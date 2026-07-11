package com.repolens.kernel.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次验证运行结果（表 {@code rk_verification_run}）。
 *
 * <p>相较旧版补 {@code shadowId}——证明"验的是自己的改动"（旧版无此关联，无法自证）。
 * {@code networkIsolated} 标记本次是否断网执行，防 reward hacking（模型联网取巧造"通过"假象）。
 */
@Data
@TableName("rk_verification_run")
public class RkVerificationRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long sessionId;

    private Long runId;

    /** 验证的是哪个影子区。 */
    private Long shadowId;

    /** 工作目录性质：SHADOW / REPO（回退真目录时标注）。 */
    private String workDir;

    /** 构建体系：maven / gradle / npm / python / go / rust。 */
    private String buildTarget;

    /** 验证类型：COMPILE / TEST / LINT。 */
    private String kind;

    private Integer exitCode;

    private Boolean passed;

    /** 输出尾部（截断保留），全文见日志/磁盘。 */
    private String outputTail;

    /** 结构化失败列表 JSON（喂回模型自愈）。 */
    private String failuresJson;

    /** 是否断网隔离执行。 */
    private Boolean networkIsolated;

    /** oracle 文件是否被篡改（防作弊）。 */
    private Boolean oracleTampered;

    private LocalDateTime createdAt;
}
