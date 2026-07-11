package com.repolens.service.impl.support;

import com.repolens.domain.entity.AgentMemoryEntity;
import com.repolens.domain.entity.SensitiveFileEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Aggregates CONSTRAINT memories + BLOCK sensitive files into an AGENTS.md addendum proposal.
 *
 * <p><b>Design contract:</b>
 * <ul>
 *   <li>Pure-ish: no DB access; callers must pass all data in.</li>
 *   <li>Fail-safe: any null input → empty / no-change proposal, never throws.</li>
 *   <li>Dedup: items already present (substring) in currentAgentsMd are skipped.</li>
 *   <li>NEVER writes to disk. Returns proposal text only.</li>
 * </ul>
 */
@Slf4j
@Component
public class AgentsMdProposer {

    /**
     * Immutable proposal result.
     *
     * @param currentContent  the original AGENTS.md content (empty string if absent)
     * @param proposedContent current + appended sections (equals current when hasChanges=false)
     * @param diffMarkdown    unified-ish diff: new lines prefixed with '+'
     * @param hasChanges      true if at least one new item was appended
     */
    public record Proposal(
            String currentContent,
            String proposedContent,
            String diffMarkdown,
            boolean hasChanges) {
    }

    /**
     * Build an AGENTS.md addendum proposal.
     *
     * @param repoId              repository ID (informational, not used for DB access here)
     * @param currentAgentsMd     raw content of current AGENTS.md; null treated as ""
     * @param constraintMemories  CONSTRAINT-type agent memory rows
     * @param blockSensitiveFiles BLOCK-severity sensitive file rows
     * @return proposal (never null)
     */
    public Proposal propose(Long repoId,
                            String currentAgentsMd,
                            List<AgentMemoryEntity> constraintMemories,
                            List<SensitiveFileEntity> blockSensitiveFiles) {
        try {
            return doBuild(repoId, currentAgentsMd, constraintMemories, blockSensitiveFiles);
        } catch (Exception ex) {
            // fail-safe: any unexpected error returns empty proposal
            log.warn("AgentsMdProposer.propose failed for repoId={}, returning no-change proposal: {}",
                    repoId, ex.getMessage());
            String safe = currentAgentsMd != null ? currentAgentsMd : "";
            return new Proposal(safe, safe, "", false);
        }
    }

    // ─── Private implementation ───────────────────────────────────────────────

    private Proposal doBuild(Long repoId,
                             String currentAgentsMd,
                             List<AgentMemoryEntity> constraintMemories,
                             List<SensitiveFileEntity> blockSensitiveFiles) {

        String current = currentAgentsMd != null ? currentAgentsMd : "";
        List<AgentMemoryEntity> memories =
                constraintMemories != null ? constraintMemories : List.of();
        List<SensitiveFileEntity> sensitiveFiles =
                blockSensitiveFiles != null ? blockSensitiveFiles : List.of();

        // Collect new (non-deduped) items
        List<SensitiveFileEntity> newFiles = sensitiveFiles.stream()
                .filter(f -> f.getFilePath() != null && !current.contains(f.getFilePath()))
                .toList();

        List<AgentMemoryEntity> newMemories = memories.stream()
                .filter(m -> m.getContent() != null && !current.contains(m.getContent()))
                .toList();

        if (newFiles.isEmpty() && newMemories.isEmpty()) {
            return new Proposal(current, current, "", false);
        }

        StringBuilder appended = new StringBuilder();
        StringBuilder diff = new StringBuilder();

        // ── 敏感文件 section ──────────────────────────────────────────────────
        if (!newFiles.isEmpty()) {
            String header = "\n## 敏感文件（自动汇总，请审阅）\n";
            appended.append(header);
            diff.append("+").append(header);
            for (SensitiveFileEntity f : newFiles) {
                String reason = (f.getReason() != null && !f.getReason().isBlank())
                        ? f.getReason() : "sensitive file";
                String line = "- `" + f.getFilePath() + "` — " + reason + "（勿擅改）\n";
                appended.append(line);
                diff.append("+").append(line);
            }
        }

        // ── 团队约定 section ──────────────────────────────────────────────────
        if (!newMemories.isEmpty()) {
            String header = "\n## 团队约定（来自 AI 记忆）\n";
            appended.append(header);
            diff.append("+").append(header);
            for (AgentMemoryEntity m : newMemories) {
                String line = "- " + m.getContent() + "\n";
                appended.append(line);
                diff.append("+").append(line);
            }
        }

        String proposed = current + appended;
        return new Proposal(current, proposed, diff.toString(), true);
    }
}
