package com.aye.server.deploy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本机命令风险分级：ALLOW（自动执行）/ CONFIRM（弹窗）/ DENY（硬拒绝）。
 *
 * <p>归一化：trim → 按 {@code ; && || | & 换行} 分段（每段独立分级，任一段 DENY 即 DENY）
 * → 去 {@code cmd /c}、{@code sh -c} 包裹 → 小写匹配。
 *
 * <p>fail-closed：①白名单未命中的一切命令返回 CONFIRM；②任何含 shell 元字符
 * （命令替换、重定向、子 shell 等）的段一律不自动放行；③白名单只做整段匹配，
 * 杜绝「安全命令前缀 + 危险后缀」绕过。
 */
public final class LocalCommandClassifier {

    public enum Level { ALLOW, CONFIRM, DENY }

    /** 多命令分段（&amp;&amp; 与 || 必须排在单 &amp; 与 | 之前）。单 &amp; 在 Windows cmd 是顺序
     *  连接符、在 sh 是后台符，两侧命令都会执行，必须分段。 */
    private static final Pattern SEGMENT_SPLIT = Pattern.compile("\\|\\||&&|&|\\||;|\r?\n");

    /** 管道给解释器（curl|sh 类）。 */
    private static final Pattern PIPE_TO_INTERPRETER = Pattern.compile(
            "\\|\\s*(?:sudo\\s+)?(?:sh|bash|zsh|cmd(?:\\.exe)?|powershell(?:\\.exe)?|pwsh|"
                    + "python\\d?|perl|ruby|node)\\b");

    private static final Pattern WRAPPER = Pattern.compile(
            "^(?:cmd(?:\\.exe)?\\s+/c\\s+|/bin/(?:ba)?sh\\s+-c\\s+)+", Pattern.CASE_INSENSITIVE);

    /** 任意程序的版本查询：name [-v/-version/--version/version]。 */
    private static final Pattern VERSION_CMD = Pattern.compile(
            "^[a-z0-9._-]+(?:\\.cmd)?\\s+(?:-{1,2}v(?:ersion)?|version)$");

    /** 无读写敏感面的基础命令（不含 cat/type：可读任意文件绕开附件白名单）。 */
    private static final Pattern SAFE_BASIC = Pattern.compile(
            "^(?:dir|ls|cd|pwd|echo|mkdir|md|whoami|hostname|where|which)(?:\\s|$)");

    private static final Pattern PKG_MANAGER = Pattern.compile(
            "^(?:npm|pnpm|yarn)(?:\\.cmd)?\\s+(?:install|ci|run|run-script)(?:\\s|$)");

    private static final Pattern MVN = Pattern.compile(
            "^mvn(?:\\.cmd)?(?:\\s+.+)?$");
    private static final Pattern MVN_LIFECYCLE = Pattern.compile(
            "(?<![A-Za-z0-9-])(?:validate|compile|clean|test|package|verify|install)(?![A-Za-z0-9-])");

    private static final Pattern GRADLE = Pattern.compile(
            "^(?:gradlew(?:\\.bat)?|gradle)(?:\\s+.+)?$");
    private static final Pattern GRADLE_TASK = Pattern.compile(
            "(?<![A-Za-z0-9-])(?:clean|classes|jar|war|build|bootJar|check|test|assemble)"
                    + "(?![A-Za-z0-9-])", Pattern.CASE_INSENSITIVE);

    private static final Pattern GIT_SAFE = Pattern.compile(
            "^git\\s+(?:status|log|diff|rev-parse)(?:\\s|$)");
    private static final Pattern GIT_CLONE = Pattern.compile(
            "^git\\s+clone(?:\\s|$)");

    /** shell 元字符：命令替换 $()/``/${}、重定向 <>、子 shell 分组 ()。命中即不自动放行。 */
    private static final Pattern SHELL_META = Pattern.compile("[`$<>()]");

