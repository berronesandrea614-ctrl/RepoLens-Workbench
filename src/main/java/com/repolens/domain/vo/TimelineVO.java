package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Feature J: 架构时间轴 VO。
 * frames 按 agent_run.created_at 升序排列，每帧对应一次 agent run。
 * historyLimited 恒为 true，标注"仅从有 agent_run 记录起统计"。
 */
@Data
@Builder
public class TimelineVO {
    private List<FrameVO> frames;
    private int frameCount;
    /** 恒为 true，说明时间轴数据仅覆盖有 agent_run 记录的区间。 */
    private boolean historyLimited;
}
