package com.aye.server.tools.websearch;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * web_fetch 的 SSRF 守卫：阻止抓取本机回环、私网、链路本地（含云元数据 169.254.169.254）
 * 等非公网目标。
 *
 * <p>校验方式：对 URL 主机做 DNS 解析，只要解析出的<b>任一</b>地址落在封闭网段即拒绝，
 * 因此十进制/八进制/十六进制整数 IP、{@code localhost}、指向内网的域名都无法绕过。
 * 调用方（{@link WebFetchTool}）必须在<b>每一次重定向跳转后</b>重新调用本守卫，
 * 防止外网 302 跳内网。
 *
 * <p>已知残留风险（TOCTOU）：校验用的 DNS 结果与随后 HttpClient 实际连接的地址之间
 * 存在窄窗口，攻击者可控的 DNS 若做秒级 rebinding 理论上可利用；当前威胁模型（LLM 抓取
 * 用户/搜索得到的 URL）下逐跳 DNS 校验已关闭实用攻击面，彻底消除需自定义连接工厂
 * 绑定已校验 IP，后续有需要再增强。
 */
final class SsrfGuard {

    private SsrfGuard() {}

    /**
     * 校验 URI：scheme 仅 http/https；主机解析出的全部地址必须为公网地址。
     *
     * @throws IllegalArgumentException 命中封闭网段或主机不可解析
     */
    static void assertUriAllowed(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URL 无效");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("web_fetch 仅支持 http/https 链接");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("web_fetch 无法解析 URL 主机名");
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("web_fetch 主机无法解析：" + host);
        }
        for (InetAddress addr : addrs) {
            if (isBlockedAddress(addr)) {
                throw new BlockedException(
                        "目标主机解析到内网/保留地址（" + host + " → "
                                + addr.getHostAddress() + "），禁止访问回环、私网、链路本地及云元数据地址");
            }
        }
    }

    /** SSRF 策略拦截：必须作为硬错误（isError=true）返回给模型，不得被普通 URL 兜底吞掉。 */
    static final class BlockedException extends RuntimeException {
        BlockedException(String message) { super("web_fetch 安全拦截：" + message); }
    }

    /**
     * 判断 IP 是否属于禁止抓取的封闭网段。纯函数（基于地址字节），便于单元测试。
     */
    static boolean isBlockedAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress()      // 0.0.0.0 / ::
                || addr.isLoopbackAddress()      // 127.0.0.0/8 / ::1
                || addr.isLinkLocalAddress()     // 169.254.0.0/16（含 169.254.169.254 云元数据）/ fe80::/10
                || addr.isSiteLocalAddress()     // 10/8、172.16/12、192.168/16
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 16) {
            // IPv6 唯一本地地址 fc00::/7（Java 无对应 isXxx 判定，手工检查首字节 fc/fd）
            if ((b[0] & 0xFE) == 0xFC) return true;
            // IPv4-mapped IPv6（::ffff:a.b.c.d）：按内嵌 v4 地址再判一次
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) { mapped = false; break; }
            }
            if (mapped && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) {
                int first = b[12] & 0xFF, second = b[13] & 0xFF;
                if (first == 127 || first == 10 || first == 0
                        || (first == 172 && (second & 0xF0) == 16)
                        || (first == 192 && second == 168)
                        || (first == 169 && second == 254)) {
                    return true;
                }
            }
        }
        return false;
    }
}
