package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限规则表：deny > ask > allow 三表体系。
 * 规则按 category（READ/WRITE/CREATE/DELETE/EXEC/NETWORK）分组，
 * 按 rule_order 升序首匹配胜。
 */
@Data
@TableName("permission_rule")
public class PermissionRuleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** READ / WRITE / CREATE / DELETE / EXEC / NETWORK */
    private String category;

    /** glob 或 host 匹配模式，null 或 * 表示全匹配 */
    private String pattern;

    /** allow / ask / deny */
    private String decision;

    /** 优先级：值越小越先匹配 */
    private Integer ruleOrder;

    /** system / session / project / user */
    private String source;

    private LocalDateTime createdAt;
}
