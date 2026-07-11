package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 需求关联的代码位点。每条把某需求指向一个具体的符号 / 文件位置。
 * Feature C: 扩展了 link_type / confidence / status / updated_at 四列。
 */
@Data
@TableName("requirement_symbol")
public class RequirementSymbolEntity {

    public static final String LINK_TYPE_DECLARED  = "DECLARED";
    public static final String LINK_TYPE_RAG        = "RAG";
    public static final String LINK_TYPE_CALLGRAPH  = "CALLGRAPH";
    public static final String LINK_TYPE_MANUAL     = "MANUAL";

    public static final String STATUS_LINKED  = "LINKED";
    public static final String STATUS_STALE   = "STALE";
    public static final String STATUS_BROKEN  = "BROKEN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long requirementId;

    /** 关联符号 id，可能为空（仅定位到文件行）。 */
    private Long symbolId;

    private String filePath;

    private Integer startLine;

    /** 链接来源类型：DECLARED / RAG / CALLGRAPH / MANUAL。 */
    private String linkType;

    /** 置信度：DECLARED=1.0, RAG=0.5–0.8, CALLGRAPH×0.7衰减, MANUAL=1.0。 */
    private Double confidence;

    /** 链接状态：LINKED / STALE / BROKEN。 */
    private String status;

    private LocalDateTime updatedAt;
}
