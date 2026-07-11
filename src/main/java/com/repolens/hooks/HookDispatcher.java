package com.repolens.hooks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HookDispatcher {

    private final HookRegistry registry;

    public HookResult dispatchPreToolUse(HookContext ctx) {
        return dispatch("PreToolUse", ctx);
    }

    public HookResult dispatchPostToolUse(HookContext ctx) {
        return dispatch("PostToolUse", ctx);
    }

    private HookResult dispatch(String lifecycle, HookContext ctx) {
        for (BuiltinHook hook : registry.getHooks()) {
            if (!hook.lifecycles().contains(lifecycle)) {
                continue;
            }
            if (!hook.matches(ctx.getToolName())) {
                continue;
            }
            try {
                HookResult result = hook.execute(ctx);
                if (result == null) {
                    continue;
                }
                if (!result.isContinueFlow()) {
                    log.info("Hook {} blocked tool {}: {}", hook.name(), ctx.getToolName(), result.getReason());
                    return result;
                }
            } catch (Exception e) {
                log.warn("Hook {} threw exception (fail-open): {}", hook.name(), e.getMessage());
            }
        }
        return HookResult.allow();
    }
}
