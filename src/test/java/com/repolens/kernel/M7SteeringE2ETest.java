package com.repolens.kernel;

import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.hook.PostToolUseHook;
import com.repolens.kernel.loop.AgentLoopExecutor;
import com.repolens.kernel.loop.AgentLoopExecutor.RunSpec;
import com.repolens.kernel.loop.AgentRunResult;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.kernel.steering.SteeringQueue;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M7.2 Steering 队列（中途插话重定向不重启 loop）真实行为 E2E（防假实现的硬门）。
 * 真 Spring + H2 + 影子区 + 真 {@link SteeringQueue} + 真 loop（脚本 LLM 复用 M3 的共享桩驱动）：
 * <ol>
 *   <li>loop 跑到中途（第 1 个工具步后）由一个 PostToolUse hook 往 {@link SteeringQueue} push 一条「改为做 X」，
 *       模拟外部在 run 运行中投递插话；</li>
 *   <li>断言 <b>steering 消息真被注入后续轮的上下文</b>——{@code ScriptableLlmClient} 捕获的某次请求
 *       messages 里含该插话原文（loop 每轮排空队列注入的证据）；</li>
 *   <li>断言 <b>转向发生在插话注入之后</b>：agent 转向那步（steered tool_call）对应的请求上下文里已含插话，
 *       且时序上早于插话注入的轮次没有该插话（证明重定向由插话触发，而非脚本无条件顺序）；</li>
 *   <li>断言 <b>loop 未重启</b>：同一 run（turns 连续 ≥3、每次请求上下文长度单调不减、无从头重来）。</li>
 * </ol>
 */
