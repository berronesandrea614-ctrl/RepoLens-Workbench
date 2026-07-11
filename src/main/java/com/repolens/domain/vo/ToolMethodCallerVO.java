package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolMethodCallerVO {

    private Long sourceSymbolId;
    private String className;
    private String methodName;
    private String signature;
    private Integer startLine;
    private Integer endLine;
}
