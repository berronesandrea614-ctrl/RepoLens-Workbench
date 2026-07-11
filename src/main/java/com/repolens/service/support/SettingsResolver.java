package com.repolens.service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings 三层合并：local > project > user。
 * - user:    AppSettingEntity（DB，已存在）
 * - project: .repolens/settings.json（仓库级，提交到 git）
 * - local:   .repolens/settings.local.json（本地覆盖，gitignore）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettingsResolver {

    private final ObjectMapper objectMapper;

    /**
     * 合并三层配置，优先级 local > project > user。
     * @param repoRoot 仓库根路径
     * @param userSettings DB 中的用户级配置
     * @return 合并后的配置 Map
     */
    public Map<String, Object> resolve(Path repoRoot, Map<String, Object> userSettings) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (userSettings != null) {
            merged.putAll(userSettings);
        }

        Path projectFile = repoRoot.resolve(".repolens/settings.json");
        if (Files.isRegularFile(projectFile)) {
            Map<String, Object> project = readJsonFile(projectFile);
            if (project != null) merged.putAll(project);
        }

        Path localFile = repoRoot.resolve(".repolens/settings.local.json");
        if (Files.isRegularFile(localFile)) {
            Map<String, Object> local = readJsonFile(localFile);
            if (local != null) merged.putAll(local);
        }

        return merged;
    }

    private Map<String, Object> readJsonFile(Path path) {
        try {
            String content = Files.readString(path);
            return objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            log.warn("SettingsResolver: read {} failed: {}", path, e.getMessage());
            return null;
        }
    }
}
