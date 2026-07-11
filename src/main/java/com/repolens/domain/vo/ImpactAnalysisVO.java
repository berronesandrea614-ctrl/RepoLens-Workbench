package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ImpactAnalysisVO {

    private String className;
    private String methodName;
    private Integer affectedMethodCount;
    private Integer affectedApiCount;
    private List<ImpactItemVO> impacts;
}
