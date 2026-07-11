package com.repolens.hooks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class HookRegistry {

    private final List<BuiltinHook> hooks = new ArrayList<>();

    public HookRegistry(List<BuiltinHook> builtinHooks) {
        this.hooks.addAll(builtinHooks);
    }

    public List<BuiltinHook> getHooks() {
        return List.copyOf(hooks);
    }

    public void register(BuiltinHook hook) {
        hooks.add(hook);
    }
}
