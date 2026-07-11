package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Response VO for the AGENTS.md addendum proposal endpoint.
 *
 * <p>Note: this proposal is read-only. The backend never writes AGENTS.md to disk;
 * the frontend shows the diff and the user decides whether to apply it.
 */
@Data
@Builder
public class AgentsMdProposalVO {

    /** Current AGENTS.md content (empty string if file does not exist). */
    private String currentContent;

    /** Proposed full content = currentContent + appended sections. */
    private String proposedContent;

    /** Simple unified-ish diff: added lines prefixed with '+'. */
    private String diffMarkdown;

    /** True if at least one new item was appended. */
    private boolean hasChanges;
}
