package com.aye.server.web;

/**
 * 业务异常：携带 HTTP 状态码与业务 code，由 {@link GlobalExceptionHandler} 统一转成
 * {@code { statusCode, message, code }} 的 JSON 响应（对齐旧 NestJS 的 HttpException 行为，
 * 前端 axios 拦截器读取 {@code message}）。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(int status, String message) {
        this(status, message, null);
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
