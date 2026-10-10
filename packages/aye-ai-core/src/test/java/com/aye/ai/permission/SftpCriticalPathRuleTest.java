package com.aye.ai.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aye.ai.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sftp_remove 关键路径规则的正反用例。
 *
 * <p>安全语义：DENY 档与信任级别无关（TRUSTED/yolo 也必须拦）；CONFIRM 档在任何信任级别
 * 都强制人工确认；普通路径不受影响。绕过形态（重复斜杠、.、..、尾斜杠）必须经规范化后
 * 仍被正确分级。
 */
class SftpCriticalPathRuleTest {

    @Nested
    @DisplayName("POSIX 路径词法规范化")
    class Normalize {

        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @CsvSource({
                "/etc,                     /etc",
                "//etc//,                  /etc",
                "/etc/nginx/,              /etc/nginx",
                "/etc/./nginx,             /etc/nginx",
                "/opt/../etc,              /etc",
                "/opt/../../etc,           /etc",
                "/,                        /",
                "///,                      /",
                "/home/deploy/./../deploy, /home/deploy"
        })
        void normalizes(String raw, String expected) {
            assertEquals(expected, PermissionEngine.normalizePosixPath(raw));
        }

        @Test
        void nonAbsoluteOrBlankReturnsNull() {
            assertNull(PermissionEngine.normalizePosixPath("etc/nginx"));
            assertNull(PermissionEngine.normalizePosixPath(""));
            assertNull(PermissionEngine.normalizePosixPath("   "));
            assertNull(PermissionEngine.normalizePosixPath(null));
        }
    }

