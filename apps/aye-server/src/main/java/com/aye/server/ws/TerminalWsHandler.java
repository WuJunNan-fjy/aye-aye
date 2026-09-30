package com.aye.server.ws;

import com.jcraft.jsch.ChannelShell;
import com.aye.server.ssh.SshSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 交互式终端 WebSocket（翻译自旧 NestJS 的 {@code terminal.gateway.ts}）。
 *
 * <p>路由：{@code /ws/terminal/{sessionId}?token=xxx}，其中 {@code sessionId} 是
 * {@code /api/ssh/connect} 返回的 SSH 会话 ID。前端（SshTerminalPanel / TerminalView）
 * 用浏览器原生 WebSocket 连接，协议：
 *
 * <ul>
 *   <li><b>客户端 → 服务端</b>：文本帧即 xterm.js 的键盘输入（原样写进 shell stdin）；
 *       {@code __ping__} 为心跳，需回 {@code __pong__}。</li>
 *   <li><b>服务端 → 客户端</b>：文本帧即 shell stdout（原样交给 {@code term.write()}）；
 *       致命错误发 {@code [ERROR] <文案>} 再关闭。</li>
 *   <li><b>关闭码</b>：1000 = 会话正常结束（如用户输入 exit）；1011 = 连接异常断开。
 *       前端对二者有不同的 UI 提示与重试策略。</li>
 * </ul>
 */
@Component
public class TerminalWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalWsHandler.class);

    private static final String PING = "__ping__";
    private static final String PONG = "__pong__";

    private final SshSessionManager sessionManager;
    private final Map<String, TerminalConnection> connections = new ConcurrentHashMap<>();
    private final ExecutorService readerPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "aye-terminal-reader");
        t.setDaemon(true);
        return t;
    });

    public TerminalWsHandler(SshSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /** 单条 WS 连接持有的资源：shell 通道 + stdin 流 */
    private static final class TerminalConnection {
        final WebSocketSession ws;
        final ChannelShell channel;
        final OutputStream stdin;

        TerminalConnection(WebSocketSession ws, ChannelShell channel, OutputStream stdin) {
            this.ws = ws;
            this.channel = channel;
            this.stdin = stdin;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) throws Exception {
        String sessionId = extractPathSegment(ws);
        String token = extractQueryParam(ws, "token");

        // token 鉴权：与 Electron 注入的 AYE_API_TOKEN 比对；未配置则不启用
        String expected = System.getenv("AYE_API_TOKEN");
        if (expected != null && !expected.isBlank() && !expected.equals(token)) {
            log.warn("[TerminalWs] token 校验失败，拒绝连接");
            sendTextAndClose(ws, "[ERROR] 鉴权失败，请重新从桌面客户端进入", CloseStatus.POLICY_VIOLATION);
            return;
        }

        if (sessionId == null || sessionId.isBlank()) {
            sendTextAndClose(ws, "[ERROR] 缺少 sessionId", CloseStatus.BAD_DATA);
            return;
        }
        if (!sessionManager.isSessionValid(sessionId)) {
            sendTextAndClose(ws, "[ERROR] SSH 会话不存在或已断开，请重新连接服务器", CloseStatus.BAD_DATA);
            return;
        }

        ChannelShell shell;
        try {
            shell = sessionManager.openShell(sessionId);
        } catch (Exception e) {
            sendTextAndClose(ws, "[ERROR] " + e.getMessage(), CloseStatus.SERVER_ERROR);
            return;
        }

        try {
            InputStream stdout = shell.getInputStream();
            OutputStream stdin = shell.getOutputStream();
            shell.connect(20_000);

            TerminalConnection conn = new TerminalConnection(ws, shell, stdin);
            connections.put(ws.getId(), conn);

            readerPool.submit(() -> pumpStdout(ws.getId(), sessionId, conn, stdout));
            log.info("[TerminalWs] 终端已连接 sessionId={}", sessionId);
        } catch (Exception e) {
            try { shell.disconnect(); } catch (Exception ignored) {}
            sendTextAndClose(ws, "[ERROR] Shell 通道启动失败：" + e.getMessage(), CloseStatus.SERVER_ERROR);
        }
    }

    /** 读 shell stdout → 推给 WS；EOF 视为会话正常结束（1000），读异常视为异常断开（1011） */
    private void pumpStdout(String wsId, String sessionId, TerminalConnection conn, InputStream stdout) {
        try (Reader r = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) {
                sendText(conn.ws, new String(buf, 0, n));
            }
            // EOF：远端 shell 退出（用户输入 exit 或会话被回收）
            closeQuietly(conn.ws, CloseStatus.NORMAL);
        } catch (Exception e) {
            log.debug("[TerminalWs] stdout 读取结束 sessionId={} err={}", sessionId, e.getMessage());
            closeQuietly(conn.ws, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        TerminalConnection conn = connections.get(ws.getId());
        if (conn == null) return;

        String payload = message.getPayload();
        if (PING.equals(payload)) {
            sendText(ws, PONG);
            return;
        }
        // 键盘输入代表会话仍活跃，重置空闲计时（防 30min 空闲回收误杀挂机的终端）
        sessionManager.touch(extractPathSegment(ws));
        try {
            conn.stdin.write(payload.getBytes(StandardCharsets.UTF_8));
            conn.stdin.flush();
        } catch (IOException e) {
            log.debug("[TerminalWs] stdin 写入失败（通道可能已关闭）：{}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        TerminalConnection conn = connections.remove(ws.getId());
        if (conn != null) {
            try { conn.channel.disconnect(); } catch (Exception ignored) {}
            log.info("[TerminalWs] 终端已断开 code={} sessionId 可用={}", status.getCode(),
                    connections.size());
        }
    }

    // ---------- 工具方法 ----------

    /** 线程安全发送文本帧（Spring WS 不允许同一 session 并发写） */
    private void sendText(WebSocketSession ws, String text) {
        if (ws == null || !ws.isOpen()) return;
        synchronized (ws) {
            try {
                ws.sendMessage(new TextMessage(text));
            } catch (IOException e) {
                log.debug("[TerminalWs] 发送失败（连接可能已断开）：{}", e.getMessage());
            }
        }
    }

    private void sendTextAndClose(WebSocketSession ws, String text, CloseStatus status) {
        sendText(ws, text);
        closeQuietly(ws, status);
    }

    private void closeQuietly(WebSocketSession ws, CloseStatus status) {
        if (ws == null) return;
        try {
            ws.close(status);
        } catch (Exception ignored) {}
    }

    /** 从 URI 路径提取 sessionId：/ws/terminal/{sessionId} */
    private String extractPathSegment(WebSocketSession ws) {
        URI uri = ws.getUri();
        if (uri == null) return null;
        String path = uri.getPath();
        if (path == null) return null;
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : null;
    }

    private String extractQueryParam(WebSocketSession ws, String name) {
        URI uri = ws.getUri();
        if (uri == null || uri.getQuery() == null) return null;
        for (String pair : uri.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}
