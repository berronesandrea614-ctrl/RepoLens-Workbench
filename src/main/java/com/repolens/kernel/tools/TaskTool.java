package com.repolens.kernel.tools;

import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.loop.AgentLoopExecutor;
import com.repolens.kernel.loop.AgentLoopExecutor.RunSpec;
import com.repolens.kernel.loop.AgentRunResult;
import com.repolens.kernel.route.LlmCallKind;
import com.repolens.kernel.route.ModelRouter;
import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Task 工具（M5.2 只读子代理）：派生一个<b>隔离的只读子代理</b>去做调研/定位/理解类子任务，
 * 只把子代理的<b>最终摘要</b>返回给父，父的上下文<b>不被子代理的中间步污染</b>。
 *
 * <p>隔离硬门（三点保证上下文隔离）：
 * <ol>
 *   <li><b>独立 message 列表</b>：子代理走一次全新的 {@link AgentLoopExecutor#run}，其 transcript
 *       是新起的 ArrayList，与父 run 完全无共享引用；</li>
 *   <li><b>只回摘要</b>：本工具只取子 {@link AgentRunResult#finalText()} 返回给父，子的中间
 *       tool_call/observation 一律不外泄——父上下文只因这一步的 observation（=子摘要串）增长；</li>
 *   <li><b>只读视角</b>：子上下文用 {@link PermissionMode#PLAN}，工具目录被 {@code ToolRouter}
 *       过滤到只读子集（read/grep/glob），子代理无从发起写/执行，天然不与父写路径竞争。</li>
 * </ol>
 *
 * <p>子上下文复用父的影子区（只读，无写竞争）、父的 modelName；用<b>独立 {@link ReadTracker}</b>
 * 与独立预算，彻底与父分离。为打破 {@code AgentLoopExecutor}↔{@code TaskTool} 的构造期循环，
 * 通过 {@link ObjectProvider} 懒取 executor。
 */
@Component
public class TaskTool implements KernelTool {

    private static final Logger log = LoggerFactory.getLogger(TaskTool.class);

    /** 子代理预算：默认较父保守，防子代理把总预算烧穿（可后续做成配置）。 */
    private static final long SUB_MAX_TOKENS = 0;        // 0=不额外限 token，靠 hardTurnCap 兜底
    private static final long SUB_WALL_CLOCK_MS = 60_000;

    private static final String SUB_SYSTEM_PROMPT =
            "你是一个只读调研子代理。你只能用 read/grep/glob 在仓库里查资料，不能修改任何文件。"
            + "围绕交给你的 description 做定位与理解，完成后用一段简短摘要回答（聚焦结论，不要贴大段原文），"
            + "这段摘要会作为唯一结果返回给主代理。";

    private final ObjectProvider<AgentLoopExecutor> executorProvider;
    private final ModelRouter modelRouter;

    public TaskTool(ObjectProvider<AgentLoopExecutor> executorProvider, ModelRouter modelRouter) {
        this.executorProvider = executorProvider;
        this.modelRouter = modelRouter;
    }

    @Override
    public String name() {
        return "Task";
    }

    @Override
    public boolean readOnly() {
        // 视角上只读：派出去的子代理只读。父侧调度可并发（不写文件、无副作用竞争）。
        return true;
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition d = new ToolDefinition();
        d.setName("Task");
        d.setDescription("派一个只读子代理去做一段独立的调研/定位/理解子任务。"
                + "做什么：给它一句 description，它会用 read/grep/glob 自主多步查资料，"
                + "只把一段简短摘要返回给你——子代理的中间过程不会进入你的上下文（省 token、防干扰）。"
                + "何时用：需要在大范围里定位/梳理某主题、又不想让一堆中间检索步塞满你自己的上下文时。"
                + "何时不用：你自己一两步 read/grep 就能搞定的小事，别绕道派子代理。"
                + "示例：{\"description\":\"找出鉴权逻辑分布在哪些类，各自职责是什么\"}");
        d.setParameters(Map.of(
                "type", "object",
                "properties", Map.of(
                        "description", Map.of("type", "string",
                                "description", "交给子代理的调研任务描述")),
                "required", List.of("description")));
        return d;
    }

    @Override
    public String execute(ToolContext ctx, Map<String, Object> args) {
        String description = str(args, "description");
        if (description == null || description.isBlank()) {
            return "Task 失败：缺少 description。";
        }

        // 子上下文：复用父的 repo/session/run/影子区/modelName，但用独立 ReadTracker + 只读 PLAN 模式。
        // 复用父 runId 使子代理若查验证记录亦同 run；写路径本就只读，无竞争。
        // M7.3：子代理调研摘要属「杂活」（CHORE）——配了 chore-model 就降档走小模型，否则回退主模型（行为不变）。
        String subModel = modelRouter.modelFor(LlmCallKind.CHORE, ctx.modelName(), ctx.repoDir());

        ToolContext subCtx = new ToolContext(
                ctx.repoId(), ctx.sessionId(), ctx.runId(), ctx.repoDir(),
                ctx.shadow(), new ReadTracker(), PermissionMode.PLAN, subModel);

        RunSpec sub = new RunSpec(
                SUB_SYSTEM_PROMPT, description, subModel,
                subCtx, SUB_MAX_TOKENS, SUB_WALL_CLOCK_MS);

        AgentLoopExecutor executor = executorProvider.getObject();
        AgentRunResult result;
        try {
            // 关键隔离点：run 内部新建独立 messages 列表；此处只取 finalText，
            // 子代理的 transcript（中间 read/grep 步）绝不回传给父。
            result = executor.run(sub);
        } catch (Exception e) {
            log.warn("[task] 子代理执行异常", e);
            return "Task 子代理执行失败：" + e.getMessage();
        }

        String summary = result.finalText();
        if (summary == null || summary.isBlank()) {
            summary = "（子代理未产出摘要，终止原因：" + result.terminationReason() + "）";
        }
        log.info("[task] 子代理完成：turns={} toolCalls={} 终止={}，返回摘要 {} 字",
                result.turns(), result.toolCallCount(), result.terminationReason(), summary.length());
        return "子代理调研结论：\n" + summary;
    }

    private static String str(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
