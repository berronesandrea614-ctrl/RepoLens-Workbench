package com.repolens.service.impl.support;

import com.repolens.domain.entity.AgentMemoryEntity;
import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 记忆协调器（mem0 范式）。
 *
 * 当新记忆与现有相似记忆（相似度 >= 0.6）冲突时，调一次 LLM 判定应如何处理：
 * ADD / UPDATE / DELETE / NOOP。
 *
 * 输出格式（严格单行）：
 *   ACTION: ADD
 *   ACTION: UPDATE|<targetMemoryId>
 *   ACTION: DELETE|<targetMemoryId>
 *   ACTION: NOOP
 *
 * 任何 LLM 调用失败或解析失败都回退为 ADD（fail-safe）。
 */
@Slf4j
@Component
public class MemoryReconciler {

    /** Reconcile 动作枚举。 */
    public enum ReconcileAction {
        ADD, UPDATE, DELETE, NOOP
    }

    /**
     * Reconcile 结果：动作 + 可选目标记忆 ID（UPDATE / DELETE 时非 null）+ 是否为降级回退。
     * {@code isFallback=true} 表示 LLM 调用失败/解析失败，调用方应使用 Jaccard 去重而非跳过。
     */
    public record ReconcileResult(ReconcileAction action, Long targetMemoryId, boolean isFallback) {
        /** LLM 明确指示的动作（非降级）。 */
        public static ReconcileResult of(ReconcileAction action, Long targetId) {
            return new ReconcileResult(action, targetId, false);
        }
        /** LLM 不可用/解析失败 → 降级回退（调用方保持 Jaccard 去重）。 */
        public static ReconcileResult fallback() {
            return new ReconcileResult(ReconcileAction.ADD, null, true);
        }
    }

    /** 待协调的候选：现有实体 + 相似度得分。 */
    public record ScoredMemory(AgentMemoryEntity entity, double similarity) {
    }

    private static final String SYSTEM_PROMPT =
            "你是记忆协调器。给定一条新记忆和若干相似的旧记忆候选，判断应如何处理。\n"
            + "只能回复以下四种格式之一（不输出任何多余内容）：\n"
            + "ACTION: ADD                   # 新记忆与已有记忆不冲突，直接新增\n"
            + "ACTION: UPDATE|<旧记忆ID>      # 新记忆是旧记忆的更新/修正，用新内容替换旧记忆\n"
            + "ACTION: DELETE|<旧记忆ID>      # 新记忆与旧记忆矛盾，删除旧记忆并新增\n"
            + "ACTION: NOOP                  # 新记忆与旧记忆重复，丢弃新记忆\n";

    private final LlmClient llmClient;
    private final LlmRuntimeConfig llmRuntimeConfig;

    public MemoryReconciler(LlmClient llmClient, LlmRuntimeConfig llmRuntimeConfig) {
        this.llmClient = llmClient;
        this.llmRuntimeConfig = llmRuntimeConfig;
    }

    /**
     * 调用 LLM 判定 reconcile 动作。解析失败或异常返回 ADD（fail-safe）。
     *
     * @param newNote    新抽取的记忆笔记
     * @param candidates 相似度最高的若干旧记忆（最多展示 top-3）
     * @return reconcile 结果
     */
    public ReconcileResult reconcile(MemoryExtractor.MemoryNote newNote, List<ScoredMemory> candidates) {
        try {
            String userPrompt = buildPrompt(newNote, candidates);
            LlmResponse response = llmClient.generate(LlmRequest.builder()
                    .modelName(llmRuntimeConfig.getModelName())
                    .systemPrompt(SYSTEM_PROMPT)
                    .userPrompt(userPrompt)
                    .temperature(0.0d)
                    .timeoutMs(llmRuntimeConfig.getTimeoutMs())
                    .build());
            if (response == null || !StringUtils.hasText(response.getContent())) {
                log.warn("MemoryReconciler LLM returned empty, fallback to ADD");
                return ReconcileResult.fallback();
            }
            return parse(response.getContent().trim());
        } catch (Exception ex) {
            log.warn("MemoryReconciler failed, fallback to ADD, err={}", ex.getMessage());
            return ReconcileResult.fallback();
        }
    }

    private String buildPrompt(MemoryExtractor.MemoryNote newNote, List<ScoredMemory> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("新记忆：\n");
        sb.append("  内容：").append(newNote.content()).append("\n");
        sb.append("  类型：").append(newNote.memoryType()).append("\n");
        sb.append("  重要性：").append(newNote.importance()).append("\n\n");

        sb.append("相似旧记忆候选（最多 3 条）：\n");
        int limit = Math.min(3, candidates.size());
        for (int i = 0; i < limit; i++) {
            ScoredMemory sm = candidates.get(i);
            AgentMemoryEntity e = sm.entity();
            sb.append("  [").append(i + 1).append("] ID=").append(e.getId())
                    .append(", 相似度=").append(String.format("%.2f", sm.similarity()))
                    .append("\n");
            sb.append("      内容：").append(e.getContent()).append("\n");
        }
        sb.append("\n请判断应如何处理这条新记忆：");
        return sb.toString();
    }

    /**
     * 解析 LLM 输出。格式：ACTION: ADD | ACTION: UPDATE|id | ACTION: DELETE|id | ACTION: NOOP。
     * 解析失败回退 ADD。
     */
    private ReconcileResult parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return ReconcileResult.fallback();
        }
        // 找 ACTION: 前缀
        int idx = raw.indexOf("ACTION:");
        if (idx < 0) {
            return ReconcileResult.fallback();
        }
        String actionPart = raw.substring(idx + "ACTION:".length()).trim();
        // 只取首行
        int nl = actionPart.indexOf('\n');
        if (nl >= 0) {
            actionPart = actionPart.substring(0, nl).trim();
        }

        if (actionPart.startsWith("NOOP")) {
            return ReconcileResult.of(ReconcileAction.NOOP, null);
        }
        if (actionPart.startsWith("ADD")) {
            return ReconcileResult.of(ReconcileAction.ADD, null);
        }
        if (actionPart.startsWith("UPDATE")) {
            Long id = extractId(actionPart, "UPDATE");
            return ReconcileResult.of(ReconcileAction.UPDATE, id);
        }
        if (actionPart.startsWith("DELETE")) {
            Long id = extractId(actionPart, "DELETE");
            return ReconcileResult.of(ReconcileAction.DELETE, id);
        }
        return ReconcileResult.fallback();
    }

    private Long extractId(String actionPart, String prefix) {
        // 格式：UPDATE|<id> 或 DELETE|<id>
        int pipe = actionPart.indexOf('|');
        if (pipe < 0) {
            return null;
        }
        String idStr = actionPart.substring(pipe + 1).trim();
        try {
            return Long.parseLong(idStr.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ex) {
            log.warn("MemoryReconciler cannot parse target ID from '{}', action={}", actionPart, prefix);
            return null;
        }
    }
}
