package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 计划偏差摘要 VO（H Mission Control P1）。
 * 从 ReconciliationVO 提取关键字段，供泳道面板快速展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviationVO {

    /** 是否有结构化计划 */
    private boolean planned;

    /** 计划落实率（0-100，来自 ReconciliationVO.Summary.coverage × 100 取整） */
    private Integer coverage;

    /** 综合信任标志：OK / SUSPECT / FABRICATED */
    private String trustFlag;

    /** 未落实的计划项数量（status 以 MISSING_ 开头的 items） */
    private int missingCount;

    /** 计划外改动数（OVER_SCOPE + SILENT_ADD） */
    private int offPlanCount;
}
