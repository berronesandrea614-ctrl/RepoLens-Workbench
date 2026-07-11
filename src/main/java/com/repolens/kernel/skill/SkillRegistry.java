package com.repolens.kernel.skill;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * skill 注册表：统一 skill 的发现、覆盖优先级与查询，是 {@code Skill} 工具、斜杠触发、系统提示索引三个入口的单一真源。
 *
 * <p>覆盖优先级（后者覆盖前者，按 name 去重）：<b>内置(builtin) &lt; 个人(personal) &lt; 项目(project)</b>。
 * 内置 + 个人在启动时加载一次并缓存（不随 run 变化）；项目级随 {@code repoDir} 每次查询时叠加
 * （仓库自带的 {@code .claude/skills} 可覆盖/新增，随仓库走）。
 *
 * <p>渐进披露：{@link #indexFor(Path)} 只吐 name+description 供进系统提示（索引层，常驻但省 token）；
 * {@link #get(Path, String)} 供触发后取完整 body（正文层，按需注入）。
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final SkillLoader loader;

    /** 内置 + 个人：启动时加载一次，name → def（个人覆盖内置）。 */
    private final Map<String, SkillDefinition> base = new LinkedHashMap<>();

    public SkillRegistry(SkillLoader loader) {
        this.loader = loader;
    }

    @PostConstruct
    void init() {
        for (SkillDefinition d : loader.loadBuiltin()) {
            base.put(d.name(), d);
        }
        Path personal = personalSkillsDir();
        for (SkillDefinition d : loader.loadFromDir(personal, "personal")) {
            base.put(d.name(), d);
        }
        log.info("[skill] 内置+个人 skill 载入 {} 个：{}", base.size(), base.keySet());
    }

    /** 某仓库下生效的全部 skill（base + 项目级覆盖），按 name 排稳定顺序。 */
    public Map<String, SkillDefinition> effective(Path repoDir) {
        Map<String, SkillDefinition> merged = new LinkedHashMap<>(base);
        if (repoDir != null) {
            Path projectSkills = repoDir.resolve(".claude/skills");
            for (SkillDefinition d : loader.loadFromDir(projectSkills, "project")) {
                merged.put(d.name(), d);
            }
        }
        return merged;
    }

    /** 取某个生效 skill（含项目级覆盖）；不存在返回 null。 */
    public SkillDefinition get(Path repoDir, String name) {
        if (name == null) {
            return null;
        }
        return effective(repoDir).get(name.strip());
    }

    /** 是否存在该生效 skill。 */
    public boolean has(Path repoDir, String name) {
        return get(repoDir, name) != null;
    }

    /**
     * 供系统提示的 skill 索引块（渐进披露第一级）：每个 skill 一行 {@code - name — description}。
     * 只列可被模型自动触发的（{@code disable-model-invocation=false}）。无 skill 返回空串。
     */
    public String indexFor(Path repoDir) {
        List<SkillDefinition> ds = effective(repoDir).values().stream()
                .filter(d -> !d.disableModelInvocation())
                .toList();
        if (ds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 可用 Skills（专业能力/工作流）\n");
        sb.append("当任务匹配下列某个 skill 时，先调用 Skill 工具（skill=\"名字\"）加载其完整操作说明，再严格照它执行；")
                .append("不要凭记忆臆造流程。1% 相关就值得先加载看一眼。\n");
        for (SkillDefinition d : ds) {
            sb.append("- ").append(d.name()).append(" — ").append(oneLine(d.description())).append('\n');
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace("\n", " ").strip();
        return one.length() > 400 ? one.substring(0, 400) + "…" : one;
    }

    private static Path personalSkillsDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        return Paths.get(home, ".claude", "skills");
    }
}
