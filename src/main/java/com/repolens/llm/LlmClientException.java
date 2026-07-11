package com.repolens.llm;

import lombok.Getter;

/**
 * LLM 调用专用异常。
 * 与通用业务异常拆开，是为了让 CodeAnswerService 在降级时拿到更细粒度的错误码，
 * 例如配置缺失、HTTP 错误、超时、响应解析失败等。
 */
@Getter
public class LlmClientException extends RuntimeException {

    private final String errorCode;

    public LlmClientException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public LlmClientException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