    /** git clone 允许的选项（防止 -c core.sshCommand=、--upload-pack 等选项注入 RCE）。 */
    private static final Set<String> GIT_CLONE_OPTS = Set.of(
            "-b", "--branch", "--depth", "--origin", "-o", "--jobs", "-j",
            "--recurse-submodules", "--recursive", "--shallow-submodules",
            "--single-branch", "--no-single-branch", "-q", "--quiet",
            "--progress", "--filter", "--no-checkout");
    /** 带值的 clone 选项（其后一个 token 是参数而非选项）。 */
    private static final Set<String> GIT_CLONE_VALUE_OPTS = Set.of(
            "-b", "--branch", "--depth", "--origin", "-o", "--jobs", "-j", "--filter");

    /** tar/bsdtar 允许的长选项（--name 或 --name=value 形式）。 */
    private static final Set<String> TAR_LONG_OPTS = Set.of(
            "--create", "--list", "--file", "--gzip", "--bzip2", "--xz", "--verbose",
            "--directory", "--exclude", "--recursion", "--no-recursion", "--dereference",
            "--force-local", "--quiet", "--preserve-permissions", "--same-owner");
    /** tar 允许的短选项字符（显式排除 I=--use-compress-program 等可执行任意命令的选项）。 */
    private static final Pattern TAR_SHORT_OPTS = Pattern.compile("-[ctfzjJvpPrRqCh]{1,}");
    /** zip 允许的短选项字符（显式排除 T/TT=测试命令执行）。 */
    private static final Pattern ZIP_SHORT_OPTS = Pattern.compile("-[rlq0-9xX]{1,}");

    /** DENY：危险系统命令/提权/混淆。 */
    private static final Pattern DENY_PATTERNS = Pattern.compile(
            "\\bformat\\b|"
            + "\\bdiskpart\\b|"
            + "\\breg(?:\\.exe)?\\b|"
            + "\\bshutdown\\b|"
            + "\\bmkfs(?:\\.[a-z0-9]+)?\\b|"
            + "\\bdd\\s+[^|]*\\bof=/dev/|"
            + "\\biptables\\b|"
            + "\\bsudo\\b|"
            + "(?<![A-Za-z])su(?:\\s|$)|"
            + "powershell(?:\\.exe)?\\s+(?:-e(?:nc(?:odedcommand)?)?|-encodedcommand)\\b|"
            + "\\bwmic\\b[^|]*\\bdelete\\b|"
            + "\\b(?:rm|del|erase)\\b[^|]*(?:-rf?\\b|-fr\\b|-r\\b|/s\\b)|"
            + "\\brm\\s+-[a-z]*f|"
            + "(?:del|erase)\\s+[^|]*/s");

    public Level classify(String raw) {
        if (raw == null || raw.isBlank()) return Level.CONFIRM;
        if (PIPE_TO_INTERPRETER.matcher(raw).find()) return Level.DENY;

        boolean sawConfirm = false;
        for (String seg : SEGMENT_SPLIT.split(raw)) {
            Level lvl = classifySegment(seg.trim());
            if (lvl == Level.DENY) return Level.DENY;
            if (lvl == Level.CONFIRM) sawConfirm = true;
        }
        return sawConfirm ? Level.CONFIRM : Level.ALLOW;
    }

    private Level classifySegment(String seg) {
        if (seg.isEmpty()) return Level.CONFIRM;
        Matcher wrap = WRAPPER.matcher(seg);
        if (wrap.find()) seg = seg.substring(wrap.end());
        String s = seg.toLowerCase();

        // DENY 优先；其次任何元字符都不自动放行（防前缀白名单藏匿注入）。
        if (DENY_PATTERNS.matcher(s).find()) return Level.DENY;
        if (SHELL_META.matcher(s).find()) return Level.CONFIRM;

        if (VERSION_CMD.matcher(s).matches()) return Level.ALLOW;
        if (SAFE_BASIC.matcher(s).find()) return Level.ALLOW;
        if (PKG_MANAGER.matcher(s).find()) return Level.ALLOW;

        if (GIT_SAFE.matcher(s).find()) return Level.ALLOW;
        if (GIT_CLONE.matcher(s).find()) return cloneLevel(seg);

        Level archive = archiveLevel(s);
        if (archive != null) return archive;

        if (MVN.matcher(s).matches() && MVN_LIFECYCLE.matcher(s).find()) return Level.ALLOW;
        if (GRADLE.matcher(s).matches() && GRADLE_TASK.matcher(s).find()) return Level.ALLOW;

        return Level.CONFIRM;
    }

