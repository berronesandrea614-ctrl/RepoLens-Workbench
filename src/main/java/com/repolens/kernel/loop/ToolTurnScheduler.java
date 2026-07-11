package com.repolens.kernel.loop;

import com.repolens.kernel.hook.HookDecision;
import com.repolens.kernel.hook.HookDispatcher;
import com.repolens.kernel.perm.Decision;
import com.repolens.kernel.perm.KernelPermissionGate;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单轮工具调度：<b>只读并发 / 写串行</b>（规划 §3.1「写路径单线程」的执行体）。
 *
 * <p>一轮里 LLM 可能一次给出多个 tool_call。策略：
 * <ul>
 *   <li>只读工具（read/grep/glob）用线程池<b>并发</b>跑——无副作用，抢时间；</li>
 *   <li>写类工具（write/edit/multi_edit/bash 等）在只读跑完后<b>按原始顺序串行</b>跑——
 *       保证「唯一写文件的执行体」，杜绝并发写影子区互相踩踏；</li>
 *   <li>本轮内 dedup：同 {@code name+args} 的重复调用只跑一次、复用结果（省 token、防抖动）。</li>
 * </ul>
 * 返回结果按<b>输入顺序</b>对齐，供主循环原样回填 tool_result（顺序稳定利于前缀缓存）。
 */
@Component
public class ToolTurnScheduler {

    private static final Logger log = LoggerFactory.getLogger(ToolTurnScheduler.class);

    /**
     * 单个工具调用的执行结果，回填给 LLM 用。
     *
     * @param toolCallId LLM 工具调用 id
     * @param toolName   工具名
     * @param content    回填给 LLM 的 tool_result 文本（被权限拦截时为说明性 observation）
     * @param verdict    权限门裁决（M4 §3.7 外显；ALLOW 时也带风险档位供前端展示）
     */
    public record ToolResult(String toolCallId, String toolName, String content, Decision verdict) {
    }

    private final ToolRouter router;
    private final KernelPermissionGate permissionGate;
    private final HookDispatcher hookDispatcher;
    private final ExecutorService readPool;

    public ToolTurnScheduler(ToolRouter router, KernelPermissionGate permissionGate,
                             HookDispatcher hookDispatcher) {
        this.router = router;
        this.permissionGate = permissionGate;
        this.hookDispatcher = hookDispatcher;
        AtomicInteger seq = new AtomicInteger();
        this.readPool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "kernel-readtool-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 调度执行一轮的所有 tool_call。
     *
     * @return 与 {@code calls} 顺序对齐的结果列表
     */
    public List<ToolResult> schedule(List<ToolCall> calls, ToolContext ctx) {
        List<ToolResult> results = new ArrayList<>(calls.size());
        if (calls.isEmpty()) {
            return results;
        }

        // dedup：同一 (name|args) 只跑一次
        Map<String, String> dedupCache = new HashMap<>();
        // 结果按 index 回填，保证与输入顺序对齐
        Map<Integer, ToolResult> byIndex = new LinkedHashMap<>();

        // 1) 只读工具并发
        List<CompletableFuture<Void>> readFutures = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (!router.isReadOnly(call.getName())) {
                continue;
            }
            final int idx = i;
            readFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    byIndex.put(idx, runOne(call, ctx, dedupCache));
                } catch (Exception e) {
                    // 单个只读工具异常不得让该 index 缺位（否则回填出 null 结果引发下游 NPE）。
                    log.warn("[scheduler] 只读工具 {} 执行异常，降级为错误结果", call.getName(), e);
                    byIndex.put(idx, new ToolResult(call.getId(), call.getName(),
                            "工具执行异常：" + e.getMessage(), null));
                }
            }, readPool));
        }
        try {
            CompletableFuture.allOf(readFutures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.warn("[scheduler] 只读并发段异常，逐个降级", e);
        }

        // 2) 写类工具串行（按原始顺序）——唯一写路径
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (router.isReadOnly(call.getName())) {
                continue;
            }
            byIndex.put(i, runOne(call, ctx, dedupCache));
        }

        for (int i = 0; i < calls.size(); i++) {
            ToolResult r = byIndex.get(i);
            if (r == null) {
                // 兜底：任何原因导致某 index 缺位，都补一个占位结果，保证 results 与 calls 对齐且无 null。
                ToolCall c = calls.get(i);
                r = new ToolResult(c.getId(), c.getName(), "（该工具调用无结果，已跳过）", null);
            }
            results.add(r);
        }
        return results;
    }

    private ToolResult runOne(ToolCall call, ToolContext ctx, Map<String, String> dedupCache) {
        // 执行侧权限门（M4）：真跑前先裁决。非 ALLOW → 不执行，回填说明性 observation。
        Decision verdict = permissionGate.decide(call.getName(), call.getArguments(), ctx.mode());
        if (!verdict.isAllow()) {
            String obs = switch (verdict.decision()) {
                case DENY -> "被权限策略拒绝：" + verdict.reason();
                case ASK -> "需人工审批：" + verdict.reason() + "（当前非交互流，暂拒绝，等 M5/M7 审批链）";
                default -> verdict.reason();
            };
            log.info("[perm-gate] 拦截工具 {}（{}，风险 {}）：{}",
                    call.getName(), verdict.decision(), verdict.riskLevel(), verdict.reason());
            return new ToolResult(call.getId(), call.getName(), obs, verdict);
        }

        // M7.1 PreToolUse Hook（权限门之后、dispatch 之前）：确定性护栏，可 BLOCK 或改参。
        Map<String, Object> effectiveArgs = call.getArguments();
        HookDecision hook = hookDispatcher.runPreToolUse(call.getName(), effectiveArgs, ctx);
        if (hook.isBlock()) {
            return new ToolResult(call.getId(), call.getName(), hook.reason(), verdict);
        }
        if (hook.isModify()) {
            effectiveArgs = hook.rewrittenArgs();
        }

        // dedup 用「生效参数」（含 hook 改写）为 key，保证改参后不误复用旧结果。
        String key = call.getName() + "|" + (effectiveArgs == null ? "" : effectiveArgs);
        String cached;
        synchronized (dedupCache) {
            cached = dedupCache.get(key);
        }
        String content;
        if (cached != null) {
            content = "（本轮已执行过相同调用，复用上次结果）\n" + cached;
        } else {
            content = router.dispatch(call.getName(), ctx, effectiveArgs);
            synchronized (dedupCache) {
                dedupCache.putIfAbsent(key, content);
            }
        }
        // M7.1 PostToolUse Hook：旁路观察/记录，不改结果。
        hookDispatcher.runPostToolUse(call.getName(), effectiveArgs, content, ctx);
        return new ToolResult(call.getId(), call.getName(), content, verdict);
    }
}
