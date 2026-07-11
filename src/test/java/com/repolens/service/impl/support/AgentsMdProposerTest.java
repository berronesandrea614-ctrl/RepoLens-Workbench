package com.repolens.service.impl.support;

import com.repolens.domain.entity.AgentMemoryEntity;
import com.repolens.domain.entity.SensitiveFileEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * TDD tests for AgentsMdProposer.
 * Validates proposal generation, substring dedup, hasChanges=false logic, and null safety.
 */
class AgentsMdProposerTest {

    private AgentsMdProposer proposer;

    @BeforeEach
    void setUp() {
        proposer = new AgentsMdProposer();
    }

    // ─── Helper builders ──────────────────────────────────────────────────────

    private SensitiveFileEntity sensitiveFile(String path, String reason) {
        SensitiveFileEntity e = new SensitiveFileEntity();
        e.setFilePath(path);
        e.setReason(reason);
        e.setSeverity("BLOCK");
        return e;
    }

    private AgentMemoryEntity constraintMemory(String content) {
        AgentMemoryEntity e = new AgentMemoryEntity();
        e.setContent(content);
        e.setMemoryType("CONSTRAINT");
        return e;
    }

    // ─── Test: appends sensitive + constraint sections ────────────────────────

    @Test
    void propose_appendsSensitiveAndConstraintSections_whenBothPresent() {
        String current = "# Project Rules\n\nDo not break prod.\n";
        List<SensitiveFileEntity> files = List.of(
                sensitiveFile("src/main/resources/application-prod.yml", "production credentials")
        );
        List<AgentMemoryEntity> memories = List.of(
                constraintMemory("Never commit secrets to git")
        );

        AgentsMdProposer.Proposal proposal = proposer.propose(1L, current, memories, files);

        assertThat(proposal.hasChanges()).isTrue();
        assertThat(proposal.currentContent()).isEqualTo(current);
        assertThat(proposal.proposedContent()).contains("## 敏感文件（自动汇总，请审阅）");
        assertThat(proposal.proposedContent()).contains("application-prod.yml");
        assertThat(proposal.proposedContent()).contains("production credentials");
        assertThat(proposal.proposedContent()).contains("勿擅改");
        assertThat(proposal.proposedContent()).contains("## 团队约定（来自 AI 记忆）");
        assertThat(proposal.proposedContent()).contains("Never commit secrets to git");
        // proposed = current + appended; current unchanged
        assertThat(proposal.proposedContent()).startsWith(current);
    }

    @Test
    void propose_diffMarkdown_containsAddedLinesPrefixedWithPlus() {
        String current = "# Rules\n";
        List<SensitiveFileEntity> files = List.of(
                sensitiveFile("config/secrets.env", "env secrets")
        );
        List<AgentMemoryEntity> memories = List.of();

        AgentsMdProposer.Proposal proposal = proposer.propose(1L, current, memories, files);

        assertThat(proposal.diffMarkdown()).contains("+");
        assertThat(proposal.diffMarkdown()).contains("secrets.env");
    }

    // ─── Test: substring dedup - already present items skipped ───────────────

    @Test
    void propose_dedupsAlreadyPresentSensitiveFile() {
        // current already mentions the file path
        String current = "# Rules\n\n- `src/main/resources/application-prod.yml` — already documented\n";
        List<SensitiveFileEntity> files = List.of(
                sensitiveFile("src/main/resources/application-prod.yml", "production credentials")
        );
        List<AgentMemoryEntity> memories = List.of();

        AgentsMdProposer.Proposal proposal = proposer.propose(1L, current, memories, files);

        // The file path is already in currentAgentsMd → skip → no changes
        assertThat(proposal.hasChanges()).isFalse();
        assertThat(proposal.proposedContent()).isEqualTo(current);
    }

    @Test
    void propose_dedupsAlreadyPresentConstraintMemory() {
        String constraint = "Never commit secrets to git";
        String current = "# Rules\n\n- " + constraint + "\n";
        List<SensitiveFileEntity> files = List.of();
        List<AgentMemoryEntity> memories = List.of(
                constraintMemory(constraint)
        );

        AgentsMdProposer.Proposal proposal = proposer.propose(1L, current, memories, files);

        assertThat(proposal.hasChanges()).isFalse();
        assertThat(proposal.proposedContent()).isEqualTo(current);
    }

