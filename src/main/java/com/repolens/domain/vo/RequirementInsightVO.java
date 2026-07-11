package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 需求意图可视化聚合 VO（GET /api/repos/{repoId}/requirements/{reqId}/insight）。
 *
 * <p>包含 4 层信息：
 * <ol>
 *   <li>意图条（intent + approach）</li>
 *   <li>概览 chips（filesChanged / added / modified / 计划步落地比 / offPlanCount）</li>
 *   <li>思路步骤（steps：每步 title/why/kind/riskNote/insight/toolReads/flow）</li>
 *   <li>底部验收（footer）+ 分层全景（panorama，标签 B）</li>
 * </ol>
 *
 * <p>降级策略：
 * <ul>
 *   <li>纯问答（hasChanges=false）：steps 只有「AI 的回答依据」一步，无 deviation/panorama。</li>
 *   <li>无结构化计划（planned=false）：steps 只有「改动概览」一步，无 why/deviation。</li>
 *   <li>两种都满足时纯问答优先。</li>
 * </ul>
 */
@Data
@Builder
public class RequirementInsightVO {

    /** 需求标题/意图，来自 requirement.title。 */
    private String intent;

    /** AI 整体思路，来自 requirement.approach 或 agent_run_plan.approach（可空）。 */
    private String approach;

    /** 是否存在结构化计划（agent_run_plan 有效记录）。 */
    private boolean planned;

    /** 是否存在代码改动（file_change_log APPLIED/PROPOSED 有效记录）。 */
    private boolean hasChanges;

    /** 改动概览 chips（数字徽标行）。 */
    private Chips chips;

    /** 计划 vs 实际偏差（只有 planned=true 且有实际偏差时非 null）。 */
    private Deviation deviation;

    /** 思路步骤列表（有计划=结构化步骤；无计划=单步概览；纯问答=单步依据）。 */
    private List<InsightStep> steps;

    /** 底部验收与影响面摘要。 */
    private InsightFooter footer;

    /** 数据流全景（标签 B），仅 hasChanges=true 时非 null。 */
    private Panorama panorama;

    // ── 嵌套 VO ──────────────────────────────────────────────────────────────────

    /**
     * 改动概览数字 chips。
     */
    @Data
    @Builder
    public static class Chips {
        private int filesChanged;
        private int added;
        private int modified;
        private int plannedStepsDone;
        private int plannedStepsTotal;
        private int offPlanCount;
    }

    /**
     * 计划 vs 实际偏差信息（计划外改动）。
     */
    @Data
    @Builder
    public static class Deviation {
        /** 计划外改动的文件路径列表。 */
        private List<String> files;
        /** 人类可读说明（"AI 声明只改 X 处，实际多动了 Y"）。 */
        private String note;
    }

    /**
     * 一个思路步骤。
     * flow 列表元素为 {@link FlowNodeVO} 或 {@link FlowEdgeVO}（通过 nodeType 字段区分）。
     */
    @Data
    @Builder
    public static class InsightStep {
        /** 步骤序号（从 0 开始）。 */
        private int index;
        /** 步骤标题。 */
        private String title;
        /** 为什么这么做（来自 plan step.why，无计划时为 null）。 */
        private String why;
        /** 步骤分类：in=计划内正常，risk=命中敏感区，off=计划外改动。 */
        private String kind;
        /** 敏感区命中说明（"触及 X，建议复审"，仅 kind=risk 时非 null）。 */
        private String riskNote;
        /** AI 洞察（优先 plan step.insight，其次从 why 派生，可空）。 */
        private String insight;
        /** 决策依据：该步读了哪些文件/符号（来自 agent_run_step.target_files）。 */
        private List<String> toolReads;
        /**
         * 迷你数据流节点+边的混合列表。
         * 节点为 {@link FlowNodeVO}，边为 {@link FlowEdgeVO}，通过 nodeType 字段区分。
         */
        private List<Object> flow;
    }

    /**
     * 底部验收与影响面摘要。
     */
    @Data
    @Builder
    public static class InsightFooter {
        /** 计划步落地比例，格式 "X/Y"（可空）。 */
        private String plannedDone;
        /** 待确认的计划外改动数。 */
        private int offPlanPending;
        /** 影响面简述（"数据仅在 X 链路+Redis"之类，MVP 用固定模板生成）。 */
        private String impactNote;
    }

    /**
     * 数据流分层全景（标签 B），仅 hasChanges=true 时存在。
     */
    @Data
    @Builder
    public static class Panorama {
        /** 按层展示的数据流，每层对应 Controller/Service/Mapper/Entity 之一。 */
        private List<PanoramaLayer> layers;
    }

    /**
     * 全景图中的一层（如 "Service 层"）。
     */
    @Data
    @Builder
    public static class PanoramaLayer {
        /** 层名称（"Controller" / "Service" / "Mapper" / "Entity" / "Other"）。 */
        private String label;
        /**
         * 该层的节点+边序列（与 InsightStep.flow 结构相同）。
         * 触碰节点用真实 cls（new/mod/danger/offp），未触碰邻居 cls=dim。
         */
        private List<Object> flow;
    }
}
