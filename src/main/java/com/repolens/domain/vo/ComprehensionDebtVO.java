package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 理解债务仪表盘主 VO。
 */
@Data
@Builder
public class ComprehensionDebtVO {

    /** 仓库 id。 */
    private Long repoId;

    /** 高危（RED, score≥70）文件数。 */
    private int redCount;

    /** 预警（YELLOW, 40–69）文件数。 */
    private int yellowCount;

    /** 健康（GREEN, <40）文件数。 */
    private int greenCount;

    /** 债务 Top 列表（按 score 降序，已过 minScore 过滤）。 */
    private List<DebtUnitVO> topDebt;

    /** true = 物化表中存在过期数据（stale），本次返回的是旧快照或重算后数据。 */
    private boolean stale;

    /** true = S6 等信号降级（无 git）。 */
    private boolean degraded;
}