    @Test
    void propose_partialDedup_onlyNewItemsAppended() {
        String current = "# Rules\n\n- `existing.yml` — already here\n";
        List<SensitiveFileEntity> files = List.of(
                sensitiveFile("existing.yml", "already documented"),
                sensitiveFile("new-secrets.env", "new sensitive file")
        );
        List<AgentMemoryEntity> memories = List.of();

        AgentsMdProposer.Proposal proposal = proposer.propose(1L, current, memories, files);

        assertThat(proposal.hasChanges()).isTrue();
        assertThat(proposal.proposedContent()).contains("new-secrets.env");
        // existing.yml is already present → not duplicated in appended section
        long occurrences = proposal.proposedContent().chars()
                .filter(c -> c == '\n')
                .count(); // rough check that existing is NOT added again
        assertThat(proposal.proposedContent()).doesNotContain("## 敏感文件（自动汇总，请审阅）\n- `existing.yml`");
    }

    // ─── Test: hasChanges=false when nothing new ──────────────────────────────

    @Test
    void propose_hasChangesFalse_whenNoNewItems() {
        String current = "# Rules\n";
        List<SensitiveFileEntity> files = List.of();
        List<AgentMemoryEntity> memories = List.of();

        AgentsMdProposer.Proposal proposal = proposer.propose(1L, current, memories, files);

        assertThat(proposal.hasChanges()).isFalse();
        assertThat(proposal.proposedContent()).isEqualTo(current);
        assertThat(proposal.diffMarkdown()).isEmpty();
    }

    // ─── Test: null inputs do not throw ──────────────────────────────────────

    @Test
    void propose_nullCurrentAgentsMd_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> {
            AgentsMdProposer.Proposal proposal = proposer.propose(1L, null,
                    List.of(constraintMemory("some constraint")),
                    List.of(sensitiveFile("secrets.yml", "creds")));
            assertThat(proposal).isNotNull();
            assertThat(proposal.hasChanges()).isTrue();
        });
    }

    @Test
    void propose_nullConstraintMemories_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> {
            AgentsMdProposer.Proposal proposal = proposer.propose(1L, "# Rules\n",
                    null,
                    List.of(sensitiveFile("secrets.yml", "creds")));
            assertThat(proposal).isNotNull();
        });
    }

    @Test
    void propose_nullBlockSensitiveFiles_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> {
            AgentsMdProposer.Proposal proposal = proposer.propose(1L, "# Rules\n",
                    List.of(constraintMemory("some constraint")),
                    null);
            assertThat(proposal).isNotNull();
        });
    }

    @Test
    void propose_allNullInputs_returnsNoChangesProposal() {
        AgentsMdProposer.Proposal proposal = proposer.propose(null, null, null, null);
        assertThat(proposal).isNotNull();
        assertThat(proposal.hasChanges()).isFalse();
        assertThat(proposal.currentContent()).isEmpty();
        assertThat(proposal.proposedContent()).isEmpty();
        assertThat(proposal.diffMarkdown()).isEmpty();
    }

    @Test
    void propose_sensitiveFileWithNullPath_skippedGracefully() {
        SensitiveFileEntity badFile = new SensitiveFileEntity();
        badFile.setFilePath(null);
        badFile.setReason("some reason");

        assertThatNoException().isThrownBy(() -> {
            AgentsMdProposer.Proposal proposal = proposer.propose(1L, "# Rules\n",
                    List.of(), List.of(badFile));
            // null path skipped → no changes
            assertThat(proposal.hasChanges()).isFalse();
        });
    }

    @Test
    void propose_memoryWithNullContent_skippedGracefully() {
        AgentMemoryEntity badMemory = new AgentMemoryEntity();
        badMemory.setContent(null);

        assertThatNoException().isThrownBy(() -> {
            AgentsMdProposer.Proposal proposal = proposer.propose(1L, "# Rules\n",
                    List.of(badMemory), List.of());
            assertThat(proposal.hasChanges()).isFalse();
        });
    }
}
