package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ContextBudgetVO {
    private int windowTokens;
    private int usedTokens;
    private double usedPercent;
    private List<String> actions;
    private List<BlockInfo> blocks;

    @Data
    @Builder
    public static class BlockInfo {
        private String id;
        private String kind;
        private int tokens;
        private String state;
        private boolean pinned;
        private String preview;
    }
}
