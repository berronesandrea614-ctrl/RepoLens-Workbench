package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 迷你数据流图中的节点（富节点）。
 * nodeType 固定为 "node"，与 FlowEdgeVO 的 "edge" 共同作为前端多态判别字段。
 * cls 含义：new=新增文件, mod=已有文件改动, danger=敏感区命中, offp=计划外改动,
 *           ext=外部节点(Redis/DB等无代码对应), dim=未触碰灰化邻居, ""=普通.
 * 定位字段(symbolId/filePath/startLine/changeId)供前端点击跳转使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowNodeVO {

    /** 多态判别：固定 "node"。 */
    @Builder.Default
    private String nodeType = "node";

    /** 节点角色/分层，例如 "Controller" "Service" "Mapper" "Entity"。 */
    private String role;

    /** 组件名/类名（等宽字体展示）。 */
    private String name;

    /** 方法签名（精简版，用于展示）。 */
    private String sig;

    /** 对数据做了什么，一句话描述（优先来自 code_symbol.summary）。 */
    private String note;

    /** 改动行数标注，格式 "+N"（新增）或 "~N"（修改行数差），null=无改动。 */
    private String delta;

    /** 视觉分类：new / mod / danger / offp / ext / dim / ""。 */
    private String cls;

    /** 右上角角标，如 "+新增" "~改" "计划外·改"。 */
    private String tag;

    /** 代码符号 id，供前端跳转 Monaco 编辑器（可空）。 */
    private Long symbolId;

    /** 文件路径（相对项目根），供跳行高亮（可空）。 */
    private String filePath;

    /** 符号起始行（可空）。 */
    private Integer startLine;

    /** 符号结束行（可空）。 */
    private Integer endLine;

    /** file_change_log.id，供前端跳 diff 视图（可空）。 */
    private Long changeId;

    /** true=外部节点（Redis/DB/HTTP等无源码），点击不跳转（可空）。 */
    private Boolean external;
}
