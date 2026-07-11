package com.repolens.service;

import com.repolens.domain.vo.AgentRunTraceVO;
import com.repolens.domain.vo.AgentRunVO;
import com.repolens.domain.vo.AgentStepVO;
import com.repolens.service.impl.support.AgentPlanner;

import java.util.List;

/**
 * Agent 执行记录（可回放 trace）读写层。
 * 写入侧供 CodeAnswerService 在每次 agent 问答后落库；读取侧供前端渲染 run 列表与 trace。
 * 读取方法均先做 repo 权限校验，并校验 run 归属 (repo)。
 */
public interface AgentRunService {

    /**
     * 落一条 agent_run + 每个 AgentStepVO 一条 agent_run_step，返回 runId。
     * 由调用方保证失败安全（本方法自身不吞异常，便于测试断言与上层统一 try/catch）。
     *
     * @param answer 完整答案文本（内部截前 500 字作为 answer_preview）
     * @param steps  agent 执行轨迹（可空/空）
     * @return 新建 run 的 id
     */
    Long record(Long repoId, Long sessionId, Long userId, String question, String mode,
                String answer, Integer iterations, Integer toolCalls, List<AgentStepVO> steps);

    /**
     * 落一条 agent_run + steps，同时（若 plan 非 null 且有结构化数据）落 agent_run_plan。
     * 失败安全由调用方负责；本方法自身不吞异常。
     *
     * @param plan 结构化计划（null 或 {@link AgentPlanner.StructuredPlan#hasStructure()} 为 false 时不落计划）
     * @return 新建 run 的 id
     */
    Long record(Long repoId, Long sessionId, Long userId, String question, String mode,
                String answer, Integer iterations, Integer toolCalls, List<AgentStepVO> steps,
                AgentPlanner.StructuredPlan plan);

    /**
     * 列出该 repo（可选 sessionId 过滤）下的 agent run，最新在前。权限校验 (user, repo)。
     */
    List<AgentRunVO> list(Long userId, Long repoId, Long sessionId);

    /**
     * 取某 run 的完整 trace（run 头 + 逐步）。权限校验 (user, repo) + 校验 run 归属该 repo。
     * run 不存在抛 NOT_FOUND，不属于该 repo 抛 FORBIDDEN。
     */
    AgentRunTraceVO trace(Long userId, Long repoId, Long runId);

    /**
     * 在 agent loop 开始前创建占位 agent_run（status=RUNNING），返回 runId。
     * 供 TodoWrite / Task 等控制工具在 loop 内关联使用。
     */
    Long begin(Long userId, Long repoId, Long sessionId, String permissionMode);

    /**
     * agent loop 结束后更新 agent_run 为最终状态。
     */
    void finish(Long runId, String finalAnswer, int toolTurns, long wallClockMs);
}
