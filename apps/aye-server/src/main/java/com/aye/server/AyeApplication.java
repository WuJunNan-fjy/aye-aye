package com.aye.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Aye 后端入口（Spring Boot 薄壳）。
 *
 * <p>核心 AI 逻辑在 {@code com.aye.ai}（纯 Java 库、零 AI 框架）。
 * 本模块只负责四件事，并把它们桥接到 AI 核心：
 * <ol>
 *   <li>HTTP / SSE 通道（聊天流式响应）</li>
 *   <li>WebSocket 通道（交互式终端 /ws/terminal/:sessionId）</li>
 *   <li>SQLite 存储（会话 / 消息 / 模型配置 / 主机 / 审计 / 设置）</li>
 *   <li>SSH / 服务器操作（作为外部工具注入 ToolRegistry）</li>
 * </ol>
 *
 * <p><b>Electron sidecar 契约</b>（与 {@code aye-desktop/src/main/index.ts} 对齐）：
 * 通过环境变量注入 {@code PORT}（0 = 随机端口）、{@code AYE_API_TOKEN}、
 * {@code AYE_DATA_DIR}；启动完成后输出单行结构化握手：
 * {@code AYE_READY {"port":54321,"pid":12348,"version":"0.0.1"}}。
 * <b>端口必须取 WebServer 实际绑定值</b>——{@code server.port=0} 时配置值仍是 0，
 * 不能读 {@code env.getProperty("server.port")}，否则 Electron 拿到 0 端口全部请求失败。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AyeApplication {

    public static void main(String[] args) {
        // Electron sidecar 注入：PORT / AYE_DATA_DIR → Spring 属性。
        // 注意不走 relaxed binding（SERVER__PORT 会被 WorkBuddy 等环境误映射），这里显式转换。
        String port = System.getenv("PORT");
        if (port != null && !port.isBlank()) {
            System.setProperty("server.port", port.trim());
        }
        String address = System.getenv("SERVER_ADDRESS");
        if (address != null && !address.isBlank()) {
            System.setProperty("server.address", address.trim());
        }
        String dataDir = System.getenv("AYE_DATA_DIR");
        if (dataDir != null && !dataDir.isBlank()) {
            System.setProperty("aye.data-dir", dataDir.trim());
        }

        ConfigurableApplicationContext ctx = SpringApplication.run(AyeApplication.class, args);

        // 结构化握手行：端口取 WebServer 实际绑定值（server.port=0 时不能读配置属性，否则恒为 0）
        int boundPort = 8080;
        if (ctx instanceof WebServerApplicationContext webServerCtx) {
            boundPort = webServerCtx.getWebServer().getPort();
        }
        String readyLine = "AYE_READY "
                + "{\"port\":" + boundPort
                + ",\"pid\":" + ProcessHandle.current().pid()
                + ",\"version\":\"" + readImplementationVersion() + "\"}";
        System.out.println(readyLine);
        System.out.flush();
    }

    /**
     * 读取 fat jar 根 MANIFEST.MF 的 Implementation-Version（repackage 后保留）；
     * IDE/exploded classpath 下清单可能不存在，回退 "dev"。
     */
    private static String readImplementationVersion() {
        Package pkg = AyeApplication.class.getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null) {
            return pkg.getImplementationVersion();
        }
        try (java.io.InputStream is =
                     AyeApplication.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (is != null) {
                String v = new java.util.jar.Manifest(is)
                        .getMainAttributes()
                        .getValue(java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        } catch (Exception ignored) {
            // 开发态读不到清单属正常
        }
        return "dev";
    }
}
