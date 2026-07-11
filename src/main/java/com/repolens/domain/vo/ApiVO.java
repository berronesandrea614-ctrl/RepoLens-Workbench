package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ApiVO {

    private Long id;
    private String apiPath;
    private String httpMethod;
    private String className;
    private String methodName;
    private Integer startLine;
    private Integer endLine;
}
