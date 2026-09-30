package com.aye.server.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 路由注册：交互式终端走 {@code /ws/terminal/{sessionId}}。
 *
 * <p>注意路径不带 {@code /api} 前缀，与前端
 * {@code ws://127.0.0.1:{port}/ws/terminal/{sessionId}?token=xxx} 对齐。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalWsHandler terminalWsHandler;

    public WebSocketConfig(TerminalWsHandler terminalWsHandler) {
        this.terminalWsHandler = terminalWsHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalWsHandler, "/ws/terminal/*")
                .setAllowedOriginPatterns("*");
    }
}
