package com.repolens.hooks;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class HookContext {
    private String lifecycle;
    private String toolName;
    private Map<String, Object> toolArgs;
    private String toolOutput;
    private Long userId;
    private Long repoId;
    private Long sessionId;
}
