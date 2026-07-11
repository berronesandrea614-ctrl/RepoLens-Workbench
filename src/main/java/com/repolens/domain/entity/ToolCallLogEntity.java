package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tool_call_log")
public class ToolCallLogEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long repoId;

    private Long sessionId;

    private String toolName;

    private String inputJson;

    private String outputJson;

    private Boolean success;

    private Long costMs;

    private String errorMsg;
}
