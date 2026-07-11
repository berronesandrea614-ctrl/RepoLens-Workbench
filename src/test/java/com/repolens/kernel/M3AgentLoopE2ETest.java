package com.repolens.kernel;

import com.repolens.kernel.loop.AgentLoopExecutor;
import com.repolens.kernel.loop.AgentLoopExecutor.RunSpec;
import com.repolens.kernel.loop.AgentRunResult;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.LlmClient;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 主循环真实行为 E2E（防假实现的硬门）。
 *
 * <p>LLM 用脚本化桩（确定性驱动），但主循环、工具注册表分发、影子区落盘、语法护栏、只读并发/写串行
 * 调度<b>全是真的</b>——验的是 loop 机制真跑通，不是"方法被调用"：
 * <ol>
 *   <li>无 tool_call 即止（stop_reason≠tool_use 是唯一自然完成信号，非迭代计数）；</li>
 *   <li>agent 真发起 read→坏 edit（语法护栏真拒、错误真回填）→好 edit（真落影子区）→收尾；</li>
 *   <li>一轮多 tool_call：只读并发 + 写串行，结果按序回填、写真生效；</li>
 *   <li>token 预算耗尽真能终止失控 loop。</li>
 * </ol>
 */
@SpringBootTest(classes = {KernelTestApplication.class, M3AgentLoopE2ETest.TestCfg.class})
class M3AgentLoopE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m3loop;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m3loop-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    /**
     * 脚本化 LLM 桩：按预置队列逐轮返回，确定性驱动主循环。全内核 E2E 共用的唯一 LlmClient
     * （其它测试复用它、不再引入第二个 LlmClient bean，避免上下文里出现歧义候选）。
     *
     * <p>默认单队列（{@link #script}）。M8 并行 fanout 场景多个分支同时调用本桩，用 {@link #route}
     * 按消息里的 marker 把每个分支分发到各自独立队列（各队列只被该分支线程 poll，故并行安全）。
     */
    static class ScriptableLlmClient implements LlmClient {
        final Deque<LlmResponse> script = new ArrayDeque<>();
        final List<LlmRequest> captured = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** 按 marker 路由的分支剧本（并行 fanout 用；为空则退化为单队列 {@link #script}）。 */
        final Map<String, Deque<LlmResponse>> routes = new java.util.concurrent.ConcurrentHashMap<>();

        void reset() {
            script.clear();
            captured.clear();
            routes.clear();
        }

        /** 注册一条按 marker 路由的分支剧本：generate 时消息里命中该 marker 就从这条队列出下一轮。 */
        void route(String marker, LlmResponse... turns) {
            routes.put(marker, new ArrayDeque<>(List.of(turns)));
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            captured.add(request);
            if (!routes.isEmpty()) {
                String hay = haystack(request);
                for (Map.Entry<String, Deque<LlmResponse>> e : routes.entrySet()) {
                    if (hay.contains(e.getKey())) {
                        LlmResponse r = e.getValue().poll();
                        return r != null ? r
                                : LlmResponse.builder().success(false).errorMessage("剧本已空:" + e.getKey()).build();
                    }
                }
                return LlmResponse.builder().success(false).errorMessage("无匹配策略 marker").build();
            }
            LlmResponse r = script.poll();
            return r != null ? r
                    : LlmResponse.builder().success(false).errorMessage("脚本已空").build();
        }

        private static String haystack(LlmRequest req) {
            StringBuilder sb = new StringBuilder();
            if (req.getMessages() != null) {
                for (LlmMessage m : req.getMessages()) {
                    if (m.getContent() != null) {
                        sb.append(m.getContent()).append('\n');
                    }
                }
            }
            return sb.toString();
        }
    }

    @TestConfiguration
    static class TestCfg {
        @Bean
        ScriptableLlmClient scriptableLlmClient() {
            return new ScriptableLlmClient();
        }
    }

    @Autowired AgentLoopExecutor executor;
    @Autowired ScriptableLlmClient llm;
    @Autowired ShadowWorkspaceManager shadowManager;

    private static final String REL = "src/main/java/com/demo/Calc.java";
    private static final String SRC =
            "package com.demo;\n" +
            "public class Calc {\n" +
            "    public int add(int a, int b) {\n" +
            "        return a + b;\n" +
            "    }\n" +
            "}\n";

    private ToolContext newCtx(long id) throws Exception {
        Path repoDir = Files.createTempDirectory("rk-m3loop-repo");
        Path f = repoDir.resolve(REL);
        Files.createDirectories(f.getParent());
        Files.writeString(f, SRC);
        ShadowHandle shadow = shadowManager.create(id, id, id, repoDir);
        return new ToolContext(id, id, id, repoDir, shadow, new ReadTracker(),
                com.repolens.domain.enums.PermissionMode.DEFAULT);
    }

    private static LlmResponse toolTurn(ToolCall... calls) {
        return LlmResponse.builder()
                .success(true)
                .finishReason("tool_calls")
                .toolCalls(List.of(calls))
                .build();
    }

    private static LlmResponse finalTurn(String text) {
        return LlmResponse.builder().success(true).finishReason("stop").content(text).build();
    }

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return ToolCall.builder().id(id).name(name).arguments(args).build();
    }

    @Test
    void loop_read_badEditRejected_goodEdit_thenTerminatesOnNoToolCall() throws Exception {
        ToolContext ctx = newCtx(1L);
        llm.reset();
        // 轮1：read 文件
        llm.script.add(toolTurn(call("t1", "read", Map.of("file_path", REL))));
        // 轮2：坏 edit（删分号）→ 语法护栏拒
        llm.script.add(toolTurn(call("t2", "edit", Map.of(
                "file_path", REL, "old_string", "return a + b;", "new_string", "return a + b"))));
        // 轮3：好 edit → 真落影子区
        llm.script.add(toolTurn(call("t3", "edit", Map.of(
                "file_path", REL, "old_string", "return a + b;", "new_string", "return a + b + 10;"))));
        // 轮4：无 tool_call → 自然完成
        llm.script.add(finalTurn("已完成修改 done"));

        AgentRunResult res = executor.run(new RunSpec(
                "你是编码 agent。", "把 add 改成 a+b+10", "test-model", ctx, 0, 0));

        assertEquals(AgentRunResult.TerminationReason.NO_TOOL_CALL, res.terminationReason());
        assertEquals(4, res.turns(), "应正好 4 轮");
        assertEquals(3, res.toolCallCount(), "应发起 3 次工具调用");
        assertTrue(res.finalText().contains("done"), "最终文本应为收尾语");

        // 坏 edit 的语法护栏拒绝真回填进了对话
        boolean sawGuardrail = res.transcript().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .anyMatch(m -> m.getContent() != null && m.getContent().contains("语法护栏"));
        assertTrue(sawGuardrail, "坏编辑应被语法护栏拒绝并回填错误");

        // 好 edit 真落影子区
        Path shadowFile = shadowManager.resolveInShadow(ctx.shadow().root(), REL);
        assertTrue(Files.readString(shadowFile).contains("a + b + 10"), "好编辑应真落影子区");
    }

    @Test
    void loop_singleFinalTurn_terminatesImmediately() throws Exception {
        ToolContext ctx = newCtx(2L);
        llm.reset();
        llm.script.add(finalTurn("这是直接回答，无需工具"));

        AgentRunResult res = executor.run(new RunSpec(
                "系统", "问个问题", "test-model", ctx, 0, 0));
        assertEquals(AgentRunResult.TerminationReason.NO_TOOL_CALL, res.terminationReason());
        assertEquals(1, res.turns());
        assertEquals(0, res.toolCallCount());
    }

    @Test
    void loop_multiToolCallTurn_readConcurrent_writeSerial_allResultsBackfilled() throws Exception {
        ToolContext ctx = newCtx(3L);
        llm.reset();
        // 一轮里同时 read + grep（两个只读→并发）+ write（写→串行）
        llm.script.add(toolTurn(
                call("a", "read", Map.of("file_path", REL)),
                call("b", "grep", Map.of("pattern", "class Calc")),
                call("c", "write", Map.of(
                        "file_path", "src/main/java/com/demo/New.java",
                        "content", "package com.demo;\npublic class New {}\n"))));
        llm.script.add(finalTurn("done"));

        AgentRunResult res = executor.run(new RunSpec(
                "系统", "并发读+写", "test-model", ctx, 0, 0));
        assertEquals(AgentRunResult.TerminationReason.NO_TOOL_CALL, res.terminationReason());

        // 三个工具结果都按序回填（tool 消息各一条）
        long toolMsgs = res.transcript().stream().filter(m -> "tool".equals(m.getRole())).count();
        assertEquals(3, toolMsgs, "三个 tool_call 应各回填一条结果");
        // grep 命中 + write 真落盘
        Path newFile = shadowManager.resolveInShadow(ctx.shadow().root(), "src/main/java/com/demo/New.java");
        assertTrue(Files.exists(newFile), "write 应真落影子区");
    }

    @Test
    void loop_tokenBudgetExhausted_terminates() throws Exception {
        ToolContext ctx = newCtx(4L);
        llm.reset();
        // 无限请求工具（永不给终止信号），靠预算兜住
        for (int i = 0; i < 60; i++) {
            llm.script.add(toolTurn(call("g" + i, "grep", Map.of("pattern", "x"))));
        }
        AgentRunResult res = executor.run(new RunSpec(
                "系统", "死循环任务", "test-model", ctx, /*maxTokens*/ 1, /*wallClockMs*/ 0));
        assertEquals(AgentRunResult.TerminationReason.BUDGET_EXHAUSTED, res.terminationReason(),
                "极小 token 预算应终止失控 loop");
        assertTrue(res.turns() <= 2, "预算应在头几轮就闸住，实际 " + res.turns() + " 轮");
    }
}
