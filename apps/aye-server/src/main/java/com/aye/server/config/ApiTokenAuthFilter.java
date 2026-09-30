package com.aye.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * REST 接口令牌鉴权。
 *
 * <p>桌面端启动时生成随机 {@code AYE_API_TOKEN} 注入后端（见 Electron 主进程），
 * 渲染端经 IPC 取得后随每个请求带回。未配置 token（浏览器模式/本地开发）时不启用，
 * 保持向后兼容。
 *
 * <p>令牌可通过三种方式传递：{@code X-API-Token} 头、{@code Authorization: Bearer} 头、
 * 或 {@code api_token} 查询参数（仅供无法加头的下载直链使用）。CORS 预检请求直接放行。
 */
@Configuration
public class ApiTokenAuthFilter {

    @Bean
    FilterRegistrationBean<TokenFilter> apiTokenAuthFilterRegistration() {
        FilterRegistrationBean<TokenFilter> reg = new FilterRegistrationBean<>(new TokenFilter());
        reg.addUrlPatterns("/api/*");
        reg.setOrder(1);
        reg.setName("apiTokenAuthFilter");
        return reg;
    }

    static final class TokenFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String expected = System.getenv("AYE_API_TOKEN");
            // 存活探针免 token：服务仅绑 127.0.0.1，健康信息不含敏感数据
            boolean isHealthProbe = "GET".equalsIgnoreCase(request.getMethod())
                    && "/api/health".equals(request.getRequestURI());
            if (expected == null || expected.isBlank()
                    || "OPTIONS".equalsIgnoreCase(request.getMethod()) || isHealthProbe) {
                chain.doFilter(request, response);
                return;
            }
            String presented = request.getHeader("X-API-Token");
            if (presented == null || presented.isBlank()) {
                String auth = request.getHeader("Authorization");
                if (auth != null && auth.startsWith("Bearer ")) presented = auth.substring(7).trim();
            }
            if (presented == null || presented.isBlank()) {
                presented = request.getParameter("api_token");
            }
            if (constantTimeEquals(expected, presented)) {
                chain.doFilter(request, response);
                return;
            }
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"statusCode\":401,\"code\":\"UNAUTHORIZED\","
                    + "\"message\":\"缺少或无效的 API 令牌\"}");
        }

        /** 常量时间比较，避免计时侧信道。 */
        private static boolean constantTimeEquals(String expected, String presented) {
            if (presented == null) return false;
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    presented.getBytes(StandardCharsets.UTF_8));
        }
    }
}
