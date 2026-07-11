package com.repolens.service.impl.support;

import com.repolens.domain.entity.AgentMemoryEntity;
import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MemoryReconciler 单测（mock LlmClient，无真实 LLM 调用）。验证：
 * (a) LLM 返回 "ACTION: ADD"   → ADD, isFallback=false
 * (b) LLM 返回 "ACTION: UPDATE|123" → UPDATE, targetId=123, isFallback=false
 * (c) LLM 返回 "ACTION: DELETE|456" → DELETE, targetId=456, isFallback=false
 * (d) LLM 返回 "ACTION: NOOP"  → NOOP, isFallback=false
 * (e) LLM 返回乱码             → ADD, isFallback=true（fallback）
 * (f) LLM 抛出异常             → ADD, isFallback=true
 * (g) LLM 返回空串             → ADD, isFallback=true
 */
@ExtendWith(MockitoExtension.class)
class MemoryReconcilerTest {

    @Mock
    private LlmClient llmClient;

    private MemoryReconciler newReconciler() {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(cfg, "modelName", "mock-model");
        ReflectionTestUtils.setField(cfg, "timeoutMs", 5000);
        return new MemoryReconciler(llmClient, cfg);
    }

    private MemoryExtractor.MemoryNote newNote(String content) {
        return new MemoryExtractor.MemoryNote(content, "kw", "FACT", 3);
    }

    private List<MemoryReconciler.ScoredMemory> candidates(long id, String content) {
        AgentMemoryEntity e = new AgentMemoryEntity();
        e.setId(id);
        e.setContent(content);
        return List.of(new MemoryReconciler.ScoredMemory(e, 0.85));
    }

    // ── (a) ADD ──────────────────────────────────────────────────────────────

    @Test
    void reconcile_add_parsedCorrectly() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("ACTION: ADD")
                .success(true)
                .build());

        MemoryReconciler.ReconcileResult r =
                newReconciler().reconcile(newNote("new fact"), candidates(1L, "old fact"));

        Assertions.assertEquals(MemoryReconciler.ReconcileAction.ADD, r.action());
        Assertions.assertFalse(r.isFallback(), "LLM-confirmed ADD must not be flagged as fallback");
        Assertions.assertNull(r.targetMemoryId());
    }

    // ── (b) UPDATE|id ────────────────────────────────────────────────────────

    @Test
    void reconcile_update_parsedWithTargetId() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("ACTION: UPDATE|123")
                .success(true)
                .build());

        MemoryReconciler.ReconcileResult r =
                newReconciler().reconcile(newNote("updated fact"), candidates(123L, "old fact"));

        Assertions.assertEquals(MemoryReconciler.ReconcileAction.UPDATE, r.action());
        Assertions.assertFalse(r.isFallback());
        Assertions.assertEquals(123L, r.targetMemoryId());
    }

    // ── (c) DELETE|id ────────────────────────────────────────────────────────

    @Test
    void reconcile_delete_parsedWithTargetId() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("ACTION: DELETE|456")
                .success(true)
                .build());

        MemoryReconciler.ReconcileResult r =
                newReconciler().reconcile(newNote("conflicting fact"), candidates(456L, "old fact"));

        Assertions.assertEquals(MemoryReconciler.ReconcileAction.DELETE, r.action());
        Assertions.assertFalse(r.isFallback());
        Assertions.assertEquals(456L, r.targetMemoryId());
    }

    // ── (d) NOOP ─────────────────────────────────────────────────────────────

    @Test
    void reconcile_noop_parsedCorrectly() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("ACTION: NOOP")
                .success(true)
                .build());

        MemoryReconciler.ReconcileResult r =
                newReconciler().reconcile(newNote("duplicate fact"), candidates(1L, "same fact"));

        Assertions.assertEquals(MemoryReconciler.ReconcileAction.NOOP, r.action());
        Assertions.assertFalse(r.isFallback());
        Assertions.assertNull(r.targetMemoryId());
    }

    // ── (e) malformed output ──────────────────────────────────────────────────

    @Test
    void reconcile_malformedOutput_returnsFallback() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("I cannot determine the action at this time.")
                .success(true)
                .build());

        MemoryReconciler.ReconcileResult r =
                newReconciler().reconcile(newNote("new fact"), candidates(1L, "old fact"));

        Assertions.assertTrue(r.isFallback(),
                "Malformed output must return fallback=true so caller uses Jaccard dedup");
        // Fallback action is ADD so callers can use it safely without null-checking
        Assertions.assertEquals(MemoryReconciler.ReconcileAction.ADD, r.action());
    }

    // ── (f) LLM throws ───────────────────────────────────────────────────────

    @Test
    void reconcile_llmThrows_returnsFallback() {
        when(llmClient.generate(any())).thenThrow(new RuntimeException("timeout"));

        MemoryReconciler.ReconcileResult r =
                newReconciler().reconcile(newNote("new fact"), candidates(1L, "old fact"));

        Assertions.assertTrue(r.isFallback(), "LLM exception must return fallback, not propagate");
    }

    // ── (g) empty LLM response ───────────────────────────────────────────────

    @Test
    void reconcile_emptyLlmResponse_returnsFallback() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("")
                .success(true)
                .build());

        MemoryReconciler.ReconcileResult r =
                newReconciler().reconcile(newNote("new fact"), candidates(1L, "old fact"));

        Assertions.assertTrue(r.isFallback(), "Empty LLM response must return fallback");
    }
}
