package com.repolens.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureLedgerService {

    private final ObjectMapper objectMapper;

    private static final String FEATURES_PATH = ".repolens/features.json";

    public record Feature(String id, String description, String status, String oraclePath) {}

    @SuppressWarnings("unchecked")
    public List<Feature> load(Path repoRoot) {
        try {
            Path file = repoRoot.resolve(FEATURES_PATH);
            if (!Files.exists(file)) return List.of();
            List<Map<String, Object>> raw = objectMapper.readValue(file.toFile(), List.class);
            return raw.stream().map(m -> new Feature(
                    String.valueOf(m.getOrDefault("id", "")),
                    String.valueOf(m.getOrDefault("description", "")),
                    String.valueOf(m.getOrDefault("status", "failing")),
                    (String) m.get("oraclePath")
            )).toList();
        } catch (Exception e) {
            log.warn("FeatureLedgerService.load failed (fail-safe): {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public void reconcile(Path repoRoot, String oraclePath) {
        if (oraclePath == null) return;
        try {
            Path file = repoRoot.resolve(FEATURES_PATH);
            if (!Files.exists(file)) return;
            List<Map<String, Object>> features = objectMapper.readValue(file.toFile(), List.class);
            boolean changed = false;
            for (Map<String, Object> f : features) {
                if (oraclePath.equals(f.get("oraclePath")) && "failing".equals(f.get("status"))) {
                    f.put("status", "done");
                    changed = true;
                }
            }
            if (changed) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), features);
            }
        } catch (Exception e) {
            log.warn("FeatureLedgerService.reconcile failed (fail-safe): {}", e.getMessage());
        }
    }

    public boolean hasUnfinishedFeatures(Path repoRoot) {
        return load(repoRoot).stream().anyMatch(f -> "failing".equals(f.status()));
    }
}
