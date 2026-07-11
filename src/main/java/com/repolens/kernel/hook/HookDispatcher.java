package com.repolens.kernel.hook;

import com.repolens.kernel.spi.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Hook 分发中枢（M7.1 确定性控制层）。Spring 自动注入所有 Hook bean，按三时机分类持有，
 * 供 loop / scheduler 在既定接入点调用。加一个 hook = 加一个实现对应 SPI 的 bean，无需改本类。
 *
 * <p>三时机接入点：
 * <ul>
 *   <li><b>UserPromptSubmit</b>：{@link com.repolens.kernel.loop.AgentLoopExecutor} 组装首条 user 消息前，
 *       链式改写 prompt；</li>
 *   <li><b>PreToolUse</b>：{@link com.repolens.kernel.loop.ToolTurnScheduler} 在<b>权限门放行后、dispatch 前</b>，
 *       可 BLOCK 或改参（第一个 BLOCK 短路）；</li>
 *   <li><b>PostToolUse</b>：dispatch 拿到结果后旁路观察（不改结果）。</li>
 * </ul>
 *
 * <p>fail-safe：单个 hook 抛异常不拖垮 loop——PreToolUse 异常按「放行原参」降级（护栏坏了不该反而阻断正常工作，
 * 真正的硬拦截仍有权限门兜底），PostToolUse / UserPromptSubmit 异常吞掉并记日志。
 * 用 {@link ObjectProvider} 懒取，避免与 loop / scheduler 的潜在构造期耦合。
 */
@Component("kernelHookDispatcher")
public class HookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HookDispatcher.class);

    private final ObjectProvider<List<PreToolUseHook>> preHooks;
    private final ObjectProvider<List<PostToolUseHook>> postHooks;
    private final ObjectProvider<List<UserPromptSubmitHook>> promptHooks;

    public HookDispatcher(ObjectProvider<List<PreToolUseHook>> preHooks,
                          ObjectProvider<List<PostToolUseHook>> postHooks,
                          ObjectProvider<List<UserPromptSubmitHook>> promptHooks) {
        this.preHooks = preHooks;
        this.postHooks = postHooks;
        this.promptHooks = promptHooks;
    }

    /**
     * 用户输入进 loop 前，依次跑所有 UserPromptSubmit hook（链式改写）。
     *
     * @return 改写后的 prompt（无 hook 或全不改写时原样返回）
     */
    public String runUserPromptSubmit(String prompt, ToolContext ctx) {
        String current = prompt;
        for (UserPromptSubmitHook h : promptHooks.getIfAvailable(List::of)) {
            try {
                String next = h.onUserPromptSubmit(current, ctx);
                if (next != null && !next.isBlank()) {
                    current = next;
                }
            } catch (Exception e) {
                log.warn("[hook] UserPromptSubmit hook {} 抛异常，忽略其改写", h.getClass().getSimpleName(), e);
            }
        }
        return current;
    }

    /**
     * 工具执行前（权限门已放行）跑所有 PreToolUse hook。第一个 BLOCK 短路；MODIFY 链式改参。
     *
     * @return 最终裁决：BLOCK（携理由）/ MODIFY（携合并后的参数）/ PROCEED
     */
    public HookDecision runPreToolUse(String toolName, Map<String, Object> args, ToolContext ctx) {
        Map<String, Object> current = args;
        boolean modified = false;
        for (PreToolUseHook h : preHooks.getIfAvailable(List::of)) {
            HookDecision d;
            try {
                d = h.beforeToolUse(toolName, current, ctx);
            } catch (Exception e) {
                // 护栏本身异常→降级放行原参（权限门/后续 hook 仍在，硬安全不依赖单个 hook 不崩）
                log.warn("[hook] PreToolUse hook {} 抛异常，按放行降级", h.getClass().getSimpleName(), e);
                continue;
            }
            if (d == null) {
                continue;
            }
            if (d.isBlock()) {
                log.info("[hook] PreToolUse hook {} 拦截工具 {}：{}",
                        h.getClass().getSimpleName(), toolName, d.reason());
                return d;
            }
            if (d.isModify()) {
                current = d.rewrittenArgs();
                modified = true;
                log.info("[hook] PreToolUse hook {} 改写了工具 {} 的参数",
                        h.getClass().getSimpleName(), toolName);
            }
        }
        return modified ? HookDecision.proceedWith(current) : HookDecision.proceed();
    }

    /**
     * 工具执行后跑所有 PostToolUse hook（旁路观察，不改结果）。
     */
    public void runPostToolUse(String toolName, Map<String, Object> args, String observation, ToolContext ctx) {
        for (PostToolUseHook h : postHooks.getIfAvailable(List::of)) {
            try {
                h.afterToolUse(toolName, args, observation, ctx);
            } catch (Exception e) {
                log.warn("[hook] PostToolUse hook {} 抛异常，忽略", h.getClass().getSimpleName(), e);
            }
        }
    }
}