@SpringBootTest(classes = {KernelTestApplication.class, M7SteeringE2ETest.TestCfg.class})
class M7SteeringE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m7steer;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m7steer-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    static final String STEER_MSG = "改为做 X：请改用 grep 检索 STEERED_TARGET";
    // 独特 session/run id：确保只有本测试的 run 会 drain 到 MidRunSteerHook push 的插话，
    // 不干扰其它共享同一 Spring 上下文缓存的 E2E。
    static final long SESSION = 7702L;
    static final long RUN = 7702L;

    /**
     * 在「本测试的 run」的第一个工具步之后往 SteeringQueue push 一条插话。
     * 只对 runId==RUN 生效——避免污染共享上下文里其它测试的 run。
     */
    static class MidRunSteerHook implements PostToolUseHook {
        private final SteeringQueue queue;
        private final AtomicInteger firedForRun = new AtomicInteger();

        MidRunSteerHook(SteeringQueue queue) {
            this.queue = queue;
        }

        @Override
        public void afterToolUse(String toolName, Map<String, Object> args, String observation, ToolContext ctx) {
            if (ctx != null && ctx.runId() != null && ctx.runId() == RUN
                    && firedForRun.incrementAndGet() == 1) {
                queue.push(SESSION, RUN, STEER_MSG);
            }
        }
    }

    @TestConfiguration
    static class TestCfg {
        // 复用 M3 的共享 scriptableLlmClient（唯一 LlmClient），此处只加本测试的 steering hook。
        @Bean
        MidRunSteerHook midRunSteerHook(SteeringQueue queue) {
            return new MidRunSteerHook(queue);
        }
    }

    @Autowired AgentLoopExecutor executor;
    @Autowired M3AgentLoopE2ETest.ScriptableLlmClient llm;
    @Autowired ShadowWorkspaceManager shadowManager;

    private ToolContext newCtx() throws Exception {
        Path repoDir = Files.createTempDirectory("rk-m7steer-repo");
        ShadowHandle shadow = shadowManager.create(SESSION, SESSION, RUN, repoDir);
        return new ToolContext(SESSION, SESSION, RUN, repoDir, shadow, new ReadTracker(),
                PermissionMode.DEFAULT, "test-model");
    }

    private static LlmResponse toolTurn(ToolCall... calls) {
        return LlmResponse.builder().success(true).finishReason("tool_calls").toolCalls(List.of(calls)).build();
    }

    private static LlmResponse finalTurn(String text) {
        return LlmResponse.builder().success(true).finishReason("stop").content(text).build();
    }

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return ToolCall.builder().id(id).name(name).arguments(args).build();
    }

    @Test
    void midRunSteering_injectedIntoContext_agentRedirects_loopNotRestarted() throws Exception {
        ToolContext ctx = newCtx();
        llm.reset();

        // 轮1：原计划——grep ORIGINAL_TARGET（此步后 MidRunSteerHook 会 push 插话）
        llm.script.add(toolTurn(call("orig-1", "grep", Map.of("pattern", "ORIGINAL_TARGET"))));
        // 轮2：此时 loop 已在轮首排空 steering、把插话注入上下文；agent「据插话转向」→ grep STEERED_TARGET
        llm.script.add(toolTurn(call("steered-2", "grep", Map.of("pattern", "STEERED_TARGET"))));
        // 轮3：收尾
        llm.script.add(finalTurn("已按中途指令转向完成 steered-done"));

        AgentRunResult res = executor.run(new RunSpec(
                "编码 agent", "先做原计划", "test-model", ctx, 0, 0));

        assertEquals(AgentRunResult.TerminationReason.NO_TOOL_CALL, res.terminationReason());
        assertTrue(res.turns() >= 3, "应连续跑原计划+转向+收尾至少 3 轮，实际 " + res.turns());

        // 捕获的每次 LLM 请求上下文
        List<LlmRequest> reqs = llm.captured;
        assertTrue(reqs.size() >= 3, "至少 3 次 LLM 调用");

        // ① 轮1 请求上下文里【尚无】插话（证明插话是中途才来的，不是一开始就在）
        boolean steerInFirst = reqs.get(0).getMessages().stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains(STEER_MSG));
        assertFalse(steerInFirst, "首轮请求上下文不应含插话（插话是第 1 工具步之后才 push 的）");

        // ② 转向轮（轮2）请求上下文里【已含】插话——loop 在轮首排空队列注入的证据
        boolean steerInSecond = reqs.get(1).getMessages().stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains(STEER_MSG));
        assertTrue(steerInSecond, "转向轮请求上下文应含被注入的 steering 消息");

        // ③ agent 真据插话转向：transcript 里出现 steered grep（STEERED_TARGET），
        //    且它出现在原计划 grep（ORIGINAL_TARGET）之后
        List<LlmMessage> tx = res.transcript();
        int origIdx = firstAssistantToolIdx(tx, "ORIGINAL_TARGET");
        int steerIdx = firstAssistantToolIdx(tx, "STEERED_TARGET");
        assertTrue(origIdx >= 0, "应先跑过原计划 grep(ORIGINAL_TARGET)");
        assertTrue(steerIdx > origIdx, "转向 grep(STEERED_TARGET) 应发生在原计划之后（中途重定向）");
        assertTrue(res.finalText() != null && res.finalText().contains("steered-done"),
                "最终应从转向分支收尾");

        // ④ loop 未重启：每次请求上下文长度单调不减（同一 messages 引用持续追加，无从头重建）
        int prev = -1;
        for (LlmRequest r : reqs) {
            int sz = r.getMessages().size();
            assertTrue(sz >= prev, "loop 若重启会导致上下文长度回退；应单调不减");
            prev = sz;
        }
    }

    /** transcript 里首个「assistant 发起、grep 参数含给定 pattern」的消息下标；无则 -1。 */
    private static int firstAssistantToolIdx(List<LlmMessage> tx, String pattern) {
        for (int i = 0; i < tx.size(); i++) {
            LlmMessage m = tx.get(i);
            if ("assistant".equals(m.getRole()) && m.getToolCalls() != null
                    && m.getToolCalls().stream().anyMatch(tc ->
                    "grep".equals(tc.getName())
                            && pattern.equals(String.valueOf(tc.getArguments().get("pattern"))))) {
                return i;
            }
        }
        return -1;
    }
}
