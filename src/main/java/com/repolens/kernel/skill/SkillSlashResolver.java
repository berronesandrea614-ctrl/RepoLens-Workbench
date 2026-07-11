package com.repolens.kernel.skill;

import com.repolens.kernel.slash.SlashCommandService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 斜杠输入路由（user-invoked 入口）：把用户输入的 {@code /name args} 解析成喂进 loop 的用户 prompt。
 *
 * <p>解析优先级：
 * <ol>
 *   <li>不以 {@code /} 开头 → 返回 null（普通输入，原样交给 agent）；</li>
 *   <li>命中 {@code .claude/commands/<name>.md} 自定义命令 → 由 {@link SlashCommandService} 展开正文（$ARGUMENTS 替换）；</li>
 *   <li>命中一个 skill（{@code /deep-research …}）→ 生成一段触发 prompt，让 agent 立即调用 {@code Skill} 工具
 *       加载该 skill 的完整说明再执行——<b>仍走 Skill 工具单一注入路径</b>，保持渐进披露纪律一致；</li>
 *   <li>都不命中 → 返回 null（未知斜杠按普通文本处理，不报错）。</li>
 * </ol>
 */
@Component
public class SkillSlashResolver {

    private final SlashCommandService slashCommandService;
    private final SkillRegistry skillRegistry;

    public SkillSlashResolver(SlashCommandService slashCommandService, SkillRegistry skillRegistry) {
        this.slashCommandService = slashCommandService;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 路由并展开斜杠输入；非斜杠 / 未命中返回 null（调用方回退用原输入）。
     *
     * @param input   用户原始输入
     * @param repoDir 仓库根（命令目录 + 项目 skill 的锚点）
     */
    public String route(String input, Path repoDir) {
        if (input == null) {
            return null;
        }
        String trimmed = input.strip();
        if (!trimmed.startsWith("/") || trimmed.length() < 2) {
            return null;
        }
        // 1) 自定义命令优先（.claude/commands）
        String cmd = slashCommandService.expand(trimmed, repoDir);
        if (cmd != null) {
            return cmd;
        }
        // 2) skill 斜杠触发
        int sp = indexOfWhitespace(trimmed);
        String name = (sp < 0 ? trimmed.substring(1) : trimmed.substring(1, sp)).strip();
        String arguments = sp < 0 ? "" : trimmed.substring(sp + 1).strip();
        if (name.isEmpty()) {
            return null;
        }
        SkillDefinition skill = skillRegistry.get(repoDir, name);
        if (skill == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("请执行 skill「").append(skill.name()).append("」。\n");
        if (!arguments.isEmpty()) {
            sb.append("用户补充参数：").append(arguments).append('\n');
        }
        sb.append("第一步：立即调用 Skill 工具（skill=\"").append(skill.name())
                .append("\"）加载该 skill 的完整操作说明，然后严格按其步骤执行。");
        return sb.toString();
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
