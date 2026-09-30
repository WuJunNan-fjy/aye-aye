package com.aye.server.tools.websearch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF 守卫的网段判定与 URI 校验。只用字面量 IP（不触发真实 DNS），
 * 域名解析的公网路径不在单测覆盖内（需网络环境）。
 */
class SsrfGuardTest {

    @ParameterizedTest(name = "[{index}] 拦截 {0}")
    @ValueSource(strings = {
            "127.0.0.1", "127.1.2.3",        // 回环
            "0.0.0.0",                       // 任意本地
            "10.0.0.1", "10.255.255.254",    // 私网 10/8
            "172.16.0.1", "172.31.255.255",  // 私网 172.16/12
            "192.168.1.1",                   // 私网 192.168/16
            "169.254.169.254",               // 链路本地/云元数据
            "224.0.0.1", "239.255.255.255",  // 组播
            "::1",                           // IPv6 回环
            "fc00::1", "fd12:3456::1",       // IPv6 唯一本地 fc00::/7
            "fe80::1",                       // IPv6 链路本地
            "::ffff:127.0.0.1",              // IPv4-mapped 内嵌回环
            "::ffff:169.254.169.254",        // IPv4-mapped 内嵌链路本地
            "::ffff:10.0.0.1"                // IPv4-mapped 内嵌私网
    })
    void blocked(String ip) throws Exception {
        assertTrue(SsrfGuard.isBlockedAddress(InetAddress.getByName(ip)), ip);
    }

    @ParameterizedTest(name = "[{index}] 放行 {0}")
    @ValueSource(strings = {
            "8.8.8.8", "1.1.1.1",
            "172.32.0.1",                    // 172.16/12 边界外
            "172.15.255.255",
            "192.169.0.1",                   // 192.168/16 边界外
            "11.0.0.1",
            "2606:4700:4700::1111",          // 公网 IPv6（Cloudflare DNS）
            "2001:4860:4860::8888",
            "::ffff:8.8.8.8"                 // IPv4-mapped 内嵌公网
    })
    void allowed(String ip) throws Exception {
        assertFalse(SsrfGuard.isBlockedAddress(InetAddress.getByName(ip)), ip);
    }

    @ParameterizedTest(name = "[{index}] URI 拦截：{0}")
    @ValueSource(strings = {
            "http://127.0.0.1:8080/admin",
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]/",
            "http://10.0.0.5/internal",
            "file:///etc/passwd",
            "ftp://8.8.8.8/x",
            "http://2130706433/"             // 整数形态 IP：解析失败也必须 fail-closed
    })
    void blockedUris(String raw) {
        assertThrows(RuntimeException.class,
                () -> SsrfGuard.assertUriAllowed(URI.create(raw)), raw);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("公网字面量 IP URI 可通过")
    void publicIpUriPasses() {
        SsrfGuard.assertUriAllowed(URI.create("https://8.8.8.8/dns-query"));
    }
}
