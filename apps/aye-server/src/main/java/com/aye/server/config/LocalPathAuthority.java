package com.aye.server.config;

import com.aye.server.web.ApiException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 本机路径访问凭证校验。
 *
 * <p>桌面端渲染进程只能把「用户经原生对话框选择或拖拽（{@code webUtils.getPathForFile}）
 * 确认过」的本机路径交由主进程做 HMAC 签名（IPC {@code grant-local-path}），
 * 请求只携带路径 + 签名；后端用共享的 {@code AYE_API_TOKEN} 验签，
 * 防止任意网页/本机进程借附件或工作区接口让 AI 读取未授权路径。
 *
 * <p>浏览器模式（未配置 token）不启用；服务端自己落盘的 {@code uploads} 目录天然可信，
 * 无需凭证（multipart 上传通道）。
 */
@Component
public class LocalPathAuthority {

    /** 签名版本前缀，算法/格式变更时可区分。 */
    public static final String TOKEN_PREFIX = "hmac1.";

    private final AppProperties props;

    public LocalPathAuthority(AppProperties props) {
        this.props = props;
    }

    /** 是否启用路径来源证明（桌面端配置了 token 时启用）。 */
    public boolean proofRequired() {
        String secret = System.getenv("AYE_API_TOKEN");
        return secret != null && !secret.isBlank();
    }

    /**
     * 校验本机路径的来源凭证，不通过抛 403。
     *
     * @param path      请求声明的绝对路径（签名负载必须与该字符串逐字节一致）
     * @param pathToken 渲染端持有的签名；服务端自有路径可省略
     */
    public void requirePathProof(String path, String pathToken) {
        if (path == null || path.isBlank() || !proofRequired()) return;
        if (isServerOwned(path)) return;
        if (pathToken == null || !verify(path, pathToken)) {
            throw new ApiException(403, "本机路径缺少有效的来源凭证（请通过文件选择/拖拽添加）：" + path,
                    "LOCAL_PATH_UNPROVEN");
        }
    }

    /** 服务端自身管理的目录（multipart 落盘 uploads）无需来源证明。 */
    public boolean isServerOwned(String path) {
        try {
            Path uploads = Path.of(props.getDataDir(), "uploads").toAbsolutePath().normalize();
            Path p = Path.of(path).toAbsolutePath().normalize();
            return p.startsWith(uploads);
        } catch (Exception e) {
            return false;
        }
    }

    /** HMAC-SHA256(secret, path) 常量时间校验。 */
    private boolean verify(String path, String token) {
        if (!token.startsWith(TOKEN_PREFIX)) return false;
        String expected = sign(path);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /** 按当前共享密钥计算路径签名（主进程算法必须与此一致）。 */
    public String sign(String path) {
        try {
            String secret = System.getenv("AYE_API_TOKEN");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return TOKEN_PREFIX + HexFormat.of().formatHex(mac.doFinal(
                    path.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 不可用", e);
        }
    }
}
