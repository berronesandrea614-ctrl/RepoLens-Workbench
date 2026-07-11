package com.repolens.service.impl.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 内置离线恶意包快照加载器。
 * <p>
 * 从 classpath:depcheck/known-malicious.json 加载各生态已知恶意包名，
 * 在 OFFLINE 模式下或网络调用前作为快速本地检测。
 * 资源文件缺失时静默（仅打 warn log），不影响正常检测流程。
 * </p>
 */
@Slf4j
@Component
public class OfflineSnapshotLoader {

    private final Set<String> maliciousNpm  = new HashSet<>();
    private final Set<String> maliciousPypi = new HashSet<>();
    private final Set<String> maliciousMaven = new HashSet<>();

    public OfflineSnapshotLoader() {
        load();
    }

    private void load() {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("depcheck/known-malicious.json")) {
            if (in == null) {
                log.warn("depcheck/known-malicious.json not found in classpath; offline snapshot empty");
                return;
            }
            JsonNode root = new ObjectMapper().readTree(in);
            loadArray(root, "npm",   maliciousNpm);
            loadArray(root, "pypi",  maliciousPypi);
            loadArray(root, "maven", maliciousMaven);
            log.info("Offline malicious snapshot loaded: npm={}, pypi={}, maven={}",
                    maliciousNpm.size(), maliciousPypi.size(), maliciousMaven.size());
        } catch (Exception e) {
            log.warn("Failed to load offline malicious snapshot: {}", e.getMessage());
        }
    }

    private void loadArray(JsonNode root, String key, Set<String> target) {
        JsonNode arr = root.get(key);
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> {
                String s = n.asText();
                if (!s.isBlank()) target.add(s.toLowerCase(Locale.ROOT));
            });
        }
    }

    /**
     * Returns true if the given package is in the offline malicious snapshot.
     *
     * @param ecosystem npm / pypi / maven
     * @param packageName package name (case-insensitive)
     */
    public boolean isMaliciousOffline(String ecosystem, String packageName) {
        if (packageName == null || packageName.isBlank()) return false;
        String lower = packageName.toLowerCase(Locale.ROOT);
        return switch (ecosystem) {
            case ExtractedDep.ECOSYSTEM_NPM   -> maliciousNpm.contains(lower);
            case ExtractedDep.ECOSYSTEM_PYPI  -> maliciousPypi.contains(lower);
            case ExtractedDep.ECOSYSTEM_MAVEN -> maliciousMaven.contains(lower);
            default -> false;
        };
    }
}
