package com.repolens.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * K方案分支图顶层 VO，承载一个会话下全部候选方案节点。
 */
@Data
public class BranchGraphVO {

    /** 所属会话 ID。 */
    private Long sessionId;

    /** 触发多方案分析的原始问题文本。 */
    private String question;

    /** 本次分析产生的所有方案节点（v0/v1/v2/v3 等）。 */
    private List<BranchNodeVO> nodes;
}
