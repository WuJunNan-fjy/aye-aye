package com.aye.ai.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Shell 命令「可证明只读」分类器（run_ssh / run_shell 免确认的安全依据）。
 *
 * <h3>为什么需要它</h3>
 * {@code run_ssh} 整个工具申报为 {@link ToolCategory#EXECUTE}，权限引擎无法区分
 * {@code ls} 与 {@code rm}，导致纯查看命令也要逐条人工确认。本类在<b>不执行</b>的前提下做
 * 严格静态证明：只有当一条命令的<b>每个</b>流水线阶段都满足「单一已知只读程序 + 参数不构成写入」时，
 * 才返回 true。
 *
 * <h3>安全原则：宁枉勿纵（fail-closed）</h3>
 * <ul>
 *   <li>判 false 只意味着「无法证明只读」→ 回到人工确认流程，<b>永不拒绝</b>，不影响可用性；</li>
 *   <li>任何动态展开（{@code $VAR}、{@code $(...)}、反引号、双引号内替换）一律无法证明；</li>
 *   <li>任何复合结构（{@code ; & && || \n}、子 shell、进程替换、重定向到文件）一律无法证明；</li>
 *   <li>唯一放行的重定向是 fd 互复制（{@code 2>&1}），不触碰文件系统；</li>
 *   <li>命令名必须命白名单；带「写入/执行」flag 的程序（find/tar/git/docker…）按子命令与参数
 *       做收窄校验，拿不准即 false。</li>
 * </ul>
 * 已知残余风险：主机本地 shell 函数/别名/可执行文件被换皮（主机已被入侵的场景超出工具层防线）、
 * 极端冷门语法盲区——所以该结论只用于「免确认」，硬黑名单（{@link PermissionEngine}）与
 * PARANOID 全确认模式不受影响。
 */
public final class ReadOnlyCommandClassifier {

    private ReadOnlyCommandClassifier() {}

    // ==================================================================================
    // 词法层
    // ==================================================================================

    /** 一个去引号后的词；dynamic=true 表示含未加单引号保护的 $ / 反引号（无法证明只读）。 */
    private record Word(String value, boolean dynamic) {}

    private static final class LexException extends Exception {
        LexException(String message) { super(message); }
    }

    /**
     * 词法切分：命令 → 流水线阶段（仅允许未引用的单个 {@code |} 作阶段分隔）。
     *
     * @throws LexException 出现复合结构 / 危险重定向 / 未闭合引号等「无法证明只读」的结构
     */
    private static List<List<Word>> lex(String cmd) throws LexException {
        List<List<Word>> stages = new ArrayList<>();
        List<Word> stage = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean hasWord = false;
        boolean dynamic = false;

        int n = cmd.length();
        int i = 0;
        while (i < n) {
            char c = cmd.charAt(i);

            // ── 空白：结词 ──
            if (c == ' ' || c == '\t') {
                if (hasWord) {
                    stage.add(new Word(cur.toString(), dynamic));
                    cur.setLength(0);
                    hasWord = false;
                    dynamic = false;
                }
                i++;
                continue;
            }
            // ── 裸换行：多命令 ──
            if (c == '\n' || c == '\r') {
                throw new LexException("包含换行（可能是多条命令）");
            }
            // ── 单引号：内容全字面（含 $ 也安全）──
            if (c == '\'') {
                int end = cmd.indexOf('\'', i + 1);
                if (end < 0) throw new LexException("单引号未闭合");
                cur.append(cmd, i + 1, end);
                hasWord = true;
                i = end + 1;
                continue;
            }
            // ── 双引号：$ 与反引号在其中仍然展开 → dynamic；\ 仅转义 $ ` " \ 与换行 ──
            if (c == '"') {
                hasWord = true;
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = cmd.charAt(i);
                    if (d == '"') { i++; closed = true; break; }
                    if (d == '$' || d == '`') { dynamic = true; cur.append(d); i++; continue; }
                    if (d == '\\' && i + 1 < n) {
                        char e = cmd.charAt(i + 1);
                        if (e == '\n' || e == '\r') { i += 2; continue; }   // 行延续
                        cur.append(e);                                       // 转义字符按字面
                        i += 2;
                        continue;
                    }
                    cur.append(d);
                    i++;
                }
                if (!closed) throw new LexException("双引号未闭合");
                continue;
            }
            // ── 未引用反斜杠：下一字符字面化（转义的元字符失去语法意义）；\换行=行延续 ──
            if (c == '\\') {
                if (i + 1 >= n) throw new LexException("行尾游离反斜杠");
                char e = cmd.charAt(i + 1);
                hasWord = true;
                if (e != '\n' && e != '\r') cur.append(e);
                i += 2;
                continue;
            }
            // ── 注释：# 必须位于词首（前邻空白/行首），到行尾结束；中间出现换行=多命令 ──
            if (c == '#' && !hasWord) {
                int j = i + 1;
                while (j < n) {
                    char d = cmd.charAt(j);
                    if (d == '\n' || d == '\r') throw new LexException("注释后存在换行（多条命令）");
                    j++;
                }
                i = n;
                break;
            }
            // ── 复合 / 子 shell / 后台 / 进程替换：全部不支持 ──
            if (c == ';') throw new LexException("包含 ; 命令分隔符");
            if (c == '&') throw new LexException("包含 & （后台/与/合并重定向）");
            if (c == '(' || c == ')') throw new LexException("包含子 shell 结构");
            if (c == '<') throw new LexException("包含输入重定向/进程替换/here-doc");
            // ── 管道：只允许单个 |，|| 拒绝 ──
            if (c == '|') {
                if (i + 1 < n && cmd.charAt(i + 1) == '|') throw new LexException("包含 || 条件连接");
                if (hasWord) {
                    stage.add(new Word(cur.toString(), dynamic));
                    cur.setLength(0);
                    hasWord = false;
                    dynamic = false;
                }
                if (stage.isEmpty()) throw new LexException("空管道阶段");
                stages.add(stage);
                stage = new ArrayList<>();
                i++;
                continue;
            }
            // ── 输出重定向：唯一放行形态 [0|1|2]>&[0|1|2]（fd 互复制，不碰文件）──
            if (c == '>') {
                if (hasWord && isPlainFd(cur)) {
                    cur.setLength(0);
                    hasWord = false;
                    dynamic = false;
                }
                int j = i + 1;
                if (j >= n || cmd.charAt(j) != '&') {
                    throw new LexException("重定向到文件或使用 >> / >| / >&-");
                }
                j++;
                if (j >= n || cmd.charAt(j) < '0' || cmd.charAt(j) > '2') {
                    throw new LexException("仅允许 fd 互复制重定向（2>&1 形态）");
                }
                j++;
                if (j < n && !isShellSpace(cmd.charAt(j))) {
                    throw new LexException("fd 重定向后紧跟多余字符（可能是 >&file）");
                }
                i = j; // fd 互复制不产生 Word，直接丢弃
                continue;
            }
            // ── 未引用的 $ / 反引号：命令替换、变量当命令名等动态结构 ──
            if (c == '`') throw new LexException("包含反引号命令替换");
            if (c == '$') dynamic = true;

            cur.append(c);
            hasWord = true;
            i++;
        }
        if (hasWord) stage.add(new Word(cur.toString(), dynamic));
        if (!stage.isEmpty()) stages.add(stage);
        if (stages.isEmpty()) throw new LexException("空命令");
        return stages;
    }

    private static boolean isShellSpace(char c) {
        return c == ' ' || c == '\t' || c == '|';
    }

    /** 词是否恰好是 0/1/2（重定向来源 fd）。 */
    private static boolean isPlainFd(CharSequence w) {
        return w.length() == 1 && w.charAt(0) >= '0' && w.charAt(0) <= '2';
    }

    // ==================================================================================
    // 白名单层
    // ==================================================================================

    /** shell 保留字 / 内建 / 可执行任意内容的解释器：出现在命令位即否决。 */
    private static final Set<String> RESERVED = Set.of(
            "!", "{", "}", "[[", "]]", "if", "then", "else", "elif", "fi",
            "for", "select", "while", "until", "do", "done", "case", "esac", "in",
            "function", "time", "coproc", "declare", "typeset", "export", "local",
            "readonly", "unset", "eval", "exec", "source", ".", "alias", "unalias",
            "builtin", "set", "shift", "trap", "ulimit", "umask", "enable", "getopts",
            "read", "let", "mapfile", "readarray", "caller", "compgen", "complete",
            "sudo", "su", "doas", "nohup", "nice", "ionice", "stdbuf", "setsid",
            "flock", "timeout", "watch", "xargs", "parallel", "tee",
            "awk", "gawk", "mawk", "sed", "perl", "php", "python", "python2",
            "python3", "ruby", "node", "deno", "bun", "lua", "lua5.1", "lua5.2",
            "lua5.3", "lua5.4", "expect", "make", "cmake", "ninja",
            "bash", "sh", "zsh", "dash", "fish", "ssh", "scp", "sftp", "rsync",
            "curl", "wget", "chroot", "nsenter", "unshare", "at", "batch",
            "fuser", "kill", "killall", "pkill", "wall", "write",
            "dd", "truncate", "cp", "mv", "rm", "rmdir", "mkdir", "touch",
            "chmod", "chown", "chgrp", "ln", "install",
            "vi", "vim", "nano", "emacs", "ed", "script");

    /**
     * 纯只读程序：任何参数都不会写入文件系统/执行外部命令（只写 stdout）。
     * 带危险 flag 的读取程序（find/tar/date/hostname 等）在 {@link #VALIDATORS} 收窄。
     */
    private static final Set<String> UNRESTRICTED_READERS = Set.of(
            // 文件/文本查看（只写 stdout）
            "ls", "cat", "head", "tail", "less", "more", "most", "zcat", "zless",
            "gzcat", "xzcat", "lzcat", "zstdcat", "file", "stat", "tac", "nl",
            "wc", "od", "hexdump", "xxd", "strings", "rev", "col", "colrm",
            "cut", "paste", "join", "look", "tr", "expand", "unexpand", "fold",
            "fmt", "pr", "column", "uniq", "comm", "cmp", "diff", "basename",
            "dirname", "realpath", "readlink", "md5sum", "sha1sum", "sha224sum",
            "sha256sum", "sha384sum", "sha512sum", "b2sum", "cksum", "sum",
            "factor", "seq", "printf", "echo", "true", "false", "test", "[",
            "expr", "getopt", "tsort", "yes",
            // 搜索（无写入 flag）
            "grep", "egrep", "fgrep", "rg", "ag",
            // 进程/系统状态
            "ps", "free", "vmstat", "iostat", "mpstat", "pidstat", "top",
            "htop", "atop", "lscpu", "lspci", "lsusb", "lsblk", "lsmod",
            "lsof", "ss", "netstat", "rtstat", "ctstat", "ipcs", "slabtop",
            "tload", "uptime", "w", "who", "whoami", "id", "groups", "users",
            "last", "lastb", "lastlog", "logname", "finger", "pinky",
            "uname", "arch", "nproc", "tty", "runlevel",
            "cal", "du", "df", "quota", "printenv", "getconf", "locale",
            "sensors", "acpi", "acpi_listen",
            "blkid", "findmnt", "lslocks",
            "vgs", "pvs", "lvs", "vgdisplay", "pvdisplay", "lvdisplay",
            "vgscan", "pvscan", "lvscan", "vgck", "dumpe2fs", "e2freefrag",
            "filefrag", "mountpoint", "objdump", "readelf", "nm", "addr2line",
            "size", "dmidecode", "biosdecode", "vpddecode", "lsmem", "lsscsi",
            "lsgpio", "lsiio", "lsinitrd", "lsns", "numastat", "cpufreq-info",
            "cpuid",
            // 网络诊断（不发起本机变更）
            "ping", "ping6", "traceroute", "traceroute6", "tracepath",
            "tracepath6", "mtr", "dig", "nslookup", "host", "getent",
            "arping", "rpcinfo", "smbstatus",
            // 杂项只读
            "pwd", "whereis", "which", "man", "whatis", "apropos", "info",
            "help", "dpkg-query");

    // ── 内建/杂项 ────────────────────────────────────────────────────────────────────

    /** env：无参数才是打印环境；带参数可能执行命令（env FOO=b ls）。 */
    private static boolean env(String[] a) { return a.length == 0; }

    /** command：仅允许 -v/-V 查询；command foo 会执行 foo。 */
    private static boolean commandBuiltin(String[] a) {
        if (a.length == 0) return false;
        for (String x : a) {
            if (!x.equals("-v") && !x.equals("-V")) return false;
        }
        return true;
    }

    /** type：只解析命令位置，不会执行。 */
    private static boolean typeBuiltin(String[] a) { return a.length > 0; }

    /** date：拒绝 -s/--set 设置时间；--date= 是解析展示，只读。 */
    private static boolean date(String[] a) {
        for (String x : a) {
            if (x.equals("-s") || x.equals("--set") || x.startsWith("--set=")) return false;
        }
        return true;
    }

    /** hostname：无位置参数=查询；带位置参数或 -F/--file/-b/--boot 是设置。 */
    private static boolean hostname(String[] a) {
        for (String x : a) {
            if (x.equals("-F") || x.equals("--file") || x.startsWith("--file=")
                    || x.equals("-b") || x.equals("--boot")) {
                return false;
            }
            if (!x.startsWith("-")) return false; // 位置参数 = 设置主机名
        }
        return true;
    }

    /** sort：拒绝 -o/--output 输出到文件（默认只写 stdout）。 */
    private static boolean sort(String[] a) {
        for (String x : a) {
            if (x.equals("-o") || x.equals("--output") || x.startsWith("--output=")) return false;
        }
        return true;
    }

    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");

    // ── find / tree / 日志 ────────────────────────────────────────────────────────────

    /** find：拒绝一切执行/删除/写结果文件的动作。 */
    private static boolean find(String[] a) {
        for (String x : a) {
            if (x.equals("-delete") || x.equals("-exec") || x.equals("-execdir")
                    || x.equals("-ok") || x.equals("-okdir")
                    || x.equals("-fls") || x.equals("-fprint") || x.equals("-fprintf")
                    || x.equals("-fappend") || x.equals("-fprint0") || x.equals("-fls0")) {
                return false;
            }
        }
        return true;
    }

    /** tree：-o 会写文件。 */
    private static boolean tree(String[] a) {
        for (String x : a) {
            if (x.equals("-o") || x.startsWith("--output-file")) return false;
        }
        return true;
    }

    /** dmesg：-c/-C/--clear/--read-clear 会清缓冲。 */
    private static boolean dmesg(String[] a) {
        for (String x : a) {
            if (x.equals("-c") || x.equals("-C") || x.equals("--clear")
                    || x.equals("--read-clear")) return false;
        }
        return true;
    }

    /** journalctl：vacuum/rotate/flush/sync/relabel 会改日志存储。 */
    private static boolean journalctl(String[] a) {
        for (String x : a) {
            if (x.startsWith("--vacuum") || x.equals("--rotate") || x.equals("--flush")
                    || x.equals("--sync") || x.equals("--relabel")) {
                return false;
            }
        }
        return true;
    }

    // ── 计划任务 / 挂载 / 内核参数 ────────────────────────────────────────────────────

    /** crontab：仅 -l/--list。 */
    private static boolean crontab(String[] a) {
        return a.length == 1 && (a[0].equals("-l") || a[0].equals("--list"));
    }

    /** mount：无参数=列出挂载点；任何参数都可能是挂载动作。 */
    private static boolean mount(String[] a) { return a.length == 0; }

    /** sysctl：拒绝 -w/--write、-p/--load 与 name=value 赋值。 */
    private static boolean sysctl(String[] a) {
        for (String x : a) {
            if (x.equals("-w") || x.equals("--write")
                    || x.equals("-p") || x.startsWith("-p") || x.equals("--load")
                    || x.startsWith("--load=") || x.equals("--system")
                    || ASSIGNMENT.matcher(x).matches()) {
                return false;
            }
        }
        return true;
    }

    /** swapon：必须 --show/-s/--summary 展示模式（该模式不会真正启用交换）。 */
    private static boolean swapon(String[] a) {
        boolean show = false;
        for (String x : a) {
            if (x.equals("--show") || x.startsWith("--show=")
                    || x.equals("-s") || x.equals("--summary")) {
                show = true;
            } else if (x.equals("--noheadings") || x.equals("--raw") || x.equals("--bytes")
                    || x.equals("--verbose") || x.equals("-v") || x.equals("--json")
                    || x.equals("-h") || x.equals("--help") || x.equals("--nofsroot")
                    || x.startsWith("--nofsroot=") || x.equals("-o") || x.equals("--output")
                    || x.startsWith("--output=")) {
                // 展示修饰（-o/--output 的列名 token 落到位置参数分支，按设备/列名放行）
            } else if (x.startsWith("-")) {
                return false;
            }
            // 位置参数（设备路径 / 输出列名）在 show 模式下只参与展示
        }
        return show;
    }

    // ── systemd / 服务管理 ───────────────────────────────────────────────────────────

    private static final Set<String> SYSTEMCTL_VALUE_FLAGS = Set.of(
            "-p", "--property", "-t", "--type", "--state", "-H", "--host",
            "-M", "--machine", "--root", "--exit-type", "--signal",
            "--kill-value", "-n", "--lines", "-o", "--output");

    private static final Set<String> SYSTEMCTL_READ_SUBS = Set.of(
            "status", "show", "cat", "list-units", "list-unit-files",
            "list-dependencies", "list-jobs", "list-timers", "list-sockets",
            "list-locations", "list-paths", "list-machines",
            "is-active", "is-enabled", "is-failed", "is-system-running",
            "get-default", "show-environment", "help", "analyze", "check");

    private static boolean systemctl(String[] a) {
        String sub = firstSubcommand(a, SYSTEMCTL_VALUE_FLAGS);
        return sub == null || SYSTEMCTL_READ_SUBS.contains(sub);
    }

    /** service NAME status。 */
    private static boolean service(String[] a) {
        return a.length == 2 && a[1].equals("status");
    }

    /** supervisorctl：状态/日志/只读元信息。 */
    private static boolean supervisorctl(String[] a) {
        String sub = null;
        for (String x : a) {
            if (!x.startsWith("-")) { sub = x; break; }
        }
        return sub != null && Set.of("status", "tail", "avail", "pid", "version",
                "maintail", "mainpid").contains(sub);
    }

    private static final Set<String> CTL_STATUS_READ_SUBS = Set.of(
            "status", "show", "list-timezones", "list-locales", "list-keymaps",
            "list-sessions", "list-users", "list-seats", "show-user",
            "show-session", "seat-status", "user-status", "session-status");

    /** timedatectl / hostnamectl / localectl / loginctl：无子命令=status，否则收窄。 */
    private static boolean statusCtl(String[] a) {
        for (String x : a) {
            if (x.startsWith("-")) continue;
            return CTL_STATUS_READ_SUBS.contains(x);
        }
        return true;
    }

    /** ufw：仅 status（可带 verbose/raw 修饰）。 */
    private static boolean ufw(String[] a) {
        if (a.length == 0 || !a[0].equals("status")) return false;
        for (int k = 1; k < a.length; k++) {
            String x = a[k];
            if (!x.equals("verbose") && !x.equals("raw") && !x.equals("--show-report")) return false;
        }
        return true;
    }

    // ── 网络 / 防火墙 ────────────────────────────────────────────────────────────────

    /** ip：OBJECT 后跟 show/list/get（省略即 show）；add/delete/set/flush 等拒绝。 */
    private static final Set<String> IP_OBJECTS = Set.of(
            "addr", "address", "link", "route", "rule", "neigh", "neighbour",
            "maddr", "mroute", "tunnel", "xfrm");
    private static final Set<String> IP_READ_CMDS = Set.of("show", "list", "lst", "get");

    private static boolean ip(String[] a) {
        int k = 0;
        while (k < a.length && a[k].startsWith("-")) k++;
        if (k >= a.length) return true; // 裸 ip = 列出
        String obj = a[k++];
        if (obj.equals("monitor") || obj.equals("help")) return true;
        if (!IP_OBJECTS.contains(obj)) return false;
        while (k < a.length && a[k].startsWith("-")) k++;
        if (k >= a.length) return true; // 省略命令 = show
        return IP_READ_CMDS.contains(a[k]);
    }

    /** ifconfig：仅无参数（或纯展示 flag）列表；指定接口+参数可能 up/down/add。 */
    private static boolean ifconfig(String[] a) {
        for (String x : a) {
            if (!x.equals("-a") && !x.equals("-s") && !x.equals("-v")
                    && !x.equals("--help") && !x.equals("-V")) {
                return false;
            }
        }
        return true;
    }

    private static final String IPTABLES_MUTATE_CHARS = "ACDRINXPEFZM";
    private static final Set<String> IPTABLES_MUTATE_LONG = Set.of(
            "--append", "--check", "--delete", "--insert", "--replace",
            "--new-chain", "--delete-chain", "--policy", "--rename-chain",
            "--flush", "--zero", "--modprobe");

    /** iptables：必须显式列举(-L/-S)，短选项簇含增删改字符、--modprobe 等一律拒绝。 */
    private static boolean iptables(String[] a) {
        boolean hasList = false;
        for (String x : a) {
            if (x.startsWith("--")) {
                for (String bad : IPTABLES_MUTATE_LONG) {
                    if (x.equals(bad) || x.startsWith(bad + "=")) return false;
                }
                if (x.equals("--list") || x.equals("--list-rules")) hasList = true;
                continue;
            }
            if (x.startsWith("-")) {
                for (int k = 1; k < x.length(); k++) {
                    char ch = x.charAt(k);
                    if (IPTABLES_MUTATE_CHARS.indexOf(ch) >= 0) return false;
                    if (ch == 'L' || ch == 'S') hasList = true;
                }
            }
        }
        return hasList;
    }

    /** nft：只放行 list（add/delete/flush/replace 等拒绝）。 */
    private static boolean nft(String[] a) {
        for (String x : a) {
            if (x.startsWith("-")) continue;
            return x.equals("list");
        }
        return false;
    }

    /** ipset：list/save/test/version 只读。 */
    private static boolean ipset(String[] a) {
        for (String x : a) {
            if (x.startsWith("-")) continue;
            return Set.of("list", "save", "test", "version", "x").contains(x);
        }
        return false;
    }

    /** tc：OBJECT show|list，或 monitor；add/del/replace/change 拒绝。 */
    private static boolean tc(String[] a) {
        int k = 0;
        while (k < a.length && a[k].startsWith("-")) k++;
        if (k >= a.length) return false;
        if (a[k].equals("monitor")) return true;
        if (!Set.of("qdisc", "class", "filter", "action", "chain").contains(a[k])) return false;
        k++;
        while (k < a.length && a[k].startsWith("-")) k++;
        return k < a.length && (a[k].equals("show") || a[k].equals("list"));
    }

    /** tcpdump：拒绝 -w/-G/-W/-U/-z 写文件/轮转/后处理；其余打印到终端。 */
    private static boolean tcpdump(String[] a) {
        for (int k = 0; k < a.length; k++) {
            String x = a[k];
            if (x.equals("-w") || x.startsWith("-w") || x.equals("--file")
                    || x.equals("-G") || x.startsWith("-G") || x.equals("-W")
                    || x.startsWith("-W") || x.equals("--rotate-seconds")
                    || x.startsWith("--rotate-seconds") || x.equals("--rotate-count")
                    || x.startsWith("--rotate-count") || x.equals("-U")
                    || x.equals("--packet-buffered") || x.equals("-z")
                    || x.equals("--postrotate-command") || x.startsWith("-z")) {
                return false;
            }
        }
        return true;
    }

    // ── 磁盘 / 文件系统 / 硬件 ───────────────────────────────────────────────────────

    /** fdisk/sfdisk：必须 -l/--list。 */
    private static boolean fdiskList(String[] a) {
        boolean list = false;
        for (String x : a) {
            if (x.equals("-l") || x.equals("--list")) { list = true; continue; }
            if (x.startsWith("-")) return false;
            // 位置参数只能是设备
            if (!x.contains("/") && !looksLikeDiskName(x)) return false;
        }
        return list;
    }

    private static boolean looksLikeDiskName(String x) {
        return x.startsWith("sd") || x.startsWith("hd") || x.startsWith("vd")
                || x.startsWith("xvd") || x.startsWith("nvme") || x.startsWith("loop")
                || x.startsWith("mmcblk") || x.startsWith("cciss");
    }

    /** parted：只允许 print（可带 unit/compact）；mklabel/mkpart/rm/set 等拒绝。 */
    private static final Set<String> PARTED_MUTATE = Set.of(
            "mklabel", "mkpart", "mkpartfs", "mkfs", "rm", "resize", "rescue",
            "move", "set", "name", "toggle", "align-check", "disk_set",
            "disk_toggle");

    private static boolean parted(String[] a) {
        boolean readAction = false;
        for (String x : a) {
            if (x.startsWith("-")) continue;
            if (x.equals("print") || x.equals("list") || x.equals("devices")
                    || x.equals("version") || x.equals("machine") || x.equals("unit")
                    || x.equals("compact") || x.equals("help") || x.equals("free")
                    || x.equals("all")) {
                if (x.equals("print") || x.equals("list") || x.equals("devices")
                        || x.equals("version")) readAction = true;
                continue;
            }
            if (PARTED_MUTATE.contains(x)) return false;
            // 其余 token 视为设备路径
            if (!x.contains("/") && !looksLikeDiskName(x)
                    && !Set.of("s", "B", "KiB", "MiB", "GiB", "TiB", "KB", "MB",
                            "GB", "TB", "%", "cyl", "chs", "compact").contains(x)) {
                return false;
            }
        }
        return readAction;
    }

    /** tune2fs：仅 -l（列超级块）。 */
    private static boolean tune2fs(String[] a) {
        return a.length >= 1 && a[0].equals("-l");
    }

    /** smartctl：拒绝开关/设置类(-s/-S/-t/-C/--set)；-a/-H/-i/-x 等为读取。 */
    private static boolean smartctl(String[] a) {
        for (String x : a) {
            if (x.equals("-s") || x.startsWith("-s")
                    || x.equals("-S") || x.startsWith("-S")
                    || x.equals("-t") || x.startsWith("-t")
                    || x.equals("-C") || x.startsWith("-C")
                    || x.startsWith("--set")) {
                return false;
            }
        }
        return true;
    }

    /** ethtool：拒绝改配置/驱动动作(-s/-K/-L/-G/-C/-A/-E/-r/-N/-U/-W/-p 等)。 */
    private static boolean ethtool(String[] a) {
        for (String x : a) {
            if (x.equals("-s") || x.equals("-K") || x.equals("-L") || x.equals("-G")
                    || x.equals("-C") || x.equals("-A") || x.equals("-E") || x.equals("-r")
                    || x.equals("-N") || x.equals("-U") || x.equals("-W") || x.equals("-p")
                    || x.equals("--change") || x.equals("--identify")
                    || x.startsWith("--set-") || x.startsWith("--config-")) {
                return false;
            }
        }
        return true;
    }

    /** cryptsetup：只放行状态/转储类（luksHeaderBackup 写文件，不在其列）。 */
    private static boolean cryptsetup(String[] a) {
        for (String x : a) {
            if (x.startsWith("-")) continue;
            return Set.of("status", "isLuks", "is-luks", "luksDump", "luksdump",
                    "luksUUID", "luks-uuid", "trueUUID", "resolve", "uuid",
                    "dump").contains(x);
        }
        return false;
    }

    // ── 打包 / 压缩 ──────────────────────────────────────────────────────────────────

    /** tar：必须列举(-t)或对比(-d)；短选项簇含 c/x/u/r/A、长选项 create/extract 等拒绝。 */
    private static boolean tar(String[] a) {
        boolean listOrDiff = false;
        for (String x : a) {
            if (x.startsWith("--")) {
                if (x.equals("--list") || x.equals("--diff") || x.equals("--compare")) {
                    listOrDiff = true;
                } else if (x.equals("--create") || x.equals("--extract") || x.equals("--get")
                        || x.equals("--append") || x.equals("--update") || x.equals("--catenate")
                        || x.equals("--concatenate") || x.equals("--delete")
                        || x.startsWith("--delete")) {
                    return false;
                }
                continue;
            }
            if (x.startsWith("-")) {
                for (int k = 1; k < x.length(); k++) {
                    char ch = x.charAt(k);
                    if (ch == 'c' || ch == 'x' || ch == 'u' || ch == 'r' || ch == 'A') return false;
                    if (ch == 't' || ch == 'd') listOrDiff = true;
                }
            }
        }
        return listOrDiff;
    }

    /** 压缩器：仅 -t/--test 测试完整性，否则会原地改写文件。 */
    private static boolean testOnly(String[] a) {
        for (String x : a) {
            if (x.equals("-t") || x.equals("--test")) return true;
        }
        return false;
    }

    /** unzip：必须 -l/-Z/--list 列举。 */
    private static boolean unzip(String[] a) {
        if (a.length == 0) return false;
        return a[0].equals("-l") || a[0].equals("-Z") || a[0].equals("--list");
    }

    /** 7z 系：仅 l（列举）/t（测试）。 */
    private static boolean sevenz(String[] a) {
        for (String x : a) {
            if (x.startsWith("-")) continue;
            return x.equals("l") || x.equals("t") || x.equals("lt") || x.equals("lr");
        }
        return false;
    }

    // ── 包管理 ────────────────────────────────────────────────────────────────────────

    /**
     * dpkg：短选项簇含 q=查询模式（伴随字母限查询修饰）；无 q 则整簇只能是列举/校验字母；
     * 含 i(安装)/e(解包)/r(移除)/P(清除)/G/A/B/T 动作字母一律拒绝；长选项走查询白名单。
     */
    private static boolean dpkg(String[] a) {
        if (a.length == 0) return false;
        // 查询模式下安全的短选项字母（q 模式修饰：i=info l=list s=status c=configfiles…）
        final String queryChars = "qlLsSaivcd f p R o z D h? ";
        final String plainReadChars = "lLsSVCpdfzDh?";
        final String actionChars = "erPGABT"; // i 单独处理：无 q 时 i=install
        for (String x : a) {
            if (x.startsWith("--")) {
                if (!Set.of("--list", "--status", "--listfiles", "--search", "--verify",
                        "--print-avail", "--audit", "--get-selections",
                        "--print-architecture", "--version", "--help", "--listpaths",
                        "--compare-versions", "--field").contains(x)) {
                    return false;
                }
                continue;
            }
            if (x.startsWith("-")) {
                boolean queryMode = x.indexOf('q') >= 0;
                String allowed = queryMode ? queryChars : plainReadChars;
                for (int k = 1; k < x.length(); k++) {
                    char ch = x.charAt(k);
                    if (ch == 'q') continue;
                    if (actionChars.indexOf(ch) >= 0) return false;
                    if (ch == 'i' && !queryMode) return false;
                    if (allowed.indexOf(ch) < 0) return false;
                }
            }
            // 位置参数：包名 / glob / .deb 路径，不改变动作语义，放行
        }
        return true;
    }

    private static final Set<String> APT_READ_SUBS = Set.of(
            "list", "show", "search", "depends", "rdeps", "policy", "changelog",
            "moo", "help", "version");

    /** apt / apt-get：第一个非选项词必须是查询子命令。 */
    private static boolean apt(String[] a) {
        for (String x : a) {
            if (x.startsWith("-")) continue;
            return APT_READ_SUBS.contains(x);
        }
        return false;
    }

    /** apt-cache：子命令均为只读。 */
    private static boolean aptCache(String[] a) { return a.length > 0; }

    private static final Set<String> DNF_READ_SUBS = Set.of(
            "list", "info", "search", "provides", "whatprovides", "repolist",
            "check-update", "grouplist", "groupinfo", "deplist", "resolvedep",
            "history", "help", "version", "repoquery");

    /** dnf/yum：history 再收窄到 list/info/userinstalled。 */
    private static boolean dnf(String[] a) {
        int idx = 0;
        while (idx < a.length && a[idx].startsWith("-")) idx++;
        if (idx >= a.length) return false;
        String sub = a[idx];
        if (sub.equals("history")) {
            if (idx + 1 >= a.length) return true;
            String h = a[idx + 1];
            return h.equals("list") || h.startsWith("list") || h.equals("info")
                    || h.startsWith("info") || h.equals("userinstalled");
        }
        return DNF_READ_SUBS.contains(sub);
    }

    /** rpm：必须查询(-q)/校验(-V)/验签(-K)模式；--import/--rebuilddb 等拒绝。 */
    private static boolean rpm(String[] a) {
        if (a.length == 0) return false;
        boolean readMode = false;
        for (int k = 0; k < a.length; k++) {
            String x = a[k];
            if (x.startsWith("--")) {
                if (x.equals("--query") || x.equals("--verify") || x.equals("--checksig")
                        || x.equals("--version") || x.equals("--showrc")
                        || x.equals("--eval")) {
                    readMode = true;
                } else if (x.equals("--import") || x.equals("--resign") || x.equals("--addsign")
                        || x.equals("--setperms") || x.equals("--setugids")
                        || x.equals("--initdb") || x.equals("--rebuilddb")
                        || x.equals("--erase") || x.equals("--install")
                        || x.equals("--upgrade") || x.equals("--freshen")) {
                    return false;
                }
                continue;
            }
            if (x.startsWith("-")) {
                for (int j = 1; j < x.length(); j++) {
                    char ch = x.charAt(j);
                    if (ch == 'q' || ch == 'V' || ch == 'K') readMode = true;
                    if (ch == 'i' || ch == 'e' || ch == 'U' || ch == 'F') return false;
                }
            }
        }
        return readMode;
    }

    private static final Set<String> PIP_READ_SUBS = Set.of(
            "list", "show", "freeze", "check", "config", "cache", "debug", "index");

    /** pip：config 只允许 list/get；cache 只允许 list/info/dir。 */
    private static boolean pip(String[] a) {
        int idx = 0;
        while (idx < a.length && a[idx].startsWith("-")) idx++;
        if (idx >= a.length) return false;
        String sub = a[idx];
        if (sub.equals("config")) {
            return idx + 1 < a.length && Set.of("list", "get", "debug").contains(a[idx + 1]);
        }
        if (sub.equals("cache")) {
            return idx + 1 < a.length && Set.of("list", "info", "dir").contains(a[idx + 1]);
        }
        return PIP_READ_SUBS.contains(sub);
    }

    private static final Set<String> NPM_READ_SUBS = Set.of(
            "list", "ls", "la", "ll", "view", "show", "info", "v", "outdated",
            "audit", "config", "explain", "ping", "prefix", "root", "bin",
            "doctor", "version", "versions");

    /** npm：config 只允许 get。 */
    private static boolean npm(String[] a) {
        int idx = 0;
        while (idx < a.length && a[idx].startsWith("-")) idx++;
        if (idx >= a.length) return true;
        String sub = a[idx];
        if (sub.equals("config")) {
            return idx + 1 < a.length && a[idx + 1].equals("get");
        }
        return NPM_READ_SUBS.contains(sub);
    }

    // ── git ──────────────────────────────────────────────────────────────────────────

    private static final Set<String> GIT_VALUE_GLOBALS = Set.of(
            "-C", "--git-dir", "--work-tree", "--namespace", "--super-prefix",
            "--shallow-file", "--alternate-refs", "--config-env");

    private static final Set<String> GIT_READ_SUBS = Set.of(
            "status", "log", "show", "diff", "blame", "rev-parse", "ls-files",
            "ls-remote", "ls-tree", "shortlog", "describe", "cat-file",
            "count-objects", "for-each-ref", "name-rev", "var", "verify-tag",
            "verify-commit", "fsck", "merge-base", "merge-tree", "whatchanged",
            "grep", "reflog", "cherry", "range-diff", "check-attr",
            "check-ignore", "check-mailmap", "check-ref-format", "diff-tree",
            "diff-index", "diff-files", "version", "help",
            "remote", "branch", "tag", "stash", "submodule", "worktree",
            "config", "lfs", "bundle");

    /** git：拒绝 -c 别名注入；子命令白名单 + 家族二级收窄 + diff 系禁 --output。 */
    private static boolean git(String[] a) {
        int k = 0;
        while (k < a.length) {
            String x = a[k];
            if (x.equals("-c") || x.startsWith("-c") || x.equals("--config-env")
                    || x.startsWith("--config-env=")) {
                return false; // git -c alias.x='!cmd' 可借别名执行任意命令
            }
            if (GIT_VALUE_GLOBALS.contains(x)) { k += 2; continue; }
            if (x.startsWith("--git-dir=") || x.startsWith("--work-tree=")
                    || x.startsWith("--namespace=") || x.startsWith("--super-prefix=")
                    || x.startsWith("--shallow-file=") || x.startsWith("--alternate-refs=")) {
                k++;
                continue;
            }
            if (x.startsWith("-")) { k++; continue; }
            break;
        }
        if (k >= a.length) return true;
        String sub = a[k];
        String[] rest = new String[a.length - k - 1];
        System.arraycopy(a, k + 1, rest, 0, rest.length);

        if (!GIT_READ_SUBS.contains(sub)) return false;

        if (sub.equals("diff") || sub.equals("show") || sub.equals("log")
                || sub.equals("whatchanged") || sub.equals("range-diff")) {
            for (String x : rest) {
                if (x.equals("--output") || x.startsWith("--output=")) return false;
            }
        }
        switch (sub) {
            case "remote":
                return gitRemote(rest);
            case "branch":
                return gitBranch(rest);
            case "tag":
                return gitTag(rest);
            case "stash":
                return rest.length == 0 || rest[0].equals("list") || rest[0].equals("show");
            case "submodule":
                return rest.length > 0
                        && (rest[0].equals("status") || rest[0].equals("summary"));
            case "worktree":
                return rest.length > 0 && rest[0].equals("list");
            case "config":
                return gitConfig(rest);
            case "lfs":
                return rest.length > 0 && Set.of("ls-files", "logs", "status",
                        "env", "version").contains(rest[0]);
            case "bundle":
                return rest.length > 0
                        && (rest[0].equals("verify") || rest[0].equals("list-heads"));
            default:
                return true;
        }
    }

    /** remote：无参/-v 列举；show/get-url/get-all-url 只读（prune 拒绝）。 */
    private static boolean gitRemote(String[] a) {
        if (a.length == 0) return true;
        for (String x : a) {
            if (x.equals("-v") || x.equals("--verbose")) continue;
            if (x.startsWith("-")) return false;
            return x.equals("show") || x.equals("get-url") || x.equals("get-all-url");
        }
        return true;
    }

    /** branch：仅列举类 flag；出现位置参数（建/删目标）即拒绝。 */
    private static boolean gitBranch(String[] a) {
        for (String x : a) {
            if (x.equals("--show-current") || x.equals("--list") || x.equals("-l")
                    || x.equals("-a") || x.equals("--all") || x.equals("-r")
                    || x.equals("--remotes") || x.equals("-v") || x.equals("-vv")
                    || x.equals("-q") || x.equals("--no-color") || x.equals("-i")
                    || x.equals("--ignore-case") || x.startsWith("--color")
                    || x.startsWith("--sort=") || x.startsWith("--format=")
                    || x.startsWith("--column=")) {
                continue;
            }
            return false; // --contains X 等带值情况严格交确认
        }
        return true;
    }

    /** tag：仅列举类 flag；位置参数（tag 名/模式）严格交确认。 */
    private static boolean gitTag(String[] a) {
        for (String x : a) {
            if (x.equals("-l") || x.equals("--list") || x.equals("-i")
                    || x.equals("--ignore-case") || x.equals("--no-color")
                    || x.startsWith("-n") || x.startsWith("--sort=")
                    || x.startsWith("--format=") || x.startsWith("--column=")
                    || x.startsWith("--color")) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * config：显式 getter（--get/--get-all/--get-regexp/--list/--get-color*）放行；
     * 否则按位置参数判定——0~1 个 key 是查询，2 个（key value）是设置；
     * --unset、--add、--replace-all、--edit、--rename-section 等一律拒绝。
     */
    private static boolean gitConfig(String[] a) {
        int positional = 0;
        boolean getter = false;
        for (int k = 0; k < a.length; k++) {
            String x = a[k];
            if (x.equals("--get") || x.equals("--get-all") || x.equals("--get-regexp")
                    || x.equals("--get-color") || x.equals("--get-colorbool")
                    || x.startsWith("--get=") || x.startsWith("--get-all=")
                    || x.startsWith("--get-regexp=") || x.equals("-l") || x.equals("--list")
                    || x.equals("--name-only") || x.equals("--show-origin")
                    || x.equals("--show-scope") || x.equals("--null") || x.equals("-z")
                    || x.equals("--includes") || x.equals("--no-includes")) {
                getter = true;
                continue;
            }
            if (x.equals("--unset") || x.equals("--unset-all") || x.equals("--unset-regexp")
                    || x.equals("--add") || x.equals("--replace-all") || x.equals("-e")
                    || x.equals("--edit") || x.equals("--rename-section")
                    || x.equals("--remove-section") || x.equals("--fixed-value")
                    || x.equals("--bool") || x.equals("--int") || x.equals("--path")
                    || x.startsWith("--type") || x.equals("--default")
                    || x.startsWith("--default=")) {
                // --bool/--int/--path/--type/--default 只在 getter 上下文安全
                if (x.equals("--unset") || x.equals("--unset-all") || x.equals("--unset-regexp")
                        || x.equals("--add") || x.equals("--replace-all") || x.equals("-e")
                        || x.equals("--edit") || x.equals("--rename-section")
                        || x.equals("--remove-section")) {
                    return false;
                }
                continue;
            }
            if (x.equals("-f") || x.equals("--file") || x.equals("--blob")
                    || x.startsWith("--file=") || x.startsWith("--blob=")) {
                if (!x.contains("=")) k++; // 跳过值（被读取的配置源）
                continue;
            }
            if (x.startsWith("-")) continue;
            positional++;
        }
        return getter || positional <= 1;
    }

    // ── Docker / 容器运行时 ──────────────────────────────────────────────────────────

    private static final Set<String> DOCKER_GLOBAL_VALUE_FLAGS = Set.of(
            "--config", "--context", "--host", "-H", "--log-level",
            "--tlscacert", "--tlscert", "--tlskey", "--ssh");

    private static final Set<String> OBJ_IMAGE_READ = Set.of("ls", "list", "inspect", "history");
    private static final Set<String> OBJ_COMMON_READ = Set.of("ls", "list", "inspect", "show");
    private static final Set<String> OBJ_CONTAINER_READ = Set.of(
            "ls", "list", "inspect", "logs", "top", "port", "stats", "ps");
    private static final Set<String> OBJ_NODE_READ = Set.of("ls", "list", "ps", "inspect");
    private static final Set<String> OBJ_SERVICE_READ = Set.of("ls", "list", "ps", "inspect", "logs");
    private static final Set<String> OBJ_SYSTEM_READ = Set.of("df", "info", "events");
    private static final Set<String> OBJ_BUILDX_READ = Set.of("ls", "du", "inspect", "history");

    private static boolean docker(String[] a) {
        int k = skipGlobalFlags(a, 0, DOCKER_GLOBAL_VALUE_FLAGS);
        if (k >= a.length) return true;
        String sub = a[k++];
        return switch (sub) {
            case "ps", "inspect", "logs", "stats", "images", "history", "top", "port",
                 "version", "info", "events", "search", "wait" -> true;
            case "image" -> nested(a, k, OBJ_IMAGE_READ, DOCKER_GLOBAL_VALUE_FLAGS);
            case "container" -> nested(a, k, OBJ_CONTAINER_READ, DOCKER_GLOBAL_VALUE_FLAGS);
            case "network", "volume" -> nested(a, k, OBJ_COMMON_READ, DOCKER_GLOBAL_VALUE_FLAGS);
            case "node" -> nested(a, k, OBJ_NODE_READ, DOCKER_GLOBAL_VALUE_FLAGS);
            case "service" -> nested(a, k, OBJ_SERVICE_READ, DOCKER_GLOBAL_VALUE_FLAGS);
            case "config", "secret" -> nested(a, k, OBJ_COMMON_READ, DOCKER_GLOBAL_VALUE_FLAGS);
            case "plugin" -> nested(a, k, OBJ_COMMON_READ, DOCKER_GLOBAL_VALUE_FLAGS);
            case "buildx" -> nested(a, k, OBJ_BUILDX_READ, DOCKER_GLOBAL_VALUE_FLAGS);
            case "context" -> nested(a, k, OBJ_COMMON_READ, DOCKER_GLOBAL_VALUE_FLAGS);
            case "manifest" -> nested(a, k, Set.of("inspect"), DOCKER_GLOBAL_VALUE_FLAGS);
            case "system" -> nested(a, k, OBJ_SYSTEM_READ, DOCKER_GLOBAL_VALUE_FLAGS);
            case "compose" -> dockerCompose(a, k);
            default -> false;
        };
    }

    private static final Set<String> COMPOSE_VALUE_FLAGS = Set.of(
            "-f", "--file", "-p", "--project-name", "--project-directory",
            "--profile", "--env-file", "--ansi", "--log-level",
            "--default-resolution", "--tlscacert", "--tlscert", "--tlskey",
            "-H", "--host", "--context");

    private static final Set<String> COMPOSE_READ_SUBS = Set.of(
            "ps", "logs", "config", "images", "ls", "top", "port", "version",
            "events", "inspect", "services", "list");

    private static boolean dockerCompose(String[] a, int start) {
        int k = skipGlobalFlags(a, start, COMPOSE_VALUE_FLAGS);
        return k < a.length && COMPOSE_READ_SUBS.contains(a[k]);
    }

    private static boolean nested(String[] a, int start, Set<String> allowed, Set<String> valueFlags) {
        int k = skipGlobalFlags(a, start, valueFlags);
        if (k >= a.length) return true; // 无子命令默认列举
        return allowed.contains(a[k]);
    }

    /** 跳过 CLI 全局 flag；valueFlags 中的 flag 连带跳过其值。 */
    private static int skipGlobalFlags(String[] a, int start, Set<String> valueFlags) {
        int k = start;
        while (k < a.length) {
            String x = a[k];
            if (valueFlags.contains(x)) { k += 2; continue; }
            if (x.startsWith("-")) { k++; continue; }
            break;
        }
        return k;
    }

    /** nerdctl：只读子集（compose 复用同一套收窄）。 */
    private static boolean nerdctl(String[] a) {
        int k = skipGlobalFlags(a, 0, DOCKER_GLOBAL_VALUE_FLAGS);
        if (k >= a.length) return false;
        String sub = a[k];
        if (sub.equals("compose")) return dockerCompose(a, k + 1);
        if (Set.of("image", "network", "volume", "container", "namespace").contains(sub)) {
            int j = skipGlobalFlags(a, k + 1, DOCKER_GLOBAL_VALUE_FLAGS);
            return j >= a.length || OBJ_COMMON_READ.contains(a[j]);
        }
        return Set.of("ps", "logs", "images", "inspect", "stats", "info", "version",
                "events", "top", "port").contains(sub);
    }

    /** crictl：只读子集。 */
    private static boolean crictl(String[] a) {
        for (String x : a) {
            if (x.startsWith("-")) continue;
            return Set.of("ps", "inspect", "logs", "images", "inspecti", "info",
                    "version", "stats", "pods", "inspectp", "status",
                    "imagefsinfo", "runtimes", "options", "config").contains(x);
        }
        return true;
    }

    // ── Kubernetes / Helm ────────────────────────────────────────────────────────────

    private static final Set<String> KUBECTL_VALUE_FLAGS = Set.of(
            "-s", "--server", "--kubeconfig", "--context", "-n", "--namespace",
            "--cluster", "--user", "--as", "--as-uid", "--as-group",
            "--certificate-authority", "--client-certificate", "--client-key",
            "--token", "--request-timeout", "--cache-dir", "-o", "--output",
            "-l", "--selector", "--field-selector", "--since", "--tail",
            "--limit", "--chunk-size", "-v", "--max-log-chars",
            "--pod-running-timeout", "-f", "--filename", "-k", "--kustomize",
            "--sort-by", "--subresource", "--username", "--password");

    /** kubectl：get/describe/logs/top/explain 等只读子命令；config/auth 二级收窄。 */
    private static boolean kubectl(String[] a) {
        int k = skipGlobalFlags(a, 0, KUBECTL_VALUE_FLAGS);
        if (k >= a.length) return true;
        String sub = a[k++];
        return switch (sub) {
            case "get", "describe", "logs", "top", "explain", "version",
                 "cluster-info", "api-resources", "api-versions", "events", "options" -> true;
            case "config" -> {
                int j = k;
                while (j < a.length && a[j].startsWith("-")) j++;
                yield j < a.length && Set.of("view", "current-context", "get-clusters",
                        "get-contexts", "get-users", "current").contains(a[j]);
            }
            case "auth" -> {
                int j = k;
                while (j < a.length && a[j].startsWith("-")) j++;
                yield j < a.length && (a[j].equals("can-i") || a[j].equals("whoami"));
            }
            default -> false;
        };
    }

    private static final Set<String> HELM_VALUE_FLAGS = Set.of(
            "--kubeconfig", "--kube-context", "--kube-apiserver", "--namespace", "-n",
            "--burst", "--qps", "-f", "--values", "--set", "--set-string",
            "--set-file", "--version", "--filter-regex", "--repository-config",
            "--repository-cache", "--key", "--keyring", "--cert-file", "--ca-file");

    /** helm：list/status/history/get/show/template/lint/verify/search/repo list 只读。 */
    private static boolean helm(String[] a) {
        int k = skipGlobalFlags(a, 0, HELM_VALUE_FLAGS);
        if (k >= a.length) return false;
        String sub = a[k++];
        return switch (sub) {
            case "list", "ls", "status", "history", "version", "env", "lint",
                 "template", "verify", "search" -> true;
            case "get", "show", "inspect" -> {
                int j = k;
                while (j < a.length && a[j].startsWith("-")) j++;
                yield j < a.length && Set.of("all", "hooks", "manifest", "values",
                        "notes", "readme", "charts").contains(a[j]);
            }
            case "repo" -> {
                int j = k;
                while (j < a.length && a[j].startsWith("-")) j++;
                yield j < a.length && Set.of("list", "search").contains(a[j]);
            }
            default -> false;
        };
    }

    // ── pm2 / redis-cli ──────────────────────────────────────────────────────────────

    private static final Set<String> PM2_READ = Set.of(
            "list", "ls", "jlist", "prettylist", "describe", "desc", "info",
            "status", "monit", "logs", "show", "ping", "version", "env", "id");

    private static boolean pm2(String[] a) {
        if (a.length == 0) return true; // 无参 = 只读 dashboard
        return PM2_READ.contains(a[0]);
    }

    private static final Set<String> REDIS_READ_CMDS = Set.of(
            "info", "ping", "dbsize", "time", "echo", "get", "mget", "strlen",
            "exists", "keys", "scan", "type", "ttl", "pttl", "object",
            "hget", "hmget", "hgetall", "hkeys", "hvals", "hlen", "hexists",
            "hrandfield", "lindex", "llen", "lrange", "lpos",
            "sismember", "smembers", "scard", "srandmember", "smismember",
            "zscore", "zrange", "zrevrange", "zrangebyscore", "zrevrangebyscore",
            "zrank", "zrevrank", "zcount", "zcard", "zlexcount", "zmscore",
            "zrandmember", "xrange", "xrevrange", "xlen", "xinfo", "xpending",
            "config", "slowlog", "client", "memory", "cluster", "acl",
            "latency", "lastsave", "randomkey", "wait", "readonly", "command",
            "geodist", "geohash", "geopos", "geosearch", "georadius_ro",
            "georadiusbymember_ro", "bitcount", "bitpos", "pfcount",
            "getrange", "substr", "dump", "lcs", "pubsub", "function",
            "sentinel", "asking");

    /** redis-cli：先拦写入型开关（--rdb/--pipe/--eval/--cluster/--replica），再校验命令。 */
    private static boolean redisCli(String[] a) {
        for (String x : a) {
            if (x.equals("--rdb") || x.startsWith("--rdb=")
                    || x.equals("--pipe") || x.startsWith("--pipe")
                    || x.equals("--eval") || x.startsWith("--eval")
                    || x.equals("--ldb") || x.equals("--ldb-sync-mode")
                    || x.equals("--cluster") || x.startsWith("--cluster")
                    || x.equals("--replica") || x.equals("--slave")) {
                return false;
            }
        }
        int k = 0;
        while (k < a.length && a[k].startsWith("-")) {
            String x = a[k];
            if (Set.of("-h", "-p", "-n", "-a", "--user", "--cacert", "--cacertdir",
                    "--cert", "--key", "--sni", "-t", "--pattern", "--scan-pattern",
                    "--pipe-timeout").contains(x)) {
                k += 2;
            } else {
                k++;
            }
        }
        if (k >= a.length) return true; // 仅连接选项：无可执行命令
        String cmd = a[k++].toLowerCase();
        if (!REDIS_READ_CMDS.contains(cmd)) return false;
        return switch (cmd) {
            case "config" -> redisSub(a, k, Set.of("get"));
            case "slowlog" -> redisSub(a, k, Set.of("get", "len"));
            case "client" -> redisSub(a, k, Set.of("list", "getname", "info", "id"));
            case "memory" -> redisSub(a, k, Set.of("usage", "stats", "doctor", "malloc-stats"));
            case "cluster" -> redisSub(a, k, Set.of("info", "nodes", "slots", "shards",
                    "count-failure-reports", "countkeysinslot", "getkeysinslot",
                    "keyslot", "myid", "links", "help"));
            case "acl" -> redisSub(a, k, Set.of("list", "getuser", "cat", "whoami", "users"));
            case "latency" -> redisSub(a, k, Set.of("latest", "history", "graph", "doctor"));
            case "xinfo" -> redisSub(a, k, Set.of("stream", "groups", "consumers", "help"));
            case "function" -> redisSub(a, k, Set.of("list", "dump", "stats"));
            case "sentinel" -> redisSub(a, k, Set.of("get-master-addr-by-name", "master",
                    "slaves", "replicas", "sentinels", "ckquorum"));
            case "object" -> k < a.length; // ENCODING/FREQ/REFCOUNT/HELP key
            case "pubsub" -> redisSub(a, k, Set.of("channels", "numsub", "numpat",
                    "shardchannels", "shardnumsub"));
            default -> true;
        };
    }

    private static boolean redisSub(String[] a, int k, Set<String> allowed) {
        int j = k;
        while (j < a.length && a[j].startsWith("-")) j++;
        return j < a.length && allowed.contains(a[j].toLowerCase());
    }

    // ── Web 服务自检 ─────────────────────────────────────────────────────────────────

    /** nginx：必须含 -t/-T（配置测试）；-s reload/stop 拒绝。 */
    private static boolean nginx(String[] a) {
        boolean test = false;
        for (String x : a) {
            if (x.equals("-t") || x.equals("-T")) { test = true; continue; }
            if (x.equals("-s")) return false;
            if (x.startsWith("-")) continue; // -c/-p/-g/--prefix 等仅配合 -t 使用
        }
        return test;
    }

    /** apache2ctl/httpd：-t/-M/-V/-S/configtest/status 只读。 */
    private static boolean apacheCtl(String[] a) {
        if (a.length == 0) return false;
        String sub = a[0];
        return sub.equals("-t") || sub.equals("-M") || sub.equals("-V") || sub.equals("-S")
                || sub.equals("configtest") || sub.equals("fullstatus") || sub.equals("status");
    }

    /** busybox APPLET ...：递归用同一套白名单证明 applet 只读；--install 拒绝。 */
    private static boolean busybox(String[] a) {
        int k = 0;
        if (k < a.length && (a[k].equals("--list") || a[k].equals("--list-full")
                || a[k].equals("--list-short") || a[k].equals("--show-links"))) {
            return true;
        }
        if (k < a.length && (a[k].startsWith("--install") || a[k].equals("-i"))) return false;
        if (k < a.length && a[k].equals("--")) k++;
        if (k >= a.length) return true;
        String[] rest = new String[a.length - k];
        System.arraycopy(a, k, rest, 0, rest.length);
        return validateArgv(rest);
    }

    // ==================================================================================
    // 注册表 & 主入口
    // ==================================================================================

    /** 需参数级校验的程序；未在此表也不在只读集合中 → 无法证明只读。 */
    private static final Map<String, Predicate<String[]>> VALIDATORS = Map.ofEntries(
            Map.entry("env", ReadOnlyCommandClassifier::env),
            Map.entry("command", ReadOnlyCommandClassifier::commandBuiltin),
            Map.entry("type", ReadOnlyCommandClassifier::typeBuiltin),
            Map.entry("date", ReadOnlyCommandClassifier::date),
            Map.entry("hostname", ReadOnlyCommandClassifier::hostname),
            Map.entry("sort", ReadOnlyCommandClassifier::sort),
            Map.entry("find", ReadOnlyCommandClassifier::find),
            Map.entry("tree", ReadOnlyCommandClassifier::tree),
            Map.entry("dmesg", ReadOnlyCommandClassifier::dmesg),
            Map.entry("journalctl", ReadOnlyCommandClassifier::journalctl),
            Map.entry("crontab", ReadOnlyCommandClassifier::crontab),
            Map.entry("mount", ReadOnlyCommandClassifier::mount),
            Map.entry("sysctl", ReadOnlyCommandClassifier::sysctl),
            Map.entry("swapon", ReadOnlyCommandClassifier::swapon),
            Map.entry("systemctl", ReadOnlyCommandClassifier::systemctl),
            Map.entry("service", ReadOnlyCommandClassifier::service),
            Map.entry("supervisorctl", ReadOnlyCommandClassifier::supervisorctl),
            Map.entry("timedatectl", ReadOnlyCommandClassifier::statusCtl),
            Map.entry("hostnamectl", ReadOnlyCommandClassifier::statusCtl),
            Map.entry("localectl", ReadOnlyCommandClassifier::statusCtl),
            Map.entry("loginctl", ReadOnlyCommandClassifier::statusCtl),
            Map.entry("ufw", ReadOnlyCommandClassifier::ufw),
            Map.entry("ip", ReadOnlyCommandClassifier::ip),
            Map.entry("ifconfig", ReadOnlyCommandClassifier::ifconfig),
            Map.entry("iptables", ReadOnlyCommandClassifier::iptables),
            Map.entry("ip6tables", ReadOnlyCommandClassifier::iptables),
            Map.entry("nft", ReadOnlyCommandClassifier::nft),
            Map.entry("ipset", ReadOnlyCommandClassifier::ipset),
            Map.entry("tc", ReadOnlyCommandClassifier::tc),
            Map.entry("tcpdump", ReadOnlyCommandClassifier::tcpdump),
            Map.entry("fdisk", ReadOnlyCommandClassifier::fdiskList),
            Map.entry("sfdisk", ReadOnlyCommandClassifier::fdiskList),
            Map.entry("parted", ReadOnlyCommandClassifier::parted),
            Map.entry("tune2fs", ReadOnlyCommandClassifier::tune2fs),
            Map.entry("smartctl", ReadOnlyCommandClassifier::smartctl),
            Map.entry("ethtool", ReadOnlyCommandClassifier::ethtool),
            Map.entry("cryptsetup", ReadOnlyCommandClassifier::cryptsetup),
            Map.entry("tar", ReadOnlyCommandClassifier::tar),
            Map.entry("gzip", ReadOnlyCommandClassifier::testOnly),
            Map.entry("gunzip", ReadOnlyCommandClassifier::testOnly),
            Map.entry("bzip2", ReadOnlyCommandClassifier::testOnly),
            Map.entry("bunzip2", ReadOnlyCommandClassifier::testOnly),
            Map.entry("xz", ReadOnlyCommandClassifier::testOnly),
            Map.entry("unxz", ReadOnlyCommandClassifier::testOnly),
            Map.entry("zstd", ReadOnlyCommandClassifier::testOnly),
            Map.entry("unzstd", ReadOnlyCommandClassifier::testOnly),
            Map.entry("unzip", ReadOnlyCommandClassifier::unzip),
            Map.entry("7z", ReadOnlyCommandClassifier::sevenz),
            Map.entry("7za", ReadOnlyCommandClassifier::sevenz),
            Map.entry("7zr", ReadOnlyCommandClassifier::sevenz),
            Map.entry("7zz", ReadOnlyCommandClassifier::sevenz),
            Map.entry("dpkg", ReadOnlyCommandClassifier::dpkg),
            Map.entry("apt", ReadOnlyCommandClassifier::apt),
            Map.entry("apt-get", ReadOnlyCommandClassifier::apt),
            Map.entry("apt-cache", ReadOnlyCommandClassifier::aptCache),
            Map.entry("dnf", ReadOnlyCommandClassifier::dnf),
            Map.entry("yum", ReadOnlyCommandClassifier::dnf),
            Map.entry("rpm", ReadOnlyCommandClassifier::rpm),
            Map.entry("pip", ReadOnlyCommandClassifier::pip),
            Map.entry("pip3", ReadOnlyCommandClassifier::pip),
            Map.entry("npm", ReadOnlyCommandClassifier::npm),
            Map.entry("git", ReadOnlyCommandClassifier::git),
            Map.entry("docker", ReadOnlyCommandClassifier::docker),
            Map.entry("nerdctl", ReadOnlyCommandClassifier::nerdctl),
            Map.entry("crictl", ReadOnlyCommandClassifier::crictl),
            Map.entry("kubectl", ReadOnlyCommandClassifier::kubectl),
            Map.entry("helm", ReadOnlyCommandClassifier::helm),
            Map.entry("pm2", ReadOnlyCommandClassifier::pm2),
            Map.entry("redis-cli", ReadOnlyCommandClassifier::redisCli),
            Map.entry("nginx", ReadOnlyCommandClassifier::nginx),
            Map.entry("apache2ctl", ReadOnlyCommandClassifier::apacheCtl),
            Map.entry("httpd", ReadOnlyCommandClassifier::apacheCtl),
            Map.entry("busybox", ReadOnlyCommandClassifier::busybox));

    /**
     * 判断命令是否可证明为只读。
     *
     * @return true=可免确认自动放行；false=无法证明（回落到人工确认，<b>不是拒绝</b>）
     */
    public static boolean isProvablyReadOnly(String command) {
        if (command == null || command.isBlank()) return false;
        try {
            List<List<Word>> stages = lex(command);
            for (List<Word> stage : stages) {
                if (!validateStage(stage)) return false;
            }
            return true;
        } catch (LexException e) {
            return false;
        }
    }

    private static boolean validateStage(List<Word> words) {
        if (words.isEmpty()) return false;
        // 命令位是环境变量赋值（FOO=bar …）严格拒绝
        if (ASSIGNMENT.matcher(words.get(0).value()).matches()) return false;
        String[] argv = new String[words.size()];
        for (int k = 0; k < words.size(); k++) {
            Word w = words.get(k);
            if (w.dynamic()) return false; // 含未保护的 $ / 反引号展开
            argv[k] = w.value();
        }
        return validateArgv(argv);
    }

    private static boolean validateArgv(String[] argv) {
        if (argv.length == 0) return false;
        String prog = basename(argv[0]);
        if (prog.isEmpty() || RESERVED.contains(prog)) return false;
        // 显式路径只认 basename 白名单（/tmp/evil 伪装 ls：basename 非白名单即 false）
        String[] args = new String[argv.length - 1];
        System.arraycopy(argv, 1, args, 0, args.length);
        if (UNRESTRICTED_READERS.contains(prog)) return true;
        Predicate<String[]> v = VALIDATORS.get(prog);
        return v != null && v.test(args);
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** 取第一个非选项子命令；带值选项通过 valueFlags 连带跳过其值。 */
    private static String firstSubcommand(String[] a, Set<String> valueFlags) {
        int k = skipGlobalFlags(a, 0, valueFlags);
        return k < a.length ? a[k] : null;
    }
}
