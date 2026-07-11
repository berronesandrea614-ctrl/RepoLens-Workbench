package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("code_file")
public class CodeFileEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private String filePath;

    private String fileType;

    private String contentHash;

    private Integer lineCount;

    private String lastCommitId;
}
