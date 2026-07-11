package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("code_dependency")
public class CodeDependencyEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long sourceSymbolId;

    private String targetSymbolName;

    private String relationType;

    private BigDecimal confidence;
}
