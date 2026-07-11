package com.repolens.service.impl;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.service.EmbeddingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认的 Mock Embedding 实现。
 * 设计目标不是提供真实语义能力，而是保证：
 * 1. 本地没有外部 API Key 时，RepoLens 仍能完整跑通索引链路；
 * 2. 同一段文本每次都生成稳定一致的伪随机向量；
 * 3. 向量维度始终跟随配置，便于和 Milvus collection dimension 对齐。
 */
@Service
public class MockEmbeddingService implements EmbeddingService {

    @Value("${repolens.embedding.dimension:384}")
    private int dimension;

    @Override
    public float[] embed(String text) {
        if (dimension <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Invalid embedding dimension config");
        }
        byte[] digest = sha256(text == null ? "" : text);
        long state = bytesToLong(digest);
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            state = mix64(state + 0x9E3779B97F4A7C15L + i);
            int bucket = (int) (state & 0x00FFFFFFL);
            vector[i] = (bucket / 8388607.5f) - 1.0f;
        }
        return vector;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }

    private byte[] sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "SHA-256 unavailable");
        }
    }

    private long bytesToLong(byte[] bytes) {
        long value = 0L;
        int len = Math.min(8, bytes.length);
        for (int i = 0; i < len; i++) {
            value = (value << 8) | (bytes[i] & 0xFFL);
        }
        return value;
    }

    private long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
