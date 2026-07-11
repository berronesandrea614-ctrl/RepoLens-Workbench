package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.repolens.domain.enums.ChunkType;
import com.repolens.domain.enums.VectorStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("code_chunk")
public class CodeChunkEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String chunkId;

    private Long repoId;

    private Long fileId;

    private Long symbolId;

    private ChunkType chunkType;

    private String language;

    private String filePath;

    private Integer startLine;

    private Integer endLine;

    private String contentHash;

    private String content;

    private String summary;

    private VectorStatus vectorStatus;
}
