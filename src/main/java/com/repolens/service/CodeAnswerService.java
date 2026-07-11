package com.repolens.service;

import com.repolens.domain.dto.chat.CodeAnswerRequest;
import com.repolens.domain.vo.CodeAnswerVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface CodeAnswerService {

    CodeAnswerVO answer(Long repoId, Long userId, CodeAnswerRequest request);

    /**
     * 流式回答：复用与 {@link #answer} 完全一致的前置准备（权限、历史、落库、RAG、
     * 记忆召回、prompt 构建），随后把最终答案逐 token 通过 SseEmitter 推给前端。
     *
     * 事件约定：
     * - {@code meta}  一次，携带 references + modelName（JSON）；
     * - {@code steps} 可选，agent 模式携带 agentSteps；
     * - {@code token} 多次，携带 {"text": 增量文本}；
     * - {@code done}  一次，携带完整 CodeAnswerVO（最终答案/引用/指标）；
     * - {@code error} 失败时携带 {"message": ...}，随后 emitter 结束。
     *
     * 实现须在后台线程执行，异常时结束 emitter，绝不把请求线程挂住。
     */
    void answerStream(Long repoId, Long userId, CodeAnswerRequest request, SseEmitter emitter);

    /**
     * 批准 PLAN 模式产出的结构化计划，以 ACCEPT_EDITS 模式重新执行。
     * @param repoId 仓库 ID
     * @param userId 用户 ID
     * @param runId  PLAN 模式产出的 agent_run.id
     * @return 重新执行后的回答结果
     */
    CodeAnswerVO approvePlan(Long repoId, Long userId, Long runId);
}
