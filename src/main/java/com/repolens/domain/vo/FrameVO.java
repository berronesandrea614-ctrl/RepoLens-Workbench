package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Feature J: 时间轴单帧 VO。
 * 每帧对应一次 agent_run，记录该次运行触碰的文件路径与符号数。
 */
@Data
@Builder
public class FrameVO {
    /** 帧序号（0-based，按 agent_run.created_at 升序）。 */
    private int frameIndex;
    /** 对应的 agent_run.id。 */
    private Long agentRunId;
    /** 对应的 agent_run.session_id。 */
    private Long sessionId;
    /** agent_run.created_at 的字符串表示（ISO-8601）。 */
    private String createdAt;
    /** 本帧触碰的文件路径列表（distinct，状态为 APPLIED 或 PROPOSED）。 */
    private List<String> changedFilePaths;
    /** 本帧触碰的文件数（= changedFilePaths.size()）。 */
    private int changedFileCount;
    /** 本帧触碰文件对应的代码符号数。 */
    private int touchedSymbolCount;
}
