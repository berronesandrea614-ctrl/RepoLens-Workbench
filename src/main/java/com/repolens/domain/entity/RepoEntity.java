package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.repolens.domain.enums.RepoIndexStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("repo")
public class RepoEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    private String repoName;

    private String repoUrl;

    private String branchName;

    private String latestCommitId;

    private RepoIndexStatus indexStatus;

    @TableField("created_by")
    private Long createdBy;
}
