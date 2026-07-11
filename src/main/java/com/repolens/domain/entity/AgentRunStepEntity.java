package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * agent 执行轨迹中的一步（trace 的节点）。
 * 每步对应模型的一次"思考 → 工具 → 观察"，type 区分 THINK/TOOL/WRITE，
 * target_files 记录本步触达的文件，供前端把每一步链接回它改动的代码/diff。
 */
@Data
@TableName("agent_run_step")
public class AgentRunStepEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    /** 第几步，从 1 开始。 */
    private Integer stepIndex;

    /** 步类型：THINK（纯思考）/ TOOL（读工具）/ WRITE（写文件）。 */
    private String type;

    private String toolName;

    private String toolArgs;

    private String thought;

    private String observation;

    /** 本步触达的文件路径，逗号分隔（尽力解析，可空）。 */
    private String targetFiles;

    private String status;

    private LocalDateTime createdAt;

    private String permissionVerdict;
    private String applyStrategy;
    private String riskLevel;
}
