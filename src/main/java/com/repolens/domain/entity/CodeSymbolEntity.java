package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.repolens.domain.enums.SymbolType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("code_symbol")
public class CodeSymbolEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long fileId;

    /** 源语言：java（默认）/ typescript / javascript …；多语言解析写入，DB 默认 'java'。 */
    private String language;

    private SymbolType symbolType;

    private String className;

    private String methodName;

    private String signature;

    private String apiPath;

    private String httpMethod;

    private Integer startLine;

    private Integer endLine;

    private String summary;

    /** 圈复杂度（cyclomatic）：1+分支数，由 ComplexityVisitor 计算，METHOD 符号有值。 */
    private Integer cyclomatic;

    /** 认知复杂度（cognitive）：SonarSource 嵌套罚分规则，METHOD 符号有值。 */
    private Integer cognitive;
}