    /** git clone：选项白名单 + URL 协议限制；显式目标目录必须落在统一 projects 目录内。 */
    private static Level cloneLevel(String seg) {
        String[] tokens = seg.split("\\s+");
        List<String> positional = new ArrayList<>();
        for (int i = 2; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.startsWith("-")) {
                String name = t.contains("=") ? t.substring(0, t.indexOf('=')) : t;
                if (!GIT_CLONE_OPTS.contains(name)) return Level.CONFIRM;
                if (GIT_CLONE_VALUE_OPTS.contains(name) && !t.contains("=") && i + 1 < tokens.length) i++;
            } else {
                positional.add(t);
            }
        }
        if (positional.isEmpty()) return Level.CONFIRM;
        // ext:: 协议可借外部 helper 执行任意命令，拒绝自动放行。
        if (positional.get(0).toLowerCase().startsWith("ext::")) return Level.CONFIRM;
        if (positional.size() < 2) return Level.ALLOW;
        String target = positional.get(1).replace('\\', '/').toLowerCase();
        return target.contains("/projects/") || target.endsWith("/projects")
                ? Level.ALLOW : Level.CONFIRM;
    }

    /**
     * 压缩包创建/查看形态校验：只允许白名单选项，避免 --to-command、
     * --checkpoint-action=exec、-I 程序、zip -TT 等选项注入执行。
     *
     * @return ALLOW/CONFIRM；非压缩命令返回 null 交后续规则判定
     */
    private static Level archiveLevel(String s) {
        if (s.startsWith("tar ") || s.startsWith("tar.") || s.startsWith("bsdtar ")) {
            String[] tokens = s.split("\\s+");
            boolean createOrList = false;
            for (int i = 1; i < tokens.length; i++) {
                String t = tokens[i];
                if (t.startsWith("--")) {
                    String name = t.contains("=") ? t.substring(0, t.indexOf('=')) : t;
                    if (!TAR_LONG_OPTS.contains(name)) return Level.CONFIRM;
                    if ("--create".equals(name) || "--list".equals(name)) createOrList = true;
                } else if (t.startsWith("-") && t.length() > 1) {
                    if (!TAR_SHORT_OPTS.matcher(t).matches()) return Level.CONFIRM;
                    if (t.indexOf('c') >= 0 || t.indexOf('t') >= 0) createOrList = true;
                    if (t.indexOf('C') >= 0 && i + 1 < tokens.length) i++; // -C 带目录参数
                    if (t.indexOf('f') >= 0 && i + 1 < tokens.length) i++; // -f 带归档文件名
                }
            }
            return createOrList ? Level.ALLOW : Level.CONFIRM;
        }
        if (s.startsWith("zip ") || s.startsWith("zip.")) {
            String[] tokens = s.split("\\s+");
            boolean listOrRecurse = false;
            for (int i = 1; i < tokens.length; i++) {
                String t = tokens[i];
                if (t.startsWith("--")) return Level.CONFIRM; // zip 不使用长选项
                if (t.startsWith("-") && t.length() > 1) {
                    if (!ZIP_SHORT_OPTS.matcher(t).matches()) return Level.CONFIRM;
                    if (t.indexOf('l') >= 0 || t.indexOf('r') >= 0) listOrRecurse = true;
                    if (t.indexOf('x') >= 0 && i + 1 < tokens.length) i++; // -x 带排除模式
                }
            }
            return listOrRecurse ? Level.ALLOW : Level.CONFIRM;
        }
        return null;
    }
}
