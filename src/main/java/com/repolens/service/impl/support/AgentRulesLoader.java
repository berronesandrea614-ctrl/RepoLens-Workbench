package com.repolens.service.impl.support;

import com.repolens.domain.entity.RepoEntity;
import com.repolens.mapper.RepoMapper;
import com.repolens.service.support.RepoWorkspaceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 读取仓库根目录的项目规则文件（AGENTS.md 或 .repolens/rules.md），
 * 注入到 LLM system prompt，引导模型遵守项目级约定。
 * 失败安全：文件不存在或读取失败时返回 null，绝不影响主回答。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRulesLoader {

    private static final int MAX_RULES_CHARS = 4000;
    private static final String[] CANDIDATE_PATHS = {"AGENTS.md", ".repolens/rules.md"};

    private final RepoMapper repoMapper;
    private final RepoWorkspaceResolver repoWorkspaceResolver;

    /**
     * 读取仓库根目录的项目规则文件（AGENTS.md 或 .repolens/rules.md）。
     * 不存在或读取失败时返回 null（失败安全）。
     * 内容超过 MAX_RULES_CHARS 时截断。
     *
     * @return 规则文本，不存在或读取失败时返回 null
     */
    public String loadRules(Long repoId) {
        try {
            RepoEntity repo = repoMapper.selectById(repoId);
            if (repo == null) {
                return null;
            }
            Path repoDir = repoWorkspaceResolver.resolveRepoDirectory(repo);
            for (String candidate : CANDIDATE_PATHS) {
                try {
                    Path rulesPath = repoWorkspaceResolver.resolveSafeFilePath(repoDir, candidate);
                    if (!Files.isRegularFile(rulesPath)) {
                        continue;
                    }
                    String content = Files.readString(rulesPath, StandardCharsets.UTF_8);
                    if (!StringUtils.hasText(content)) {
                        continue;
                    }
                    if (content.length() > MAX_RULES_CHARS) {
                        content = content.substring(0, MAX_RULES_CHARS) + "\n[...已截断]";
                    }
                    log.debug("loaded project rules from {}, repoId={}", candidate, repoId);
                    return content;
                } catch (Exception ex) {
                    // 路径安全检查失败（如 symlink 逃逸）或文件读取失败：跳过此候选
                    log.debug("skipped rules candidate {}, repoId={}, reason={}", candidate, repoId, ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.warn("loadRules failed for repoId={}, err={}", repoId, ex.getMessage());
        }
        return null;
    }

    /**
     * Parses rules text into typed {@link ConstraintRule} objects.
     * Delegates to {@link ConstraintRuleParser#parseRules(String)}.
     * Failure-safe: returns empty list on null/blank input.
     *
     * @param rulesText raw text from AGENTS.md / .repolens/rules.md
     * @return list of parsed constraint rules; never null
     */
    public List<ConstraintRule> parseRules(String rulesText) {
        return ConstraintRuleParser.parseRules(rulesText);
    }
}
