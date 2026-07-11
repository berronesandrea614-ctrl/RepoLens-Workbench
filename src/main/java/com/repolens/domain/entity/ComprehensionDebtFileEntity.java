package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 理解债务物化表（comprehension_debt_file）。
 * 每 (repo_id, file_id) 一行，存七信号快照与综合评分。
 * 由 ComprehensionDebtService 异步物化，GET 接口只读此表（毫秒级）。
 * stale=1 表示数据过期，下次 GET 会触发重算。
 */
@Data
@TableName("comprehension_debt_file")
public class ComprehensionDebtFileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    /** 关联 code_file.id。 */
    private Long fileId;

    private String filePath;

    /** 综合理解债务分 0–100。 */
    private Integer score;

    /**
     * 分档：RED（≥70）/ YELLOW（40–69）/ GREEN（<40）。
     * 字段名用 debtBand 而非 band——band 是 MyBatis OGNL 的保留字（按位与），
     * 作属性名会让自动生成的 &lt;if test="band != null"&gt; 解析崩溃；列名仍映射 band。
     */
    @com.baomidou.mybatisplus.annotation.TableField("band")
    private String debtBand;

    /** S1 归一化值 [0,1]。 */
    private Double s1AiRatio;

    /** S2 归一化值 [0,1]。 */
    private Double s2Unreviewed;

    /** S3 归一化值 [0,1]。 */
    private Double s3NoRationale;

    /** S4 归一化值 [0,1]。 */
    private Double s4Complexity;

    /** S5 归一化值 [0,1]。 */
    private Double s5Churn;

    /** S6 归一化值 [0,1]；MVP 固定 0.5（降级）。 */
    private Double s6Recency;

    /** S7 归一化值 [0,1]。 */
    private Double s7Coverage;

    /** 乘性放大因子（1.0 或 1.5）。 */
    private Double ampFactor;

    /** 各信号原始数据快照（JSON）。 */
    private String signalsJson;

    /** 1 = 有降级信号（无 git/无复杂度/无测试）。 */
    private Integer degraded;

    /** 1 = 数据已过期，下次 GET 需重算。 */
    private Integer stale;

    /** 最近一次计算时间。 */
    private LocalDateTime computedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
