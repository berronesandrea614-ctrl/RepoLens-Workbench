package com.repolens.kernel.skill;

/**
 * 一个 skill 的定义（对齐 Claude Code / Agent Skills 开放标准）。一个 skill = 一个 {@code SKILL.md}：
 * YAML frontmatter（{@code name}/{@code description}/可选 {@code disable-model-invocation}）+ markdown 正文（body）。
 *
 * <p>渐进披露（progressive disclosure）三级中的前两级由本 record 承载：
 * <ul>
 *   <li><b>索引层</b>（常驻系统提示）：{@link #name} + {@link #description}——让 agent 知道「有哪些能力、何时用」，
 *       但不占大量 token；</li>
 *   <li><b>正文层</b>（触发后注入）：{@link #body}——agent 调用 {@code Skill} 工具或用户斜杠触发后，
 *       整段说明作为 tool_result 注入上下文，agent 随后严格照它执行。</li>
 * </ul>
 * 第三级（同目录 {@code scripts/}/{@code references/}）由 body 里用相对路径按需引用，agent 用 read/bash 自取。
 *
 * @param name        skill 名（kebab-case，全局唯一，等于其目录名；斜杠 {@code /name} 与 {@code Skill} 工具都按此查）
 * @param description 一句话「何时用」（进系统提示的索引层，决定 agent 是否触发；不应概括工作流步骤）
 * @param body        SKILL.md 正文（触发后注入的完整操作说明）
 * @param disableModelInvocation true=只能斜杠手动触发、不进模型自动可选清单（对应 frontmatter 同名字段）
 * @param source      来源：{@code builtin}（内置 classpath）/{@code personal}（~/.claude/skills）/{@code project}（仓库 .claude/skills）
 * @param dir         skill 所在目录（用于 body 引用同目录 scripts/references 时定位；classpath 内置为 null）
 */
public record SkillDefinition(
        String name,
        String description,
        String body,
        boolean disableModelInvocation,
        String source,
        String dir) {
}
