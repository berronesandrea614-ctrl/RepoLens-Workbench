package com.repolens.kernel.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 三层 Settings 合并（M7.3）：<b>内置默认 &lt; 项目级 {@code .claude/settings.json} &lt; 用户级</b>。
 * 后者覆盖前者，产出一份「生效配置」（如 {@code chore-model}、hook 开关）。
 *
 * <p>最小真实：读 repoDir 下 {@code .claude/settings.json}（JSON 扁平键值）覆盖内置默认；
 * 用户级从 {@code repolens.kernel.user-settings-path}（可选，默认 {@code ~/.claude/settings.json}）读，
 * 覆盖项目级。任一文件缺失/解析失败都 fail-safe 降级（用已合并到该层为止的结果），绝不阻断。
 *
 * <p>只做扁平字符串键值的浅合并——足以支撑本里程碑（chore-model、hook 开关），不引入 schema 复杂度。
 * 嵌套对象（如 hooks 数组）留待后续，当前诚实地只消费顶层标量键。
 */
@Component("kernelSettingsResolver")
public class SettingsResolver {

    private static final Logger log = LoggerFactory.getLogger(SettingsResolver.class);

    /** 相对 repoDir 的项目级 settings 路径。 */
    public static final String PROJECT_SETTINGS_REL = ".claude/settings.json";

    /** 内置默认。当前只有 chore-model 无默认值（=不配则回退主模型），其余键按需扩展。 */
    private static final Map<String, String> BUILTIN_DEFAULTS = Map.of(
            "hooks.enabled", "true");

    private final ObjectMapper mapper = new ObjectMapper();

    /** 用户级 settings 路径；缺省 ~/.claude/settings.json。测试可通过属性覆盖到临时目录。 */
    @Value("${repolens.kernel.user-settings-path:}")
    private String userSettingsPath;

    /**
     * 解析某个 repo 的生效配置：内置默认 &lt; 项目 &lt; 用户，逐层覆盖。
     *
     * @param repoDir 仓库根（读 {@code .claude/settings.json}）；空则只有默认 + 用户级
     * @return 生效配置（扁平字符串键值，不可变视图语义——返回新 map）
     */
    public Map<String, String> resolve(Path repoDir) {
        Map<String, String> effective = new HashMap<>(BUILTIN_DEFAULTS);

        // 项目级覆盖
        if (repoDir != null) {
            merge(effective, readFlat(repoDir.resolve(PROJECT_SETTINGS_REL)));
        }
        // 用户级覆盖（最高优先级）
        Path userPath = resolveUserPath();
        if (userPath != null) {
            merge(effective, readFlat(userPath));
        }
        return effective;
    }

    /** 便捷读取单个生效键。 */
    public String get(Path repoDir, String key) {
        return resolve(repoDir).get(key);
    }

    private Path resolveUserPath() {
        if (userSettingsPath != null && !userSettingsPath.isBlank()) {
            return Path.of(userSettingsPath);
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        return Path.of(home, ".claude", "settings.json");
    }

    /** 读一个 settings.json 为扁平字符串键值；缺失/非法返回空 map（fail-safe）。 */
    private Map<String, String> readFlat(Path file) {
        Map<String, String> out = new HashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(file));
            if (root == null || !root.isObject()) {
                return out;
            }
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                // 只吸收顶层标量（string/number/bool）；嵌套对象/数组本里程碑不消费。
                if (v != null && v.isValueNode()) {
                    out.put(e.getKey(), v.asText());
                }
            }
        } catch (Exception ex) {
            log.warn("[settings] 读取 {} 失败，忽略该层（fail-safe）：{}", file, ex.getMessage());
        }
        return out;
    }

    private static void merge(Map<String, String> base, Map<String, String> override) {
        for (Map.Entry<String, String> e : override.entrySet()) {
            if (e.getValue() != null) {
                base.put(e.getKey(), e.getValue());
            }
        }
    }
}
