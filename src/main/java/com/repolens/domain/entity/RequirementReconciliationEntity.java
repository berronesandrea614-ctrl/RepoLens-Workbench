package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计划 vs 实际对账快照（requirement_reconciliation）。
 * 惰性计算：GET 时若无快照或已过时则计算并写入；POST recompute 强制重算。
 * ledger_json 存储完整 ReconciliationVO 序列化结果，前端直接消费。
 */
@Data
@TableName("requirement_reconciliation")
public class RequirementReconciliationEntity {

    /** OK：无偏差。SUSPECT：仅橙色信号。FABRICATED：存在🔴红色信号。 */
    public static final String TRUST_OK         = "OK";
    public static final String TRUST_SUSPECT    = "SUSPECT";
    public static final String TRUST_FABRICATED = "FABRICATED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的需求 ID。 */
    private Long requirementId;

    /** 对账时对应的 agent_run.id（无计划时可为 null）。 */
    private Long agentRunId;

    /** 计划落实率（landedFiles / declaredFiles），0.0~1.0。 */
    private BigDecimal coverage;

    /** 改动契合度（IN_PLAN 改动数 / 总改动数），0.0~1.0。 */
    private BigDecimal fidelity;

    /** 计划外改动数（OVER_SCOPE + SILENT_ADD）。 */
    private Integer offPlanCount;

    /** 综合信任标志：OK / SUSPECT / FABRICATED。 */
    private String trustFlag;

    /** 完整 ReconciliationVO JSON 快照（前端直接消费）。 */
    private String ledgerJson;

    /** 快照计算时间。 */
    private LocalDateTime computedAt;
}
