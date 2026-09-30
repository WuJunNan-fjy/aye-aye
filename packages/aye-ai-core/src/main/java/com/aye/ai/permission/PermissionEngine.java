package com.aye.ai.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.ai.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 权限规则引擎（对应 DeepWork 的 {@code permission-engine.ts}，兜底对齐 Claude Code 9 步漏斗
 * / OpenCode 规则引擎的 <b>fail-closed</b> 共识：无法决策时 ASK，绝不放行）。
 *
 * <p>设计原则：规则 = match 条件 + decision 决策；数组顺序 = 优先级。
 *
 * <p>规则瀑布：硬拒绝黑名单（与信任级别无关）→ readonly 模式拒绝 → 工具自声明确认 →
 * 危险操作确认（越界路径 / git 破坏）→ 模式×类别决策矩阵 → 引擎末尾 fail-closed Confirm。
 *
 * <p>模式 × 类别决策矩阵（与前端三档承诺 1:1）：
 * <pre>
 *  类别      readonly   write_confirm(STANDARD)  all_confirm(PARANOID)  TRUSTED(全局yolo)
 *  READ      放行       放行                     确认                   放行
 *  写/执行   拒绝       确认                     确认                   放行（硬 Deny 仍优先）
 * </pre>
 * 注：{@code ask_user} 是交互通道，在 ToolExecutor 跳过权限引擎，
 * 不受 PARANOID 只读确认影响（不会出现「确认能否提问」的死锁）。
 */
public final class PermissionEngine {

    private PermissionEngine() {}

    // ==========================================================================
    // SHA-256（审计层也要用，集中放这里）
    // ==========================================================================
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    // ==========================================================================
    // 辅助：判断路径是否在 cwd 之外（越界）
    // ==========================================================================
    static boolean isPathOutsideCwd(String absPath, String cwd) {
        Path p = Paths.get(absPath).normalize();
        Path c = Paths.get(cwd).normalize();
        return !p.equals(c) && !p.startsWith(c);
    }

    // ==========================================================================
    // 远程 POSIX 路径的删除风险分级（SFTP API 通道专用；shell 通道另有 rm 黑名单）
    // ==========================================================================

    /** 远程删除目标的风险等级：硬拒绝 / 强制人工确认 / 普通流程。 */
    public enum CriticalPathLevel { DENY, CONFIRM, NONE }

