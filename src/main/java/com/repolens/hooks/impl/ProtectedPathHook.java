package com.repolens.hooks.impl;

import com.repolens.hooks.BuiltinHook;
import com.repolens.hooks.HookContext;
import com.repolens.hooks.HookResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
public class ProtectedPathHook implements BuiltinHook {

    private static final Set<String> PROTECTED_PREFIXES = Set.of(
            ".repolens/", ".git/", "node_modules/", "target/", ".env", "secret");

    @Override
    public String name() {
        return "ProtectedPathHook";
    }

    @Override
    public Set<String> lifecycles() {
        return Set.of("PreToolUse");
    }

    @Override
    public boolean matches(String toolName) {
        return Set.of("writeFileContent", "editFileContent", "createFileContent",
                "deleteFile", "multiEditFile").contains(toolName);
    }

    @Override
    public HookResult execute(HookContext ctx) {
        String filePath = extractFilePath(ctx.getToolArgs());
        if (filePath == null) {
            return HookResult.allow();
        }
        String normalized = filePath.replace('\\', '/');
        for (String prefix : PROTECTED_PREFIXES) {
            if (normalized.startsWith(prefix) || normalized.contains("/" + prefix)) {
                log.warn("ProtectedPathHook blocked: tool={}, path={}", ctx.getToolName(), filePath);
                return HookResult.block("受保护路径不可写: " + filePath);
            }
        }
        return HookResult.allow();
    }

    private String extractFilePath(java.util.Map<String, Object> args) {
        if (args == null) return null;
        Object fp = args.get("filePath");
        return fp instanceof String s ? s : null;
    }
}
