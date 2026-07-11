package com.repolens.service;

import lombok.Getter;

/**
 * Embedding Provider 调用异常。
 * 之所以单独拆出这个异常，而不是复用普通业务异常，是为了让向量化阶段能区分：
 * 1. provider 配置缺失；
 * 2. HTTP 调用失败；
 * 3. 超时；
 * 4. 响应解析失败；
 * 5. 向量维度不匹配。
 *
 * 这样 ChunkVectorizeService 才能在批次级别把 chunk 正确标记为 FAILED，
 * 而不是误标为 EMBEDDED。
 */
@Getter
public class EmbeddingClientException extends RuntimeException {

    private final String errorCode;

    public EmbeddingClientException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public EmbeddingClientException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
