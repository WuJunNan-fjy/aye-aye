package com.aye.server.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常转 JSON：{@code { statusCode, message, code }}。
 * 前端 axios 拦截器只读 {@code message}，所以 message 必须可读。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(body(e.getStatus(), e.getMessage(), e.getCode()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegal(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(400, e.getMessage(), "BAD_REQUEST"));
    }

    /**
     * Spring 6 对未匹配路由/静态资源抛 NoResourceFoundException；
     * 不应落入 catch-all 被记为 500（纯 API 服务，无 SPA 转发需求）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(body(404, "接口不存在：" + e.getResourcePath(), "NOT_FOUND"));
    }

    /**
     * 标准协议异常必须保留各自 4xx 状态码；否则会落入 catch-all 被误报为 500
     * （例如路径存在但仅支持 DELETE 时对其 GET，应是 405）。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        HttpHeaders headers = new HttpHeaders();
        if (e.getSupportedMethods() != null && e.getSupportedMethods().length > 0) {
            java.util.Set<HttpMethod> allow = new HashSet<>();
            for (String m : e.getSupportedMethods()) allow.add(HttpMethod.valueOf(m));
            headers.setAllow(allow);
        }
        return ResponseEntity.status(405).headers(headers)
                .body(body(405, e.getMessage(), "METHOD_NOT_ALLOWED"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(415).body(body(415, e.getMessage(), "UNSUPPORTED_MEDIA_TYPE"));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        return ResponseEntity.status(406).body(body(406, e.getMessage(), "NOT_ACCEPTABLE"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.status(500).body(body(500, msg, "INTERNAL_ERROR"));
    }

    private static Map<String, Object> body(int status, String message, String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statusCode", status);
        m.put("message", message);
        if (code != null) m.put("code", code);
        return m;
    }
}
