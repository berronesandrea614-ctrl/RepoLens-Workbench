package com.repolens.service.impl.support;

import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AdrSupersedeChecker unit tests (mock LlmClient, no real LLM).
 *
 * <p>TDD: covers all 5 required cases from the brief:
 * <ol>
 *   <li>SUPERSEDES parsed → supersedes=true, degraded=false</li>
 *   <li>INDEPENDENT parsed → supersedes=false, degraded=false</li>
 *   <li>LLM returns null → INDEPENDENT + degraded=true (fail-safe)</li>
 *   <li>LLM throws → INDEPENDENT + degraded=true, no exception propagated</li>
 *   <li>Unparseable output (no VERDICT) → INDEPENDENT + degraded=true</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AdrSupersedeCheckerTest {

    @Mock
    private LlmClient llmClient;

    private AdrSupersedeChecker newChecker() {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(cfg, "modelName", "mock-code-assistant");
        ReflectionTestUtils.setField(cfg, "timeoutMs", 15000);
        return new AdrSupersedeChecker(llmClient, cfg);
    }

    // ── Test 1: SUPERSEDES parsed ─────────────────────────────────────────────

    @Test
    void check_verdictSupersedes_returnsSupersedes_degradedFalse() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("VERDICT: SUPERSEDES\nRATIONALE: New decision directly replaces the old caching approach.")
                .success(true)
                .build());

        AdrSupersedeChecker.Verdict verdict = newChecker().check(
                "Use Redis for caching", "We will use Redis as cache layer",
                "Use in-memory map for caching", "We will use a ConcurrentHashMap as cache");

        Assertions.assertTrue(verdict.supersedes(), "SUPERSEDES verdict should set supersedes=true");
        Assertions.assertFalse(verdict.degraded(), "Successful parse should not be degraded");
        Assertions.assertEquals("New decision directly replaces the old caching approach.", verdict.rationale());
    }

    // ── Test 2: INDEPENDENT parsed ────────────────────────────────────────────

    @Test
    void check_verdictIndependent_returnsIndependent_degradedFalse() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("VERDICT: INDEPENDENT\nRATIONALE: These decisions address different system concerns.")
                .success(true)
                .build());

        AdrSupersedeChecker.Verdict verdict = newChecker().check(
                "Use Kafka for async messaging", "We will use Kafka for event streaming",
                "Use PostgreSQL for persistence", "We will use PostgreSQL as primary store");

        Assertions.assertFalse(verdict.supersedes(), "INDEPENDENT verdict should set supersedes=false");
        Assertions.assertFalse(verdict.degraded(), "Successful parse should not be degraded");
        Assertions.assertEquals("These decisions address different system concerns.", verdict.rationale());
    }

    // ── Test 3: null response → fail-safe ─────────────────────────────────────

    @Test
    void check_llmReturnsNull_independentDegraded() {
        when(llmClient.generate(any())).thenReturn(null);

        AdrSupersedeChecker.Verdict verdict = newChecker().check(
                "New title", "New decision", "Old title", "Old decision");

        Assertions.assertFalse(verdict.supersedes(), "Null LLM response should default to INDEPENDENT");
        Assertions.assertTrue(verdict.degraded(), "Null LLM response should mark degraded=true");
    }

    // ── Test 4: LLM throws → fail-safe, no propagation ───────────────────────

    @Test
    void check_llmThrows_independentDegraded_noExceptionPropagates() {
        when(llmClient.generate(any())).thenThrow(new RuntimeException("LLM service unavailable"));

        AdrSupersedeChecker.Verdict verdict = Assertions.assertDoesNotThrow(
                () -> newChecker().check("New title", "New decision", "Old title", "Old decision"),
                "Exception from LLM must not propagate");

        Assertions.assertFalse(verdict.supersedes(), "Exception from LLM should default to INDEPENDENT");
        Assertions.assertTrue(verdict.degraded(), "Exception from LLM should mark degraded=true");
    }

    // ── Test 5: unparseable output → fail-safe ────────────────────────────────

    @Test
    void check_unparseableOutput_independentDegraded() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("I cannot determine the relationship between these architecture decisions.")
                .success(true)
                .build());

        AdrSupersedeChecker.Verdict verdict = newChecker().check(
                "New title", "New decision", "Old title", "Old decision");

        Assertions.assertFalse(verdict.supersedes(), "Unparseable output should default to INDEPENDENT");
        Assertions.assertTrue(verdict.degraded(), "Unparseable output should mark degraded=true");
    }
}
