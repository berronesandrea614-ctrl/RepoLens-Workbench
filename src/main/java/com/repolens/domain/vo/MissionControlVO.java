package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Mission Control 顶层视图 VO（H Mission Control P1）。
 * 聚合泳道列表、待审队列与整体摘要，供前端指挥中心页面消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MissionControlVO {

    /** 整体摘要统计 */
    private SummaryVO summary;

    /** agent 泳道列表（近 N 条 agent_run 组装） */
    private List<AgentLaneVO> lanes;

    /** 待审查的未确认风险队列 */
    private List<ReviewItemVO> reviewQueue;
}
