package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImpactItemVO {

    private String className;
    private String methodName;
    private String apiPath;
    private String httpMethod;
    private Float confidence;
    private String reason;
}
