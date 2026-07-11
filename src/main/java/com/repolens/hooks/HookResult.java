package com.repolens.hooks;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HookResult {
    @Builder.Default
    private boolean continueFlow = true;
    private String decision;
    private String reason;
    private Object updatedInput;
    private String updatedToolOutput;
    private String additionalContext;

    public static HookResult allow() {
        return HookResult.builder().continueFlow(true).decision("allow").build();
    }

    public static HookResult block(String reason) {
        return HookResult.builder().continueFlow(false).decision("block").reason(reason).build();
    }
}
