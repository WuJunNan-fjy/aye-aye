package com.aye.ai.llm;

/** LLM 调用异常（HTTP 错误 / 网络错误 / 用户取消 / 流解析中断）。 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
