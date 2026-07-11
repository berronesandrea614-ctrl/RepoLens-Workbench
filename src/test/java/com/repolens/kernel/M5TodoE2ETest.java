package com.repolens.kernel;

import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.loop.AgentLoopExecutor;
import com.repolens.kernel.loop.AgentLoopExecutor.RunSpec;
import com.repolens.kernel.loop.AgentRunResult;
import com.repolens.kernel.persistence.entity.RkVerificationRunEntity;
import com.repolens.kernel.persistence.mapper.RkVerificationRunMapper;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.kernel.todo.TodoState;
import com.repolens.kernel.todo.TodoWriteTool;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5.1 TodoWrite 反漂移真实行为 E2E（防假实现的硬门）。真 Spring + H2 + 影子区，脚本 LLM 驱动，
 * 但清单状态机、不变式校验、每轮重注入全是真的：
 * <ol>
 *   <li>提交 [A in_progress, B pending] → 至多一个 in_progress 成立；</li>
 *   <li>没成功验证就把某项标 completed → <b>被拒</b>（observation 含拒绝语，状态不变）；</li>
 *   <li>插入一条 passed 的 runVerification 后，再标 completed → <b>通过</b>；</li>
 *   <li>每轮主循环把当前清单快照重注入了上下文（父 transcript 里能看到清单文本）。</li>
 * </ol>
 */
@SpringBootTest(classes = {KernelTestApplication.class, M5TodoE2ETest.TestCfg.class})
class M5TodoE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m5todo;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m5todo-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    @TestConfiguration
    static class TestCfg {
    }

    @Autowired AgentLoopExecutor executor;
    @Autowired M3AgentLoopE2ETest.ScriptableLlmClient llm;
    @Autowired ShadowWorkspaceManager shadowManager;
    @Autowired TodoWriteTool todoWriteTool;
    @Autowired RkVerificationRunMapper verificationRunMapper;

    private ToolContext newCtx(long id) throws Exception {
        Path repoDir = Files.createTempDirectory("rk-m5todo-repo");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        ShadowHandle shadow = shadowManager.create(id, id, id, repoDir);
        return new ToolContext(id, id, id, repoDir, shadow, new ReadTracker(),
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

    private static Map<String, Object> item(String content, String status) {
        return Map.of("content", content, "status", status);
    }

    private void insertPassedVerification(ToolContext ctx) {
        RkVerificationRunEntity v = new RkVerificationRunEntity();
        v.setRepoId(ctx.repoId());
        v.setSessionId(ctx.sessionId());
        v.setRunId(ctx.runId());
        v.setShadowId(ctx.shadow().id());
        v.setWorkDir("SHADOW");
        v.setKind("COMPILE");
        v.setExitCode(0);
        v.setPassed(true);
        v.setNetworkIsolated(true);
        v.setOracleTampered(false);
        verificationRunMapper.insert(v);
    }

    @Test
    void todoWrite_atMostOneInProgress_completionGatedByVerification_reinjectedEachTurn() throws Exception {
        ToolContext ctx = newCtx(1L);
        llm.reset();

        // 轮1：提交 [A in_progress, B pending]
        llm.script.add(toolTurn(call("t1", "TodoWrite", Map.of(
                "todos", List.of(item("A 改代码", "in_progress"), item("B 加测试", "pending"))))));
        // 轮2：没验证就把 A 标 completed → 应被拒
        llm.script.add(toolTurn(call("t2", "TodoWrite", Map.of(
                "todos", List.of(item("A 改代码", "completed"), item("B 加测试", "pending"))))));
        // 轮3：收尾
        llm.script.add(finalTurn("阶段一 done"));

        AgentRunResult res = executor.run(new RunSpec(
                "你是编码 agent。", "分两步做", "test-model", ctx, 0, 0));

        // 至多一个 in_progress：第一次提交后清单状态真生效
        TodoState state = todoWriteTool.stateOf(ctx.runId());
        assertTrue(state.inProgressCount() <= 1, "至多一个 in_progress");

        List<LlmMessage> tx = res.transcript();
        // 标 completed 被拒：t2 的 observation 含拒绝语
        String t2Obs = tx.stream()
                .filter(m -> "tool".equals(m.getRole()) && "t2".equals(m.getToolCallId()))
                .map(LlmMessage::getContent).findFirst().orElse("");
        assertTrue(t2Obs.contains("被拒"), "没成功验证就标 completed 应被拒，实际：" + t2Obs);
        // 拒绝后状态未落入 completed
        assertFalse(state.hasCompleted(), "被拒后清单里不应有 completed 项");

        // 每轮重注入：父 transcript 里能看到清单快照（含反漂移标题与"下一步聚焦"）
        String dump = tx.stream().map(m -> m.getContent() == null ? "" : m.getContent())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(dump.contains("[当前任务清单 TodoWrite]"), "每轮应把清单快照重注入上下文");
        assertTrue(dump.contains("下一步应聚焦"), "重注入应突出下一步，压漂移");
    }

    @Test
    void todoWrite_completionAllowed_afterSuccessfulVerification() throws Exception {
        ToolContext ctx = newCtx(2L);
        // 先造一条 passed 的验证记录（模拟本 run 已验证通过）
        insertPassedVerification(ctx);

        // 有成功验证后，标 completed 应放行
        String obs = todoWriteTool.execute(ctx, Map.of(
                "todos", List.of(item("A 改代码", "completed"), item("B 加测试", "in_progress"))));
        assertFalse(obs.contains("被拒"), "已有成功验证，标 completed 应放行，实际：" + obs);
        assertTrue(todoWriteTool.stateOf(ctx.runId()).hasCompleted(), "应真的记为 completed");
    }

    @Test
    void todoWrite_rejectsMultipleInProgress() throws Exception {
        ToolContext ctx = newCtx(3L);
        String obs = todoWriteTool.execute(ctx, Map.of(
                "todos", List.of(item("A", "in_progress"), item("B", "in_progress"))));
        assertTrue(obs.contains("被拒"), "两个 in_progress 应被拒，实际：" + obs);
        assertTrue(todoWriteTool.stateOf(ctx.runId()).isEmpty(), "被拒后清单不应写入");
    }
}
