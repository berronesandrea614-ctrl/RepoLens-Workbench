package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("verification_run")
public class VerificationRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long repoId;
    private Long sessionId;
    private String kind;
    private Integer exitCode;
    private Boolean passed;
    private String outputTail;
    private String failuresJson;
    private Boolean networkIsolated;
    private Boolean oracleTampered;
    private LocalDateTime createdAt;
}
