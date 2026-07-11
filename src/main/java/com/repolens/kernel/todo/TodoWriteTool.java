package com.repolens.kernel.todo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.kernel.persistence.entity.RkVerificationRunEntity;
import com.repolens.kernel.persistence.mapper.RkVerificationRunMapper;
import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.kernel.todo.TodoState.Item;
import com.repolens.kernel.todo.TodoState.Status;
import com.repolens.llm.model.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TodoWrite 工具（M5.1 反漂移）：agent 提交/更新任务清单。<b>不碰文件</b>（readOnly=false 仅为
 * 表明它是"改状态"的控制类工具，串行调度即可），本 run 的清单状态挂在 per-runId 的
 * {@link TodoState}（非 Spring 单例状态，按 runId 隔离）。
 *
 * <p>校验的两条不变式：
 * <ol>
 *   <li><b>至多一个 IN_PROGRESS</b>：多个进行中会稀释注意力，直接拒绝；</li>
 *   <li><b>标 COMPLETED 前须有成功验证</b>：本 run 若从未有过一次 {@code passed=true} 的
 *       {@code runVerification}，则拒绝把任一项标 completed（测试没绿不许声称完成，防 reward hacking）。</li>
 * </ol>
 *
 * <p>主循环通过 {@link #stateOf(Long)} 拿到当前 run 的清单，每轮重注入上下文（见 AgentLoopExecutor）。
 */
@Component
public class TodoWriteTool implements KernelTool {

    private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);

    /** runId → 该 run 的清单状态（弱隔离：run 结束不主动清理，量小可忽略；可后续接生命周期回收）。 */
    private final Map<Long, TodoState> byRun = new ConcurrentHashMap<>();

    private final RkVerificationRunMapper verificationRunMapper;

    public TodoWriteTool(RkVerificationRunMapper verificationRunMapper) {
        this.verificationRunMapper = verificationRunMapper;
    }

    /** 取（或建）某 run 的清单状态——主循环重注入与本工具共用同一实例。 */
    public TodoState stateOf(Long runId) {
        return byRun.computeIfAbsent(runId == null ? -1L : runId, k -> new TodoState());
    }

    @Override
    public String name() {
        return "TodoWrite";
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition d = new ToolDefinition();
        d.setName("TodoWrite");
        d.setDescription("维护你的任务清单，用于长程任务的进度追踪与防漂移。"
                + "做什么：提交一份完整的 todos 清单（每次都是整表替换），每项含 content 与 status"
                + "（pending/in_progress/completed）。"
                + "何时用：任务分多步时，开工前先列清单；每完成一步就更新对应项状态。"
                + "规则：任意时刻至多一个 in_progress；把某项标 completed 前，本次必须已有一次 runVerification 通过，"
                + "否则会被拒绝（不许没验证就声称完成）。"
                + "示例：{\"todos\":[{\"content\":\"改 add\",\"status\":\"in_progress\"},{\"content\":\"加测试\",\"status\":\"pending\"}]}");
        d.setParameters(Map.of(
                "type", "object",
                "properties", Map.of(
                        "todos", Map.of(
                                "type", "array",
                                "description", "完整任务清单（整表替换）",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "content", Map.of("type", "string", "description", "任务描述"),
                                                "status", Map.of("type", "string",
                                                        "description", "pending|in_progress|completed")),
                                        "required", List.of("content", "status")))),
                "required", List.of("todos")));
        return d;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(ToolContext ctx, Map<String, Object> args) {
        Object rawTodos = args == null ? null : args.get("todos");
        if (!(rawTodos instanceof List<?> list) || list.isEmpty()) {
            return "TodoWrite 失败：缺少非空的 todos 数组。";
        }

        List<Item> parsed = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                return "TodoWrite 失败：todos 每一项必须是含 content/status 的对象。";
            }
            String content = str(m.get("content"));
            if (content == null || content.isBlank()) {
                return "TodoWrite 失败：某项缺少 content。";
            }
            Status status = parseStatus(str(m.get("status")));
            if (status == null) {
                return "TodoWrite 失败：非法 status『" + m.get("status")
                        + "』，只能是 pending/in_progress/completed。";
            }
            parsed.add(new Item(content.trim(), status));
        }

        // 不变式 1：至多一个 IN_PROGRESS
        long inProgress = parsed.stream().filter(i -> i.status() == Status.IN_PROGRESS).count();
        if (inProgress > 1) {
            return "TodoWrite 被拒：至多只能有一个 in_progress 任务（当前 " + inProgress
                    + " 个）。请聚焦单一进行中任务。";
        }

        // 不变式 2：要标 completed，必须本 run 已有成功验证
        boolean wantsCompleted = parsed.stream().anyMatch(i -> i.status() == Status.COMPLETED);
        if (wantsCompleted && !hasSuccessfulVerification(ctx)) {
            return "TodoWrite 被拒：本次尚无一次通过的 runVerification，不允许把任务标 completed。"
                    + "请先用 runVerification 验证改动真的通过，再标完成。";
        }

        stateOf(ctx.runId()).replace(parsed);
        log.info("[todo] run={} 更新清单 {} 项（in_progress={}, completed={}）",
                ctx.runId(), parsed.size(), inProgress, wantsCompleted);
        return "清单已更新（共 " + parsed.size() + " 项）。\n" + stateOf(ctx.runId()).renderSnapshot();
    }

    /** 本 run 是否有过一次成功的 runVerification（passed=true）。 */
    private boolean hasSuccessfulVerification(ToolContext ctx) {
        if (ctx.runId() == null) {
            return false;
        }
        Long cnt = verificationRunMapper.selectCount(new LambdaQueryWrapper<RkVerificationRunEntity>()
                .eq(RkVerificationRunEntity::getRunId, ctx.runId())
                .eq(RkVerificationRunEntity::getPassed, true));
        return cnt != null && cnt > 0;
    }

    private static Status parseStatus(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "pending" -> Status.PENDING;
            case "in_progress", "in-progress", "inprogress" -> Status.IN_PROGRESS;
            case "completed", "complete", "done" -> Status.COMPLETED;
            default -> null;
        };
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