    @Nested
    @DisplayName("硬 Deny：系统关键路径（含绕过形态）")
    class DenyPaths {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "/", "//", "///",
                "/etc", "//etc//", "/etc/.", "/opt/../etc",
                "/usr", "/usr/bin", "/usr/lib/systemd",
                "/boot", "/boot/grub2/grub.cfg",
                "/bin", "/sbin", "/lib", "/lib64",
                "/proc", "/proc/1/environ",
                "/sys", "/sys/class",
                "/dev", "/dev/sda"
        })
        void deny(String path) {
            assertEquals(PermissionEngine.CriticalPathLevel.DENY,
                    PermissionEngine.classifyRemoteDeletion(path));
        }
    }

    @Nested
    @DisplayName("强制确认：高风险数据/应用路径")
    class ConfirmPaths {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "/etc/nginx", "/etc/ssh/sshd_config",
                "/home", "/home/deploy/.bashrc",
                "/root", "/root/.ssh/authorized_keys",
                "/var", "/var/lib/mysql",
                "/opt", "/opt/app",
                "/srv", "/srv/www",
                // /usr/local 必须优先于 /usr 整树 Deny
                "/usr/local", "/usr/local/bin/foo"
        })
        void confirm(String path) {
            assertEquals(PermissionEngine.CriticalPathLevel.CONFIRM,
                    PermissionEngine.classifyRemoteDeletion(path));
        }
    }

    @Nested
    @DisplayName("普通路径与相对路径不受影响")
    class NonePaths {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "/tmp", "/tmp/app.log",
                "/data/backup/2026",
                "/app/logs/nginx/access.log",
                "/tmp/../data/x"
        })
        void none(String path) {
            assertEquals(PermissionEngine.CriticalPathLevel.NONE,
                    PermissionEngine.classifyRemoteDeletion(path));
        }

        @Test
        void relativePathCannotBeClassified() {
            assertEquals(PermissionEngine.CriticalPathLevel.NONE,
                    PermissionEngine.classifyRemoteDeletion("etc/passwd"));
        }
    }

    @Nested
    @DisplayName("引擎规则端到端决策")
    class EngineRule {

        private PermissionDecision decideRemove(String path, TrustLevel trust, String executionMode) {
            ObjectNode input = Json.object();
            input.put("path", path);
            PermissionContext ctx = new PermissionContext(
                    "sftp_remove", input, trust,
                    List.of(), List.of(DestructiveTag.RECURSIVE_FORCE_DELETE),
                    "rm -rf '" + path + "'",
                    false, "/workspace", executionMode, ToolCategory.WRITE);
            return PermissionEngine.evaluatePermission(ctx);
        }

        @Test
        @DisplayName("TRUSTED(yolo) 下 /etc 仍被硬拒，且文案含规范化路径")
        void trustedCannotBypassDeny() {
            PermissionDecision d = decideRemove("//etc//", TrustLevel.TRUSTED, null);
            PermissionDecision.Deny deny = assertInstanceOf(PermissionDecision.Deny.class, d);
            assertTrue(deny.reason().contains("/etc"), deny.reason());
        }

        @Test
        @DisplayName("TRUSTED 下系统目录子树仍被硬拒")
        void trustedDenySubtree() {
            assertInstanceOf(PermissionDecision.Deny.class,
                    decideRemove("/usr/bin", TrustLevel.TRUSTED, null));
        }

        @Test
        @DisplayName("TRUSTED 下高风险数据目录仍强制确认")
        void trustedStillConfirmsDataDirs() {
            PermissionDecision d = decideRemove("/var/lib/mysql", TrustLevel.TRUSTED, null);
            PermissionDecision.Confirm confirm = assertInstanceOf(PermissionDecision.Confirm.class, d);
            assertTrue(confirm.tags().contains(DestructiveTag.RECURSIVE_FORCE_DELETE));
            assertTrue(confirm.message().contains("/var/lib/mysql"));
        }

        @Test
        @DisplayName("/usr/local 走确认而非硬拒（/usr 前缀例外）")
        void usrLocalIsConfirmNotDeny() {
            assertInstanceOf(PermissionDecision.Confirm.class,
                    decideRemove("/usr/local", TrustLevel.TRUSTED, null));
        }

        @Test
        @DisplayName("/etc 子路径在写确认模式下强制确认")
        void etcSubpathConfirmUnderStandard() {
            assertInstanceOf(PermissionDecision.Confirm.class,
                    decideRemove("/etc/nginx", TrustLevel.STANDARD, "write_confirm"));
        }

        @Test
        @DisplayName("普通路径在 TRUSTED 下放行，规则不误伤")
        void ordinaryPathAllowedUnderTrusted() {
            assertInstanceOf(PermissionDecision.Allow.class,
                    decideRemove("/tmp/app", TrustLevel.TRUSTED, null));
        }

        @Test
        @DisplayName("只读模式下普通路径仍由 readonly 规则拒绝")
        void readonlyStillDeniesWrite() {
            assertInstanceOf(PermissionDecision.Deny.class,
                    decideRemove("/tmp/app", TrustLevel.STANDARD, "readonly"));
        }

        @Test
        @DisplayName("规则只作用于 sftp_remove：sftp_write 写 /etc 不被本规则拦截")
        void ruleScopedToSftpRemove() {
            ObjectNode input = Json.object();
            input.put("path", "/etc/nginx/nginx.conf");
            PermissionContext ctx = new PermissionContext(
                    "sftp_write", input, TrustLevel.TRUSTED,
                    List.of(), List.of(), "",
                    false, "/workspace", null, ToolCategory.WRITE);
            // sftp_write 的关键路径防护是另一个通道的议题，本规则不得越权拦截
            assertInstanceOf(PermissionDecision.Allow.class,
                    PermissionEngine.evaluatePermission(ctx));
        }
    }

    @Nested
    @DisplayName("shell 通道 run_ssh：rm 目标与 SFTP 同一分级")
    class ShellRmChannel {

        private PermissionContext shell(String cmd) {
            return shell(cmd, TrustLevel.TRUSTED);
        }

        private PermissionContext shell(String cmd, TrustLevel trust) {
            ObjectNode input = Json.object();
            input.put("command", cmd);
            return new PermissionContext(
                    "run_ssh", input, trust,
                    List.of(), List.of(DestructiveTag.RECURSIVE_FORCE_DELETE),
                    cmd, false, "/workspace", null, ToolCategory.EXECUTE);
        }

        @Test
        @DisplayName("rm -rf /etc 在 TRUSTED 下仍硬拒")
        void denyEtcUnderTrusted() {
            assertInstanceOf(PermissionDecision.Deny.class,
                    PermissionEngine.evaluatePermission(shell("rm -rf /etc")));
        }

        @Test
        @DisplayName("sudo 前缀与引号/双斜杠变体不绕过")
        void variantsDeny() {
            assertInstanceOf(PermissionDecision.Deny.class,
                    PermissionEngine.evaluatePermission(shell("sudo rm -rf //etc//")));
            assertInstanceOf(PermissionDecision.Deny.class,
                    PermissionEngine.evaluatePermission(shell("rm -rf \"/usr/bin\"")));
        }

        @Test
        @DisplayName("非递归 rm 系统目录树目标也硬拒")
        void denyWithoutRecursiveFlag() {
            assertInstanceOf(PermissionDecision.Deny.class,
                    PermissionEngine.evaluatePermission(shell("rm /usr/bin/foo")));
        }

        @Test
        @DisplayName("/etc 子路径与数据目录强制确认")
        void confirmCritical() {
            assertInstanceOf(PermissionDecision.Confirm.class,
                    PermissionEngine.evaluatePermission(shell("rm -rf /etc/nginx")));
            assertInstanceOf(PermissionDecision.Confirm.class,
                    PermissionEngine.evaluatePermission(shell("rm -rf /var/lib/mysql")));
            assertInstanceOf(PermissionDecision.Confirm.class,
                    PermissionEngine.evaluatePermission(shell("rm /home/deploy/a.log")));
        }

        @Test
        @DisplayName("非递归确认使用单文件删除标签")
        void confirmTagForSingleFile() {
            PermissionDecision.Confirm c = assertInstanceOf(PermissionDecision.Confirm.class,
                    PermissionEngine.evaluatePermission(shell("rm /etc/hosts.bak")));
            assertEquals(List.of(DestructiveTag.SINGLE_FILE_DELETE), c.tags());
        }

        @Test
        @DisplayName("/usr/local 走确认而非硬拒")
        void usrLocalConfirm() {
            assertInstanceOf(PermissionDecision.Confirm.class,
                    PermissionEngine.evaluatePermission(shell("rm -rf /usr/local/app")));
        }

        @Test
        @DisplayName("普通路径 TRUSTED 放行，复合命令只拦危险段")
        void ordinaryAndCompound() {
            assertInstanceOf(PermissionDecision.Allow.class,
                    PermissionEngine.evaluatePermission(shell("rm -rf /tmp/build")));
            // /etc/nginx 是 /etc 子路径 → 确认档（不是硬拒）；复合命令按危险段升级
            assertInstanceOf(PermissionDecision.Confirm.class,
                    PermissionEngine.evaluatePermission(shell(
                            "systemctl stop nginx && rm -rf /etc/nginx")));
        }

        @Test
        @DisplayName("不含 rm 的命令查看 /etc 不受影响（cat /etc/hosts）")
        void nonRmUnaffected() {
            assertInstanceOf(PermissionDecision.Allow.class,
                    PermissionEngine.evaluatePermission(shell("cat /etc/hosts")));
        }

        @Test
        @DisplayName("变量目标是已知静态盲区，回落普通流程（TRUSTED 放行）")
        void variableTargetBlindSpot() {
            assertInstanceOf(PermissionDecision.Allow.class,
                    PermissionEngine.evaluatePermission(shell("rm -rf \"$TARGET\"")));
        }
    }
}
