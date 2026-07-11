package com.repolens.service;

import com.repolens.domain.vo.FileExplanationVO;
import com.repolens.domain.vo.QuizQuestionVO;
import com.repolens.domain.vo.QuizResultVO;

import java.util.List;

/**
 * 理解债务偿还服务（A-P1 偿债闭环）。
 *
 * <p>主形态（帮助理解）：explainFile → 前端展示 LLM 生成的文件讲解 +
 * 相关文件/符号可点击跳转。用户读懂即偿债，不再考试式出题。
 *
 * <p>legacy（保留兼容，不再是主要形态）：generateQuiz / submitQuiz 选择题闭环。
 *
 * <p>失败安全：LLM 不可用时 explainFile / generateQuiz 返回降级结果；
 * submitQuiz 永远不抛异常。
 */
public interface ComprehensionQuizService {

    /**
     * 为指定文件生成「帮助理解」讲解（替代考试式出题）。
     *
     * <p>LLM 输出：①该文件在项目中的作用与职责；②与之直接相关的业务/调用链前因后果；
     * ③直接相关的其他文件/符号清单（含相对路径，供前端点击跳转）。
     *
     * <p>结果按 {repoId,fileId} 缓存（TTL），避免每次进页面都调 LLM。
     * LLM 失败时返回降级讲解（degraded=true），不抛异常。
     *
     * @param repoId 仓库 id
     * @param fileId code_file.id
     * @param userId 调用方（权限校验）
     * @return 文件讲解（含相关文件/符号跳转清单）
     */
    FileExplanationVO explainFile(Long repoId, Long fileId, Long userId);

    /**
     * 为指定文件生成 3–4 道理解测验题（多选一）。
     * LLM 失败时降级返回静态题目，标注 degraded。
     *
     * @param repoId 仓库 id
     * @param fileId code_file.id
     * @param userId 调用方（权限校验）
     * @return 题目列表（正确答案留在 server 端 session 缓存中）
     */
    List<QuizQuestionVO> generateQuiz(Long repoId, Long fileId, Long userId);

    /**
     * 提交作答并评分。
     * answers[i] = 第 i 题选择的 0-based 选项序号。
     * 若得分 ≥ 65% 则自动调用 markReviewed(QUIZZED) → debt stale → 下次 GET 重算。
     *
     * @param repoId  仓库 id
     * @param fileId  code_file.id
     * @param userId  调用方
     * @param answers 用户选答（顺序与 generateQuiz 返回一致）
     * @return 评分结果（含每题反馈 + 新债务分）
     */
    QuizResultVO submitQuiz(Long repoId, Long fileId, Long userId, List<Integer> answers);
}
