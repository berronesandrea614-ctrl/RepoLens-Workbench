package com.repolens.domain.vo;

import com.repolens.domain.enums.SymbolType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SymbolVO {

    private Long id;
    private Long fileId;
    private SymbolType symbolType;
    private String className;
    private String methodName;
    private String signature;
    private Integer startLine;
    private Integer endLine;
    private String summary;
}
