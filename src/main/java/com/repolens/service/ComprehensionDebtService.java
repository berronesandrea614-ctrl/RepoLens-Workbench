package com.repolens.service;

import com.repolens.domain.vo.ComprehensionDebtVO;
import com.repolens.domain.vo.RepayPathVO;

/**
 * 理解债务仪表盘服务。
 *
 * <p>评分算法（文件级）：七信号加权（S1-S7）+ 乘性放大，详见 DebtScoring。
 * 物化表 comprehension_debt_file 做缓存层，GET 接口只读（毫秒级）；
 * 表空或 stale 时在 GET 内触发重算（同步，快速）。
 *
 * <p>准入闸门：只对 S1>0（AI 碰过的文件）计算债务；纯人写文件不进榜。
 * minScore 参数（默认 40）过滤低风险文件，克制噪声。
 */
public interface ComprehensionDebtService {

    /**
     * 获取理解债务仪表盘数据（TOP 列表 + 各信号明细）。
     * 若物化表为空或 stale，会先同步计算再返回。
     *
     * @param repoId   仓库 id
     * @param userId   调用方（用于权限校验）
     * @param minScore 最低分过滤阈值（默认 40）
     */
    ComprehensionDebtVO getDashboard(Long repoId, Long userId, int minScore);

    /**
     * 获取单个文件的偿债路径（理由卡片 + 长期记忆 + Claude 测验占位）。
     *
     * @param repoId 仓库 id
     * @param fileId code_file.id
     * @param userId 调用方
     */
    RepayPathVO getRepayPath(Long repoId, Long fileId, Long userId);

    /**
     * 更新 file_change_log 复核字段，喂给 S2 信号，并把受影响文件的债务标为 stale。
     *
     * @param repoId     仓库 id
     * @param changeId   file_change_log.id
     * @param reviewType DIFF_VIEWED / ACCEPTED / QUIZZED
     * @param dwellMs    停留时长（毫秒，可空）
     * @param quizScore  测验得分（0–100，QUIZZED 时必填）
     * @param userId     调用方
     */
    void markReviewed(Long repoId, Long changeId, String reviewType,
                      Long dwellMs, Integer quizScore, Long userId);

    /**
     * 触发仓库全量重算（失败安全，异常静默吞掉，不影响调用方）。
     * 用于索引完成后的异步预热。
     *
     * @param repoId 仓库 id
     * @param userId 调用方（用于权限校验）
     */
    void materializeAsync(Long repoId, Long userId);

    /**
     * 把指定文件对应的债务行标为 stale（写盘后调用，驱动下次 GET 重算）。
     * 失败安全。
     *
     * @param repoId   仓库 id
     * @param filePath 文件路径
     */
    void markDebtStale(Long repoId, String filePath);

    /**
     * 同步重算仓库内所有 AI 触碰过的文件的债务分，并 upsert 到物化表。
     * 与 materializeAsync 不同，此方法同步执行（等待完成后再返回），
     * 供「重新计算债务」按钮调用。
     *
     * @param repoId 仓库 id
     * @param userId 调用方（用于权限校验）
     */
    void recompute(Long repoId, Long userId);
}
