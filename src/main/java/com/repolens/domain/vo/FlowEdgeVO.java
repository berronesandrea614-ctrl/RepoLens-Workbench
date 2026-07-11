package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 迷你数据流图中的边（带数据标签的连接线）。
 * nodeType 固定为 "edge"，与 FlowNodeVO 的 "node" 共同作为前端多态判别字段。
 * data  = 流过边的数据描述（如 "code+token"），MVP 中无法提取时降级为「调用」。
 * mut   = true 表示这条边上发生了数据变更（写操作），false 表示只读。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowEdgeVO {

    /** 多态判别：固定 "edge"。 */
    @Builder.Default
    private String nodeType = "edge";

    /** 边上流动的数据描述标签，MVP 降级为「调用」。 */
    private String data;

    /** 是否产生了数据变更（写操作）。 */
    private boolean mut;
}
