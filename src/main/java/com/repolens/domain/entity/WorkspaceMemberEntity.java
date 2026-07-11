package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.repolens.domain.enums.WorkspaceRole;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workspace_member")
public class WorkspaceMemberEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    private Long userId;

    private WorkspaceRole role;
}
