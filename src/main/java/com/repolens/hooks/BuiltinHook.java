package com.repolens.hooks;

import java.util.Set;

public interface BuiltinHook {
    String name();
    Set<String> lifecycles();
    boolean matches(String toolName);
    HookResult execute(HookContext ctx);
}
