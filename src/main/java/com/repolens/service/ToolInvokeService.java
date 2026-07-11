package com.repolens.service;

import java.util.Map;

public interface ToolInvokeService {

    /**
     * 执行一个工具（向后兼容版本，llmCallId=null）。
     *
     * @param sessionId 会话 id（可空）。仅写工具（writeFileContent）需要它来落 file_change_log；
     *                  只读工具忽略该参数。直连调试端点传 null（且会拒绝写工具）。
     */
    default Object invoke(Long userId, Long repoId, Long sessionId, String toolName, Map<String, Object> payload) {
        return invoke(userId, repoId, sessionId, toolName, payload, null);
    }

    /**
     * 执行一个工具（Feature F P1：携带 llmCallId 供写工具写入 file_change_log）。
     * 向后兼容版本：branchId=null，行为与原始签名完全等价。
     *
     * @param llmCallId 当前 LLM 调用的 llm_call_log.id（无则 null）；写工具会将其写入 file_change_log
     */
    default Object invoke(Long userId, Long repoId, Long sessionId, String toolName,
                          Map<String, Object> payload, Long llmCallId) {
        return invoke(userId, repoId, sessionId, toolName, payload, llmCallId, null);
    }

    /**
     * 执行一个工具（K 方案 P1：额外携带 branchId 供写工具落 file_change_log.branch_id）。
     * branchId=null 时与旧签名行为完全相同（向后兼容）。
     *
     * @param llmCallId 当前 LLM 调用的 llm_call_log.id（无则 null）
     * @param branchId  当前分支隔离 id（如 "v1"/"v2"）；null 表示主线，等价现有行为
     */
    Object invoke(Long userId, Long repoId, Long sessionId, String toolName,
                  Map<String, Object> payload, Long llmCallId, String branchId);
}
