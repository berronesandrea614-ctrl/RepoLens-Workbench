package com.repolens.service.support;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionFileReadTracker {

    private final Map<Long, Map<String, String>> store = new ConcurrentHashMap<>();

    public void record(Long sessionId, String filePath, String content) {
        store.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
             .put(normalize(filePath), sha256(content));
    }

    public Optional<String> getHash(Long sessionId, String filePath) {
        Map<String, String> inner = store.get(sessionId);
        if (inner == null) return Optional.empty();
        return Optional.ofNullable(inner.get(normalize(filePath)));
    }

    public void clearSession(Long sessionId) {
        store.remove(sessionId);
    }

    private String normalize(String path) {
        return path == null ? "" : path.trim().replace('\\', '/');
    }

    private String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }
}
