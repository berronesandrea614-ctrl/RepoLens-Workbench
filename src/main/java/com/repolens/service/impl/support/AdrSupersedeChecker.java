package com.repolens.service.impl.support;

import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * ADR supersede checker (Task 4): uses a single LLM call to determine whether
 * a new ADR decision makes an existing ACCEPTED ADR decision obsolete/contradicted.
 *
 * <p>Design mirrors {@link AdrCrystallizer}:
 * <ul>
 *   <li>temperature=0 for deterministic output</li>
 *   <li>Strict parseable contract: {@code VERDICT: SUPERSEDES|INDEPENDENT} + {@code RATIONALE: ...}</li>
 *   <li>Fail-safe: null/throw/unparseable → INDEPENDENT + degraded=true
 *       (conservative — never erroneously supersede on uncertainty)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdrSupersedeChecker {

    private static final String SYSTEM_PROMPT = """
            You are an Architecture Decision Record (ADR) reviewer.
            Given a NEW ADR decision and an OLD ADR decision, determine whether the new decision \
            makes the old decision obsolete or contradicted.

            Respond using EXACTLY these labeled lines:
            VERDICT: SUPERSEDES
            RATIONALE: <one-sentence explanation>

            or:
            VERDICT: INDEPENDENT
            RATIONALE: <one-sentence explanation>

            Rules:
            - If the new decision directly contradicts, replaces, or makes the old decision obsolete, use SUPERSEDES.
            - If the decisions are independent, complementary, or address different concerns, use INDEPENDENT.
            - When in doubt, choose INDEPENDENT (conservative — do not destroy records on uncertainty).
            - Output only these two labeled lines.""";

    private final LlmClient llmClient;
    private final LlmRuntimeConfig llmRuntimeConfig;

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Does {@code newDecision} make {@code oldDecision} obsolete/contradicted?
     *
     * <p>Fail-safe: LLM null/throw/unparseable → {@code Verdict(supersedes=false, degraded=true)}.
     * Conservative: never supersede on uncertainty.
     */
    public Verdict check(String newTitle, String newDecision, String oldTitle, String oldDecision) {
        try {
            String userPrompt = buildUserPrompt(newTitle, newDecision, oldTitle, oldDecision);
            LlmResponse response = llmClient.generate(LlmRequest.builder()
                    .modelName(llmRuntimeConfig.getModelName())
                    .systemPrompt(SYSTEM_PROMPT)
                    .userPrompt(userPrompt)
                    .temperature(0.0d)
                    .timeoutMs(llmRuntimeConfig.getTimeoutMs())
                    .build());
            if (response == null) {
                log.debug("AdrSupersedeChecker: LLM returned null, defaulting to INDEPENDENT");
                return Verdict.independent("LLM returned null", true);
            }
            Verdict parsed = parse(response.getContent());
            if (parsed == null) {
                log.debug("AdrSupersedeChecker: LLM output unparseable, defaulting to INDEPENDENT");
                return Verdict.independent("Unparseable LLM output", true);
            }
            return parsed;
        } catch (Exception ex) {
            log.warn("AdrSupersedeChecker LLM call failed, defaulting to INDEPENDENT. err={}", ex.getMessage());
            return Verdict.independent("LLM call failed: " + ex.getMessage(), true);
        }
    }

    // ── record ────────────────────────────────────────────────────────────────

    /** Verdict of a supersede check. {@code degraded=true} when the result is a fail-safe fallback. */
    public record Verdict(boolean supersedes, String rationale, boolean degraded) {

        /** Convenience factory for INDEPENDENT (fail-safe or genuine). */
        static Verdict independent(String rationale, boolean degraded) {
            return new Verdict(false, rationale, degraded);
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String buildUserPrompt(String newTitle, String newDecision,
                                   String oldTitle, String oldDecision) {
        return "NEW ADR title: " + safe(newTitle) + "\n"
                + "NEW ADR decision: " + safe(newDecision) + "\n\n"
                + "OLD ADR title: " + safe(oldTitle) + "\n"
                + "OLD ADR decision: " + safe(oldDecision);
    }

    private String safe(String s) {
        return s != null ? s : "(none)";
    }

    /**
     * Parse LLM output.
     *
     * <p>Expects:
     * <pre>
     * VERDICT: SUPERSEDES|INDEPENDENT
     * RATIONALE: &lt;text&gt;
     * </pre>
     * Returns {@code null} if VERDICT is missing or unrecognized (→ caller degrades to INDEPENDENT).
     */
    private Verdict parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String verdict = null;
        String rationale = "(no rationale)";
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("VERDICT:")) {
                String val = trimmed.substring("VERDICT:".length()).trim();
                if ("SUPERSEDES".equalsIgnoreCase(val)) {
                    verdict = "SUPERSEDES";
                } else if ("INDEPENDENT".equalsIgnoreCase(val)) {
                    verdict = "INDEPENDENT";
                }
                // else: unrecognized value → verdict stays null
            } else if (trimmed.startsWith("RATIONALE:")) {
                rationale = trimmed.substring("RATIONALE:".length()).trim();
            }
        }
        if (verdict == null) {
            return null; // unparseable — trigger fail-safe in caller
        }
        return new Verdict("SUPERSEDES".equals(verdict), rationale, false);
    }
}
