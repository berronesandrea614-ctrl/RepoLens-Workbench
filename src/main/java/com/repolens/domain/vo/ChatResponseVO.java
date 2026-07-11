package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChatResponseVO {

    private Long sessionId;
    private String answer;
    private List<CodeReferenceVO> references;
}
