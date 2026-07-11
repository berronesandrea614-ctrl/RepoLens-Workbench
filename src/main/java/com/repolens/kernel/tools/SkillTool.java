package com.repolens.kernel.tools;

import com.repolens.kernel.skill.SkillDefinition;
import com.repolens.kernel.skill.SkillRegistry;
import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code Skill} 工具（渐进披露第二级注入口）：agent 判断某 skill 与当前任务相关时调用它，
 * 把该 skill 的完整操作说明（SKILL.md body）作为 tool_result 注入上下文，agent 随后严格照其执行。
 *
 * <p>这是 model-invoked 路径；user-invoked 路径（斜杠 {@code /skill-name}）由
 * {@link com.repolens.kernel.skill.SkillSlashResolver} 触发，最终同样落到「加载该 skill 的 body」——
 * 两条路径共用 {@link SkillRegistry} 单一真源，body 注入逻辑一致。
 *
 * <p>readOnly=true：本工具只返回文本、不碰文件，因此在只读并发池执行、且 PLAN 模式下亦可用
 * （研究/规划类 skill 在只读模式下也该能触发）。
 */
@Component
public class SkillTool implements KernelTool {

    private final SkillRegistry registry;

    public SkillTool(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "Skill";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("Skill")
                .description("加载并激活一个 skill（专业能力/工作流），返回该 skill 的完整操作说明；"
                        + "你随后必须严格按返回的说明执行。可用 skill 见系统提示的「可用 Skills」清单——"
                        + "任务匹配某个 skill 时先用本工具加载它，而不是凭记忆自行发挥。可选 args 传给 skill 的补充信息。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "skill", Map.of(
                                        "type", "string",
                                        "description", "要加载的 skill 名（来自系统提示的可用 Skills 清单，如 deep-research/brainstorm）"),
                                "args", Map.of(
                                        "type", "string",
                                        "description", "（可选）传给该 skill 的补充参数/上下文")),
                        "required", List.of("skill")))
                .build();
    }

    @Override
    public String execute(ToolContext ctx, Map<String, Object> args) {
        String skillName = str(args.get("skill"));
        if (skillName == null || skillName.isBlank()) {
            return "Skill 调用缺少参数 skill。请从系统提示的「可用 Skills」清单里选一个名字。";
        }
        java.nio.file.Path repoDir = ctx == null ? null : ctx.repoDir();
        SkillDefinition def = registry.get(repoDir, skillName.strip());
        if (def == null) {
            Map<String, SkillDefinition> all = registry.effective(repoDir);
            return "未找到 skill：" + skillName + "。可用 skill：" + all.keySet();
        }
        String extra = str(args.get("args"));
        StringBuilder sb = new StringBuilder();
        sb.append("已加载 skill「").append(def.name()).append("」。以下是它的完整操作说明，请严格照此执行：\n\n");
        sb.append("=== SKILL: ").append(def.name()).append(" ===\n");
        sb.append(def.body()).append('\n');
        sb.append("=== END SKILL ===\n");
        if (extra != null && !extra.isBlank()) {
            sb.append("\n[本次调用的补充参数] ").append(extra).append('\n');
        }
        if (def.dir() != null) {
            sb.append("\n（该 skill 目录：").append(def.dir())
                    .append("——如说明里引用了同目录的 scripts/references 文件，用 read/bash 按相对路径自取。）\n");
        }
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
