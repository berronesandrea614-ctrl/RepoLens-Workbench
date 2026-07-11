package com.repolens.bridge;

import com.repolens.common.result.Result;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.kernel.skill.SkillDefinition;
import com.repolens.kernel.skill.SkillRegistry;
import com.repolens.kernel.slash.SlashCommandService;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.AuthUserId;
import com.repolens.service.support.RepoWorkspaceResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 斜杠面板数据源（bridge zone）：把内核的可用 skill + 自定义 slash 命令列给前端，
 * 供聊天输入框的 {@code /} 斜杠面板做选择/调用（对齐 Claude Code 的 {@code /command} 面板）。
 *
 * <p>只回 name/description/source（不回 SKILL.md 正文——正文由 agent 触发 Skill 工具时才注入，
 * 前端面板只需索引层）。skill 的 name+description 正是渐进披露第一级，天然适合做面板项。
 */
@RestController
@RequestMapping("/api/repos/{repoId}/agent/skills")
public class KernelSkillController {

    private final SkillRegistry skillRegistry;
    private final SlashCommandService slashCommandService;
    private final RepoMapper repoMapper;
    private final RepoWorkspaceResolver repoWorkspaceResolver;

    public KernelSkillController(SkillRegistry skillRegistry, SlashCommandService slashCommandService,
                                 RepoMapper repoMapper, RepoWorkspaceResolver repoWorkspaceResolver) {
        this.skillRegistry = skillRegistry;
        this.slashCommandService = slashCommandService;
        this.repoMapper = repoMapper;
        this.repoWorkspaceResolver = repoWorkspaceResolver;
    }

    /**
     * 一个斜杠面板项。
     *
     * @param name        斜杠名（用户打 {@code /name} 触发）
     * @param description 一句话说明（何时用）
     * @param type        {@code skill}（能力/工作流）或 {@code command}（.claude/commands 自定义命令）
     * @param source      来源：builtin/personal/project（command 恒为 project）
     */
    public record SlashItem(String name, String description, String type, String source) {
    }

    /** 列出当前仓库下可用的斜杠项：内置/个人/项目 skill + 项目自定义命令。可自动触发的 skill 才列。 */
    @GetMapping
    public Result<List<SlashItem>> list(@AuthUserId Long userId, @PathVariable Long repoId) {
        Path repoDir = repoDirOrNull(repoId);
        List<SlashItem> out = new ArrayList<>();

        Map<String, SkillDefinition> skills = skillRegistry.effective(repoDir);
        for (SkillDefinition d : skills.values()) {
            if (d.disableModelInvocation()) {
                continue;
            }
            out.add(new SlashItem(d.name(), d.description(), "skill", d.source()));
        }

        if (repoDir != null) {
            Map<String, Path> cmds = slashCommandService.discover(repoDir);
            for (String name : cmds.keySet()) {
                // 与内置 skill 同名时不重复列（skill 已在上面）
                if (!skills.containsKey(name)) {
                    out.add(new SlashItem(name, "自定义命令（.claude/commands/" + name + ".md）", "command", "project"));
                }
            }
        }
        return Result.success(out);
    }

    private Path repoDirOrNull(Long repoId) {
        try {
            RepoEntity repo = repoMapper.selectById(repoId);
            if (repo == null) {
                return null;
            }
            return repoWorkspaceResolver.resolveReadDirectory(repo);
        } catch (Exception e) {
            // 解析失败不阻断：仍返回内置+个人 skill（repoDir=null）
            return null;
        }
    }
}
