package com.aye.server.controller;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 桌面端 Electron sidecar 专用内部端点。
 *
 * <ul>
 *   <li>{@code GET /api/health}：存活探针，{@link com.aye.server.config.ApiTokenAuthFilter}
 *       显式放行（服务只绑 127.0.0.1，不对外暴露）；</li>
 *   <li>{@code POST /api/internal/shutdown}：优雅关停入口，仍需 X-API-Token。
 *       Windows 上 Node 对 java 子进程发 SIGTERM 等价 TerminateProcess 硬杀，
 *       shutdown hook 不执行（SQLite WAL 不 checkpoint、SSE/上传被硬切），
 *       因此由 Electron 先调本端点触发 Spring 优雅关停，再等待进程自然退出。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class InternalController {

    private static final Logger log = LoggerFactory.getLogger(InternalController.class);

    private final ConfigurableApplicationContext context;
    /** 防重复关停：端点触发与 JVM shutdown hook 可能同时进来 */
    private volatile boolean shuttingDown = false;

    public InternalController(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping("/internal/shutdown")
    public Map<String, String> shutdown() {
        log.info("收到内部 shutdown 请求，开始优雅关停（graceful, 最多 10s）");
        beginShutdown();
        // 先给 Electron 回响应，再在独立线程关闭容器（关闭会断掉当前连接）
        return Map.of("status", "SHUTTING_DOWN");
    }

    private void beginShutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        Thread t = new Thread(() -> {
            try {
                // 请求响应刷新给对端后再关，给 200ms 让 HTTP 响应发出
                Thread.sleep(200);
                long start = System.currentTimeMillis();
                context.close();
                log.info("Spring 容器已关闭，耗时 {} ms", System.currentTimeMillis() - start);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("优雅关停异常，等待 Electron 强杀兜底", e);
            }
        }, "aye-graceful-shutdown");
        t.setDaemon(true);
        t.start();
    }

    /** JVM 收到系统信号时也标记一次，幂等。 */
    @PreDestroy
    void onDestroy() {
        shuttingDown = true;
    }
}
