package com.repolens.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HashUtils {

    private HashUtils() {
    }

    /**
     * Feature F: 哈希链序列化辅助方法。
     * 将多个字段用 "|" 拼接后计算 SHA-256，用于 ai_contribution_record.record_hash 的规范化计算。
     * null 值视为空字符串。
     */
    public static String sha256Chain(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append("|");
            }
            sb.append(parts[i] == null ? "" : parts[i]);
        }
        return sha256(sb.toString());
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not found", e);
        }
    }
}
