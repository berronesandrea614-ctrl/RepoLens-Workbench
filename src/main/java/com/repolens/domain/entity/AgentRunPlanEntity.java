package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * agent_run_plan：每次 code 模式 agent run（且启用规划）产出的结构化计划。
 * approach = 一句话整体思路；plan_json = 完整 steps 数组 JSON。
 * 与 agent_run 一对一（UNIQUE KEY），落库时机与 agent_run 同批（失败安全）。
 */
@Data
@TableName("agent_run_plan")
public class AgentRunPlanEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的 agent_run.id，唯一索引确保一 run 最多一份计划。 */
    private Long agentRunId;

    /** AI 整体思路一句话（来自 AgentPlanner.StructuredPlan.approach）。 */
    private String approach;

    /** 完整结构化 steps 数组序列化 JSON（前端可直接反序列化）。 */
    private String planJson;

    /** PLAN 模式审批状态：AWAITING_APPROVAL / APPROVED / REJECTED。非 PLAN 模式落库时默认 APPROVED。 */
    private String planStatus;

    /** TodoWrite 动态清单序列化 JSON。 */
    private String todoJson;

    /** 计划版本号（每次 revisePlan 重写 +1）。 */
    private Integer planVersion;

    private LocalDateTime createdAt;
}