    /**
     * 删除即可能导致主机变砖或 SSH 失联的系统目录（按整树前缀拦截）。
     * /usr/local 不在此类——它是管理员自装软件（非包管理器系统文件），见分类器的例外顺序。
     */
    private static final List<String> REMOTE_DENY_DIRS = List.of(
            "/boot", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/proc", "/sys", "/dev");

    /** 删除会丢失用户/数据库/应用数据或瘫痪服务、需用户强制确认的目录。 */
    private static final List<String> REMOTE_CONFIRM_DIRS = List.of(
            "/home", "/root", "/var", "/opt", "/srv", "/usr/local");

    /**
     * 对远程 POSIX 绝对路径做词法规范化：折叠重复斜杠、解析 . 与 ..、去除尾部斜杠。
     * 用于识别 {@code //etc//、/etc/./、/opt/../etc} 等绕过形态。
     *
     * <p>不做符号链接解析（需要远程主机 IO）；相对路径/空白返回 null，调用方据此放弃分级、
     * 回落到普通流程（fail-open 到既有的写确认，而非误判）。
     *
     * @return 规范化后的绝对路径；输入非绝对路径或空白时返回 null
     */
    public static String normalizePosixPath(String raw) {
        if (raw == null) return null;
        String p = raw.trim();
        if (!p.startsWith("/")) return null;
        Deque<String> stack = new ArrayDeque<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                stack.pollLast(); // 越过根的 .. 按 POSIX 词法语义丢弃
                continue;
            }
            stack.addLast(seg);
        }
        if (stack.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String seg : stack) sb.append('/').append(seg);
        return sb.toString();
   }

    /** 判断规范化路径是否等于目录本身或位于目录树下。 */
    private static boolean equalsOrUnder(String p, String dir) {
        return p.equals(dir) || p.startsWith(dir + "/");
    }

    /**
     * 对 sftp_remove 的目标路径做删除风险分级（与信任级别无关，调用方负责落地决策）。
     *
     * <p>分级顺序：{@code /} 与 {@code /etc} 精确硬拒；/usr/local 整树确认（优先于
     * /usr 的整树硬拒）；系统目录整树硬拒；/etc 子路径确认；数据/应用目录整树确认。
     */
    public static CriticalPathLevel classifyRemoteDeletion(String rawPath) {
        String p = normalizePosixPath(rawPath);
        if (p == null) return CriticalPathLevel.NONE;
        if (p.equals("/") || p.equals("/etc")) return CriticalPathLevel.DENY;
        // /usr/local 是第三方自装软件目录，删除不致系统变砖；必须先于 /usr 前缀判定。
        if (equalsOrUnder(p, "/usr/local")) return CriticalPathLevel.CONFIRM;
        for (String d : REMOTE_DENY_DIRS) {
            if (equalsOrUnder(p, d)) return CriticalPathLevel.DENY;
        }
        if (p.startsWith("/etc/")) return CriticalPathLevel.CONFIRM;
        for (String d : REMOTE_CONFIRM_DIRS) {
            if (equalsOrUnder(p, d)) return CriticalPathLevel.CONFIRM;
        }
        return CriticalPathLevel.NONE;
    }

    // ==========================================================================
    // M1 最小规则集（数组顺序 = 匹配优先级）
    // ==========================================================================
    private static final Pattern RD_RMDIR_DEL = Pattern.compile(
            "(?i)(^|[\\s;&|`])(rd|rmdir)\\s+.*/s[\\s&|`]|(^|[\\s;&|`])(del|erase)\\s+.*(/s\\s*/q|/q\\s*/s)");
    private static final Pattern FORMAT = Pattern.compile("(?i)\\bformat\\s+[A-Za-z]:");
    private static final Pattern REG_DELETE = Pattern.compile("(?i)\\breg\\s+delete\\b");
    private static final Pattern PS_IEX_IWR = Pattern.compile(
            "(?i)(Invoke-Expression|\\biex\\b).*(Invoke-WebRequest|\\biwr\\b|\\bcurl\\b|\\bwget\\b)"
                    + "|(Invoke-WebRequest|\\biwr\\b).*(Invoke-Expression|\\biex\\b)");
    private static final Pattern GIT_DESTRUCTIVE = Pattern.compile(
            "(?i)\\bgit\\s+(reset\\s+--hard|clean\\s+-fd|push\\s+-f|push\\s+--force)");
    private static final Pattern GIT_DATA_LOSS = Pattern.compile("(?i)reset\\s+--hard|clean\\s+-fd");
    private static final Pattern GIT_HISTORY = Pattern.compile("(?i)push\\s+-f|push\\s+--force");

    // ── Linux 高危命令黑名单（规则 #2.1）───────────────────────────────────
    // 与规则 #2（Windows 语义：格式化/注册表/PowerShell iex）对照：这里是 Linux 语义的
    // 不可逆破坏命令黑名单。设计（对照 Claude Code / OpenCode 的 deny 规则）：
    // 1) Deny 不依赖 trustLevel —— TRUSTED(yolo) 只放宽「确认」，不放宽「禁止」，
    //    否则 yolo 档下 rm -rf / 无阻拦；
    // 2) 正则只做 best-effort 静态形态识别：变量/eval/base64/别名可绕过正则，这层是
    //    「最后防线」，主防线是 write_confirm 下写/执行的强制确认；
    // 3) 有合法运维场景的（重启/关机、卸载核心包）走 Confirm 而非 Deny，TRUSTED 下也必须确认。
    /** 裸块设备路径：枚举常见命名（含 Xen/device-mapper/by-id 别名）；伪设备未被枚举天然排除。 */
    private static final String BLOCK_DEV =
            "/dev/(?:"
                    + "(?:sd|hd|vd|xvd)[a-z]+(?:\\d+)?"            // sda / hda / vda / xvda (+分区号)
                    + "|nvme\\d+n\\d+(?:p\\d+)?"                   // /dev/nvme0n1p2
                    + "|mmcblk\\d+(?:p\\d+)?"
                    + "|cciss/c\\d+d\\d+(?:p\\d+)?"
                    + "|loop\\d+p?\\d*"
                    + "|dm-\\d+"                                   // device-mapper 裸节点
                    + "|mapper/[A-Za-z0-9_./-]+"                   // /dev/mapper/vg-root
                    + "|disk/by-(?:id|path|uuid|partuuid)/[A-Za-z0-9_.:/-]+"
                    + ")";
    /** 块设备 token 后的合法边界：空白/结尾/shell 分隔符/重定向/引号。 */
    private static final String DEV_END = "(?=\\s|$|[;&|<>\"')\\]])";
    /** dd 写裸盘（of= 块设备；of= 在前/在后都中，参数顺序无关）。 */
    private static final Pattern DD_WRITE_RAW = Pattern.compile(
            "(?i)\\bdd\\b[^;|&\\n]*\\bof\\s*=\\s*" + BLOCK_DEV + DEV_END);
    /** 重定向写裸盘（cat x > /dev/sda）。 */
    private static final Pattern REDIRECT_RAW = Pattern.compile(
            "(?i)(?:^|[\\s;|&(])[0-9<>]*>>?\\s*" + BLOCK_DEV + DEV_END);
    /** 专用擦盘/毁分区表工具：shred 裸设备、blkdiscard、wipefs -a、sgdisk --zap。 */
    private static final Pattern DISK_WIPE = Pattern.compile(
            "(?i)\\bshred\\b[^;|&\\n]*" + BLOCK_DEV + DEV_END
                    + "|\\bblkdiscard\\b[^;|&\\n]*" + BLOCK_DEV + DEV_END
                    + "|\\bwipefs\\b[^;|&\\n]*(?:-a\\b|--all\\b)"
                    + "|\\bsgdisk\\b[^;|&\\n]*--zap(?:-all)?\\b");
    /** 格式化/交换分区/重建分区表（parted 仅 mklabel 形态命中，print 不命中）。 */
    private static final Pattern MKFS_SWAP = Pattern.compile(
            "(?i)\\bmkfs(?:\\.\\w+)?\\b|\\bmkswap\\b|\\bparted\\b[^;|&\\n]*\\bmklabel\\b");
    /** LVM 销毁（pvremove/vgremove/lvremove，存储层不可逆）。 */
    private static final Pattern LVM_REMOVE = Pattern.compile(
            "(?i)\\b(?:pvremove|vgremove|lvremove)\\b");
    /** 防火墙清空/禁用（iptables/ip6tables flush、nft flush ruleset、ufw disable、panic-on）。 */
    private static final Pattern FIREWALL_FLUSH = Pattern.compile(
            "(?i)\\bip6?tables\\b[^;|&\\n]*(?:-F\\b|--flush\\b)"
                    + "|\\bnft\\b[^;|&\\n]*\\bflush\\s+ruleset\\b"
                    + "|\\bufw\\b[^;|&\\n]*\\bdisable\\b"
                    + "|\\bfirewall-cmd\\b[^;|&\\n]*--panic-on\\b");
    /** 写 /proc/sysrq-trigger：b=硬重启 c=崩溃 o=断电，绕过优雅停机。 */
    private static final Pattern SYSRQ_TRIGGER = Pattern.compile(
            "(?i)(?:>>?|of\\s*=\\s*)\\s*/proc/sysrq-trigger\\b");
    /** 递归改整机根目录权限/属主（chmod -R /、chown -R /，含长选项与引号）。 */
    private static final Pattern CHMOD_CHOWN_ROOT = Pattern.compile(
            "(?i)\\bch(?:mod|own)\\b[^;|&\\n]*"
                    + "(?:--recursive(?:=\\S+)?|(?:^|\\s)-[A-Za-z]*R[A-Za-z]*)"
                    + "[^;|&\\n]*?\\s/[\"']?(?:\\s|$|[;&|>])");
    /** 杀 PID 1 / init / systemd / 全体进程：kill -9|KILL|SIGKILL 1|-1（两参数序都覆盖）。 */
    private static final Pattern KILL_INIT = Pattern.compile(
            "(?i)\\bkillall5\\b"
                    + "|\\bkillall\\b[^;|&\\n]*\\b(?:systemd|init|upstart)\\b"
                    + "|\\bpkill\\b[^;|&\\n]*(?:\\b(?:systemd|init|upstart)\\b|-u\\s+root\\b)"
                    + "|\\bkill\\b[^;|&\\n]*?(?:^|\\s)(?:-\\s*)?(?:9|KILL|SIGKILL)\\b[^;|&\\n]*?\\s-?1\\b"
                    + "|\\bkill\\b[^;|&\\n]*?\\s-?1\\b[^;|&\\n]*?(?:^|\\s)(?:-\\s*)?(?:9|KILL|SIGKILL)\\b");
    /** fork bomb：:(){ :|:& };（函数名是冒号，故字符类含 ':'）及 f(){ f|f& 类重命名变体。 */
    private static final Pattern FORK_BOMB = Pattern.compile(
            "(?i)\\(\\s*\\)\\s*\\{\\s*[\\w:]+\\s*\\|\\s*[\\w:]+\\s*&");
    /** find 从根/家目录起 -delete 或经 -exec/xargs 接 rm（允许跨管道匹配 xargs）。 */
    private static final Pattern FIND_ROOT_DELETE = Pattern.compile(
            "(?i)\\bfind\\s+[\"']?(?:/{1,2}(?:\\*(?:/\\*+)*)?|~(?:/)?|\\$\\{?HOME\\}?)[\"']?(?=\\s|$)"
                    + "[^;\\n]*?(?:-delete\\b|-exec(?:dir)?\\b[^;\\n]*?\\brm\\b|\\|\\s*xargs\\b[^;\\n]*?\\brm\\b)");
    /** rm 递归标志（短选项任意组合含 r/R、--recursive；flag 分开写也中）。 */
    private static final Pattern RM_RECURSIVE_FLAG = Pattern.compile(
            "(?i)(?:^|\\s)(?:--recursive(?:=\\S+)?|-[A-Za-z0-9-]*[rR][A-Za-z0-9-]*)(?:\\s|$)");
    /** rm 的根目标：/、//、/*、~、~/、~/*、$HOME（含引号）；/tmp、~/proj 等子路径不命中。 */
    private static final Pattern RM_ROOT_TARGET = Pattern.compile(
            "(?i)(?:^|\\s)[\"']?(?:"
                    + "/{1,2}(?:\\*(?:/\\*+)*)?[\"']?"
                    + "|~(?:/(?:\\*(?:/\\*+)*)?)?[\"']?"
                    + "|\\$\\{?HOME\\}?(?:/(?:\\*(?:/\\*+)*)?)?"
                    + ")(?=\\s|$|[;&|<>])");
    // 重启/关机类：有合法运维场景，故为 Confirm（TRUSTED 下同样必须确认）。
    private static final Pattern SYSTEM_DOWN = Pattern.compile(
            "(?i)\\b(?:shutdown|poweroff|reboot|halt|fasthalt|fastboot)\\b"
                    + "|\\b(?:init|telinit)\\s+[06]\\b"
                    + "|\\bsystemctl\\s+(?:reboot|poweroff|halt|kexec|rescue|emergency)\\b"
                    + "|\\bloginctl\\s+(?:power-off|reboot)\\b");
    /** 卸载系统核心组件（init/引导层/libc/SSH/sudo）→ 主机可能不可启动或失联，Confirm。 */
    private static final Pattern PKG_CORE_REMOVE = Pattern.compile(
            "(?i)\\b(?:apt|apt-get|dnf|yum|zypper)\\s+(?:remove|purge|autoremove|erase)\\b[^|;&\\n]*"
                    + "\\b(?:systemd(?:-sysv)?|sysvinit|upstart|grub2?(?:-efi)?|glibc|libc6|bash|openssh-server|sudo)\\b"
                    + "|\\bpacman\\s+-R\\w*[^|;&\\n]*\\b(?:systemd|linux|grub|glibc|bash|openssh|sudo)\\b"
                    + "|\\brpm\\s+-e\\w*[^|;&\\n]*\\b(?:systemd|glibc|bash|openssh-server)\\b");

    // 只读/写/执行 分类不再用硬编码名单，改由「工具分类注册表」提供：
    // 每个工具通过 category() 自我申报（ToolCategory.READ/WRITE/EXECUTE/DESTRUCTIVE），
    // 注册时强校验非空（ToolRegistry.register 对 null 抛异常 = fail-closed）。
    // 规则聚焦于"工具的类别"而非"工具的名字"，彻底消除"漏登记名单→default-allow"暗洞。

    /** 命令执行类工具名（历史遗留名 run_shell/bash + 实际的 run_ssh），黑名单规则共用。 */
    private static boolean isShellTool(PermissionContext ctx) {
        return "run_shell".equals(ctx.toolName()) || "bash".equals(ctx.toolName())
                || "run_ssh".equals(ctx.toolName());
    }

    /**
     * 按 shell 段（分号、管道、与、换行）检查是否存在 {@code rm 递归标志 + 根目标}。
     * 分段而非整条 find，是为了精确识别「目标 token 必须恰好是根」并覆盖 flag 分开写/长选项/
     * 引号/双斜杠/~/$HOME 等变体。已知静态盲区：{@code cd / && rm -rf .}、变量展开
     * （rm -rf "$ROOT"）、eval/base64——由写确认模式的强制确认兜底。
     */
    private static final Pattern RM_TOKEN = Pattern.compile("\\brm\\b");

    private static boolean rmWipesRoot(String cmd) {
        for (String seg : cmd.split("(?:&&|\\|\\||[;|\n])")) {
            Matcher rm = RM_TOKEN.matcher(seg);
            if (!rm.find()) continue;
            String after = seg.substring(rm.end());
            if (RM_RECURSIVE_FLAG.matcher(after).find() && RM_ROOT_TARGET.matcher(after).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 shell 段提取 rm 后的绝对路径目标，复用 {@link #classifyRemoteDeletion} 做关键目录
     * 分级——与 sftp_remove 通道共用同一事实源，避免「API 删除被拦、shell rm 漏网」。
     * 返回所有命中目标中的最高风险级（DENY &gt; CONFIRM）。
     *
     * <p>已知静态盲区（与 {@link #rmWipesRoot} 一致）：变量展开（{@code rm -rf "$X"}）、
     * eval/base64、相对路径穿越（{@code cd /etc && rm -rf nginx}）无法识别，
     * 由写确认模式的强制确认兜底。
     */
    private static CriticalPathLevel rmCriticalLevel(String cmd) {
        CriticalPathLevel best = CriticalPathLevel.NONE;
        for (String seg : cmd.split("(?:&&|\\|\\||[;|\n])")) {
            Matcher rm = RM_TOKEN.matcher(seg);
            while (rm.find()) {
                String after = seg.substring(rm.end());
                for (String raw : after.split("[\\s<>]+")) {
                    if (raw.isEmpty() || raw.startsWith("-")) continue;
                    String token = unquote(raw);
                    if (!token.startsWith("/")) continue;
                    CriticalPathLevel lvl = classifyRemoteDeletion(token);
                    if (lvl == CriticalPathLevel.DENY) return CriticalPathLevel.DENY;
                    if (lvl == CriticalPathLevel.CONFIRM) best = CriticalPathLevel.CONFIRM;
                }
            }
        }
        return best;
    }

    /** 是否存在「rm 段内含递归标志」——仅用于给关键路径确认选择破坏标签。 */
    private static boolean rmHasRecursiveFlag(String cmd) {
        for (String seg : cmd.split("(?:&&|\\|\\||[;|\n])")) {
            Matcher rm = RM_TOKEN.matcher(seg);
            if (rm.find() && RM_RECURSIVE_FLAG.matcher(seg.substring(rm.end())).find()) {
                return true;
            }
        }
        return false;
    }

    /** 去掉成对的首尾单/双引号（非成对则原样返回）。 */
    private static String unquote(String token) {
        if (token.length() >= 2) {
            char first = token.charAt(0);
            if ((first == '\'' || first == '"') && token.charAt(token.length() - 1) == first) {
                return token.substring(1, token.length() - 1);
            }
        }
        return token;
    }

    public static final List<PermissionRule> M1_PERMISSION_RULES = List.of(
            // 规则 #2：命令命中 Windows 平台最致命的 4 类黑名单（连接 Windows 主机时同样兜底）
            new PermissionRule() {
                public String name() { return "bash-windows-deadly-blacklist"; }
                public boolean matches(PermissionContext ctx) {
                    return isShellTool(ctx);
                }
                public PermissionDecision decide(PermissionContext ctx) {
                    String cmd = ctx.commandText();
                    if (RD_RMDIR_DEL.matcher(cmd).find()) {
                        return new PermissionDecision.Deny("拒绝执行递归静默删除命令（可能删除整个目录树）");
                    }
                    if (FORMAT.matcher(cmd).find()) {
                        return new PermissionDecision.Deny("拒绝执行格式化命令");
                    }
                    if (REG_DELETE.matcher(cmd).find()) {
                        return new PermissionDecision.Deny("拒绝执行注册表删除操作");
                    }
                    if (PS_IEX_IWR.matcher(cmd).find()) {
                        return new PermissionDecision.Deny("拒绝 PowerShell 远程下载并直接执行的模式（iex(iwr ...)）");
                    }
                    return null;
                }
            },

            // 规则 #2.1：Linux 高危命令黑名单 —— 拒绝**不依赖 trustLevel**（解除 P0-3 单点）
            new PermissionRule() {
                public String name() { return "linux-deadly-blacklist"; }
                public boolean matches(PermissionContext ctx) {
                    return isShellTool(ctx);
                }
                public PermissionDecision decide(PermissionContext ctx) {
                    String cmd = ctx.commandText();
                    if (cmd == null || cmd.isBlank()) return null;

                    // ── 不可逆破坏：任何信任级别（含 TRUSTED/yolo）一律 Deny ──
                    if (rmWipesRoot(cmd)) {
                        return new PermissionDecision.Deny(
                                "拒绝递归删除根目录/家目录（rm -rf /、//、~、$HOME 等变体）");
                    }
                    // rm 删除关键系统/数据目录：与 sftp_remove 同一分级事实源，TRUSTED 也不放宽。
                    // 放在根目录判定之后、其余致命黑名单之前。
                    CriticalPathLevel rmCritical = rmCriticalLevel(cmd);
                    if (rmCritical == CriticalPathLevel.DENY) {
                        return new PermissionDecision.Deny(
                                "拒绝 rm 删除远程主机关键系统路径（/etc 本身或 /usr、/boot、/bin、/sbin、"
                                        + "/lib、/lib64、/proc、/sys、/dev 等目录树下的目标）。删除可能导致主机变砖"
                                        + "或 SSH 失联且无法恢复；如确需操作，请在远程主机上由人工执行。");
                    }
                    if (rmCritical == CriticalPathLevel.CONFIRM) {
                        List<DestructiveTag> rmTags = rmHasRecursiveFlag(cmd)
                                ? List.of(DestructiveTag.RECURSIVE_FORCE_DELETE)
                                : List.of(DestructiveTag.SINGLE_FILE_DELETE);
                        return new PermissionDecision.Confirm(
                                "检测到 rm 的目标位于高风险目录（/etc 子路径或 /home、/root、/var、/opt、"
                                        + "/srv、/usr/local 目录树）：删除可能丢失用户/数据库/应用数据或导致服务瘫痪，"
                                        + "删除后无法通过本应用恢复。确认继续？",
                                rmTags, null);
                    }
                    if (FIND_ROOT_DELETE.matcher(cmd).find()) {
                        return new PermissionDecision.Deny(
                                "拒绝从根/家目录起的 find 删除（find / -delete、find / | xargs rm 等）");
                    }
                    if (DD_WRITE_RAW.matcher(cmd).find()) {
                        return new PermissionDecision.Deny("拒绝 dd 写入裸块设备（可能整盘覆盖）");
                    }
                    if (REDIRECT_RAW.matcher(cmd).find()) {
                        return new PermissionDecision.Deny("拒绝重定向写入裸块设备（> /dev/sdX 等）");
                    }
                    if (DISK_WIPE.matcher(cmd).find()) {
                        return new PermissionDecision.Deny(
                                "拒绝擦除磁盘/分区表（shred 裸设备、blkdiscard、wipefs -a、sgdisk --zap）");
                    }
                    if (MKFS_SWAP.matcher(cmd).find()) {
                        return new PermissionDecision.Deny(
                                "拒绝格式化/建交换分区/重建分区表（mkfs/mkswap/parted mklabel）");
                    }
                    if (LVM_REMOVE.matcher(cmd).find()) {
                        return new PermissionDecision.Deny(
                                "拒绝移除 PV/VG/LV（pvremove/vgremove/lvremove，存储层不可逆）");
                    }
                    if (FIREWALL_FLUSH.matcher(cmd).find()) {
                        return new PermissionDecision.Deny(
                                "拒绝清空防火墙规则/禁用防火墙（iptables -F、nft flush ruleset、ufw disable、panic-on）");
                    }
                    if (SYSRQ_TRIGGER.matcher(cmd).find()) {
                        return new PermissionDecision.Deny(
                                "拒绝写 /proc/sysrq-trigger（立即崩溃/硬重启/断电，绕过优雅停机）");
                    }
                    if (CHMOD_CHOWN_ROOT.matcher(cmd).find()) {
                        return new PermissionDecision.Deny(
                                "拒绝递归修改整机根目录权限/属主（chmod -R /、chown -R /）");
                    }
                    if (KILL_INIT.matcher(cmd).find()) {
                        return new PermissionDecision.Deny(
                                "拒绝杀死 PID 1/init/systemd 或全体进程（kill -9 1/-1、killall systemd、killall5）");
                    }
                    if (FORK_BOMB.matcher(cmd).find()) {
                        return new PermissionDecision.Deny("拒绝执行 fork bomb");
                    }

                    // ── 破坏性但存在合法运维场景：TRUSTED 下也强制 Confirm ──
                    if (SYSTEM_DOWN.matcher(cmd).find()) {
                        return new PermissionDecision.Confirm(
                                "检测到重启/关机/切换运行级别命令，可能导致服务中断，确认继续？\n命令："
                                        + cmd, List.of(DestructiveTag.SYSTEM_DOWN), null);
                    }
                    if (PKG_CORE_REMOVE.matcher(cmd).find()) {
                        return new PermissionDecision.Confirm(
                                "检测到卸载系统核心组件（init/引导层/libc/SSH/sudo），可能导致主机不可启动或失联，"
                                        + "确认继续？\n命令：" + cmd, List.of(DestructiveTag.SYSTEM_DOWN), null);
                    }
                    return null;
                }
            },

            // 规则 #2.4：run_ssh 命令可被静态证明为纯只读（ls/ps/grep/docker ps/
            // systemctl status 等查看类，白名单 + shell 词法分析，禁止复合结构/注入）→ 免确认。
            // 宁枉勿纵：证明失败（返回 false）不拒绝、不拦截，只回落后续规则照常确认。
            // 位置必须在 #2.5 之前——只读模式下「只读命令」语义安全应放行，写/执行命令才被 #2.5 拒绝；
            // 硬黑名单 #2.1 仍优先（命中即 Deny，不给本规则开口子）；PARANOID 全确认模式不适用。
            new PermissionRule() {
                public String name() { return "provably-readonly-command-auto-allow"; }
                public boolean matches(PermissionContext ctx) {
                    if (!isShellTool(ctx)) return false;
                    if (ctx.trustLevel() == TrustLevel.PARANOID) return false;
                    String cmd = ctx.commandText();
                    if (cmd == null || cmd.isBlank()) return false;
                    return ReadOnlyCommandClassifier.isProvablyReadOnly(cmd);
                }
                public PermissionDecision decide(PermissionContext ctx) {
                    return new PermissionDecision.Allow();
                }
            },

            // 规则 #2.45：sftp_remove 删除远程关键路径 → 硬 Deny / 强制确认。
            // shell 通道的 rm 黑名单扫不到 SFTP API 删除（resolveCommandText 拼出的 rm -rf '/etc'
            // 不匹配根目录正则），故按「操作语义 + 规范化目标路径」在此独立判定；
            // 位置先于类别矩阵，TRUSTED(yolo) 也不放宽。
            new PermissionRule() {
                public String name() { return "sftp-remove-critical-path"; }
                public boolean matches(PermissionContext ctx) {
                    if (!"sftp_remove".equals(ctx.toolName())) return false;
                    String path = ctx.input() == null ? "" : ctx.input().path("path").asText("");
                    return classifyRemoteDeletion(path) != CriticalPathLevel.NONE;
                }
                public PermissionDecision decide(PermissionContext ctx) {
                    String path = ctx.input() == null ? "" : ctx.input().path("path").asText("");
                    String normalized = normalizePosixPath(path);
                    if (classifyRemoteDeletion(path) == CriticalPathLevel.DENY) {
                        return new PermissionDecision.Deny(
                                "拒绝删除远程主机关键系统路径：" + normalized
                                        + "。删除可能导致主机变砖或 SSH 失联且无法恢复；"
                                        + "如确需操作，请在远程主机上由人工执行。");
                    }
                    return new PermissionDecision.Confirm(
                            "sftp_remove 的目标位于高风险目录：" + normalized
                                    + "。删除可能丢失用户/数据库/应用数据或导致服务瘫痪，"
                                    + "删除后无法通过本应用恢复。确认继续？",
                            List.of(DestructiveTag.RECURSIVE_FORCE_DELETE), null);
                }
            },

            // 规则 #2.5：readonly 执行模式下，所有写/执行工具一律拒绝（不弹确认）
            new PermissionRule() {
                public String name() { return "readonly-mode-deny-write-execute"; }
                public boolean matches(PermissionContext ctx) {
                    return "readonly".equals(ctx.executionMode()) && ctx.isWriteOrExecute();
                }
                public PermissionDecision decide(PermissionContext ctx) {
                    return new PermissionDecision.Deny(
                            "只读模式下不允许执行写/破坏性操作：" + ctx.toolName());
                }
            },

            // 规则 #3：写操作目标路径越出 cwd → 确认 + 标红越界
            new PermissionRule() {
                public String name() { return "path-escape-write-confirm"; }
                public boolean matches(PermissionContext ctx) {
                    if (ctx.category() != ToolCategory.WRITE) return false;
                    return ctx.resolvedPaths().stream().anyMatch(p -> isPathOutsideCwd(p, ctx.cwdSafe()));
                }
                public PermissionDecision decide(PermissionContext ctx) {
                    String p = ctx.resolvedPaths().isEmpty() ? "" : ctx.resolvedPaths().get(0);
                    return new PermissionDecision.Confirm(
                            "将在工作区外写文件（路径：" + p + "），是否继续？",
                            List.of(DestructiveTag.PATH_ESCAPE_WRITE), null);
                }
            },

            // 规则 #5：git 破坏性命令 → 确认 + 标破坏性标签（run_shell/bash/run_ssh 均适用）
            new PermissionRule() {
                public String name() { return "git-destructive-warning"; }
                public boolean matches(PermissionContext ctx) {
                    if (!isShellTool(ctx)) return false;
                    return GIT_DESTRUCTIVE.matcher(ctx.commandText()).find();
                }
                public PermissionDecision decide(PermissionContext ctx) {
                    List<DestructiveTag> tags = new ArrayList<>();
                    if (GIT_DATA_LOSS.matcher(ctx.commandText()).find()) tags.add(DestructiveTag.GIT_DATA_LOSS);
                    if (GIT_HISTORY.matcher(ctx.commandText()).find()) tags.add(DestructiveTag.GIT_HISTORY_REWRITE);
                    return new PermissionDecision.Confirm(
                            "检测到 Git 破坏性操作，可能丢失未提交内容或重写远程历史，确认继续？\n命令："
                                    + ctx.commandText(), tags, null);
                }
            },

            // 规则 #6：工具自身声明 requiresConfirmation === true
            // 必须排在类别放行之前（Claude Code 漏斗里 checkPermissions 在 allow 之前），
            // 否则只读工具的自声明确认会被「只读放行」短路，永远触达不到。
            new PermissionRule() {
                public String name() { return "tool-self-confirmation"; }
                public boolean matches(PermissionContext ctx) {
                    return Boolean.TRUE.equals(ctx.toolRequiresConfirmationDefault());
                }
                public PermissionDecision decide(PermissionContext ctx) {
                    return new PermissionDecision.Confirm(
                            "即将执行工具 " + ctx.toolName() + "，是否允许？",
                            new ArrayList<>(ctx.destructiveHints()), null);
                }
            },

            // 规则 #7a：全确认模式（all_confirm = PARANOID）——前端承诺「所有工具调用都需手动确认」，
            // 故只读工具也要确认（ask_user 在 ToolExecutor 跳过引擎，不受此限）。
            new PermissionRule() {
                public String name() { return "paranoid-read-confirm"; }
                public boolean matches(PermissionContext ctx) {
                    return ctx.trustLevel() == TrustLevel.PARANOID
                            && ctx.category() == ToolCategory.READ;
                }
                public PermissionDecision decide(PermissionContext ctx) {
                    return new PermissionDecision.Confirm(
                            "全确认模式：只读工具调用也需人工确认（" + ctx.toolName() + "）", List.of(), null);
                }
            },

            // 规则 #7b：只读工具在非全确认模式下放行（readonly / write_confirm / TRUSTED）。
            new PermissionRule() {
                public String name() { return "allow-readonly-tools"; }
                public boolean matches(PermissionContext ctx) {
                    return ctx.category() == ToolCategory.READ;
                }
                public PermissionDecision decide(PermissionContext ctx) {
                    return new PermissionDecision.Allow();
                }
            },

            // 规则 #7c：TRUSTED（全局配置的 yolo / bypassPermissions 模式）写/执行放行。
            // 能走到这里说明未命中任何硬 Deny 黑名单与危险操作确认——TRUSTED 只放宽确认，
            // 从不放宽禁止（rm -rf / 等已在规则 #2.1 被拦）。
            new PermissionRule() {
                public String name() { return "trusted-write-execute-allow"; }
                public boolean matches(PermissionContext ctx) {
                    return ctx.trustLevel() == TrustLevel.TRUSTED && ctx.isWriteOrExecute();
                }
                public PermissionDecision decide(PermissionContext ctx) {
                    return new PermissionDecision.Allow();
                }
            },

            // 规则 #7d：write_confirm（STANDARD）/ all_confirm（PARANOID）下写/执行需人工确认。
            // readonly 模式的写/执行已被规则 #2.5 直接拒绝，不会走到这里。
            new PermissionRule() {
                public String name() { return "non-trusted-write-execute-confirm"; }
                public boolean matches(PermissionContext ctx) {
                    return (ctx.trustLevel() == TrustLevel.STANDARD || ctx.trustLevel() == TrustLevel.PARANOID)
                            && ctx.isWriteOrExecute();
                }
                public PermissionDecision decide(PermissionContext ctx) {
                    String kind = ctx.category() == ToolCategory.EXECUTE ? "命令执行" : "写/破坏性操作";
                    return new PermissionDecision.Confirm(
                            (ctx.trustLevel() == TrustLevel.PARANOID ? "全确认模式" : "写确认模式")
                                    + "：" + kind + "需人工确认（" + ctx.toolName() + "）", List.of(), null);
                }
            }
            // 无 default-allow：所有规则都未命中（含规则抛错被跳过）时，
            // 由 evaluatePermission 末尾的 fail-closed Confirm 兜底——对齐 Claude Code/OpenCode 的 ASK 兜底。
    );

    // ==========================================================================
    // 主 API 1：evaluatePermission（遍历规则，返回决策）
    // ==========================================================================
    public static PermissionDecision evaluatePermission(PermissionContext ctx) {
        return evaluatePermission(ctx, M1_PERMISSION_RULES);
    }

    public static PermissionDecision evaluatePermission(PermissionContext ctx, List<PermissionRule> rules) {
        for (PermissionRule rule : rules) {
            boolean matched;
            try {
                matched = rule.matches(ctx);
            } catch (Exception e) {
                continue; // matches 异常 → 跳过本规则，但绝不放行（最终落 fail-closed）
            }
            if (!matched) continue;

            try {
                PermissionDecision decision = rule.decide(ctx);
                if (decision != null) return decision;
            } catch (Exception e) {
                // decision 异常 → 本规则不拍板，继续下一条（规则出错 = 请求确认，而非安全降级放行）
            }
        }
        // fail-closed 兜底（对齐 Claude Code 9 步漏斗末步 ASK / OpenCode 的 ask）：
        // 全部规则未命中或规则评估异常时，默认请求人工确认，绝不静默放行。
        return new PermissionDecision.Confirm(
                "权限规则未命中（fail-closed），请确认是否执行工具：" + ctx.toolName(), List.of(), null);
    }

    // ==========================================================================
    // 主 API 2：buildPermissionContextAndHash
    // ==========================================================================
    public record BuiltContext(PermissionContext ctx, String inputSha256, String cwd) {}

    public static BuiltContext buildPermissionContextAndHash(
            Tool tool, JsonNode parsedInput, ToolContext toolCtx, String fallbackCwd, TrustLevel fallbackTrustLevel) {
        String cwd = toolCtx.cwd() != null ? toolCtx.cwd()
                : (fallbackCwd != null ? fallbackCwd : System.getProperty("user.dir"));
        TrustLevel trustLevel = toolCtx.trustLevel() != null ? toolCtx.trustLevel()
                : (fallbackTrustLevel != null ? fallbackTrustLevel : TrustLevel.STANDARD);

        List<String> resolvedPaths = safeList(() -> tool.resolveAffectedPaths(parsedInput, toolCtx));
        String commandText = tool.resolveCommandText(parsedInput);
        List<DestructiveTag> destructiveHints = safeList(() -> tool.computeDestructiveHints(parsedInput, toolCtx));

        String serialized = Json.stringify(parsedInput == null ? null : parsedInput);
        String inputSha256 = sha256(serialized);

        PermissionContext ctx = new PermissionContext(
                tool.name(),
                parsedInput != null ? parsedInput : Json.object(),
                trustLevel,
                resolvedPaths,
                destructiveHints,
                commandText == null ? "" : commandText,
                tool.requiresConfirmation(),
                cwd,
                toolCtx.executionMode(),
                tool.category());
        return new BuiltContext(ctx, inputSha256, cwd);
    }

    private static List<String> safeList(PathSupplier supplier) {
        try {
            List<String> r = supplier.get();
            return r != null ? r : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<DestructiveTag> safeList(TagSupplier supplier) {
        try {
            List<DestructiveTag> r = supplier.get();
            return r != null ? r : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    @FunctionalInterface
    private interface PathSupplier { List<String> get() throws Exception; }

    @FunctionalInterface
    private interface TagSupplier { List<DestructiveTag> get() throws Exception; }
}
