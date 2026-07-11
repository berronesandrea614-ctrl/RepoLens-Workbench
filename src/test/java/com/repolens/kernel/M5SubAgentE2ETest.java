package com.repolens.kernel;

import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.loop.AgentLoopExecutor;
import com.repolens.kernel.loop.AgentLoopExecutor.RunSpec;
import com.repolens.kernel.loop.AgentRunResult;
import com.repolens.kernel.loop.ToolRouter;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import com.repolens.llm.model.ToolDefinition;
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
 * M5.2 只读子代理（Task）真实行为 E2E（防假实现的硬门）。真 Spring + H2 + 影子区，LLM 用脚本桩驱动，
 * 但 Task 派生的<b>隔离子 run、独立 message 列表、只读工具子集、只回摘要</b>全是真的——验的是
 * 「上下文隔离真成立」，不是「方法被调用」：
 * <ol>
 *   <li>父调 Task；子代理脚本里真跑多步（read + grep）后返回一段<b>短摘要</b>；</li>
 *   <li>父 transcript 里 Task 那步的 observation ≈ 子摘要（短、含摘要串）；</li>
 *   <li>父 transcript <b>不含</b>子代理的中间步内容（子的 read/grep observation 不外泄）；</li>
 *   <li>Task 定义 readOnly，且只读子集里不含 write/edit。</li>
 * </ol>
 */
@SpringBootTest(classes = {KernelTestApplication.class, M5SubAgentE2ETest.TestCfg.class})
class M5SubAgentE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m5sub;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m5sub-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    @TestConfiguration
    static class TestCfg {
    }

    @Autowired AgentLoopExecutor executor;
    @Autowired M3AgentLoopE2ETest.ScriptableLlmClient llm;
    @Autowired ShadowWorkspaceManager shadowManager;
    @Autowired ToolRouter router;

    private static final String REL = "src/main/java/com/demo/Calc.java";
    private static final String SRC =
            "package com.demo;\n" +
            "public class Calc {\n" +
            "    // SECRET_MARKER_XYZ 子代理中间步会读到这行\n" +
            "    public int add(int a, int b) { return a + b; }\n" +
            "}\n";

    private static final String CHILD_SUMMARY = "鉴权集中在 Calc.add，无其他分布点。";

    private ToolContext newCtx(long id) throws Exception {
        Path repoDir = Files.createTempDirectory("rk-m5sub-repo");
        Path f = repoDir.resolve(REL);
        Files.createDirectories(f.getParent());
        Files.writeString(f, SRC);
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

    @Test
    void parentDelegatesToSubAgent_onlySummaryReturns_childStepsIsolated() throws Exception {
        ToolContext ctx = newCtx(1L);
        llm.reset();

        // 脚本按 poll 顺序压：父先发起 Task → 子 run 从同一队列取它的多步 → 子收尾摘要 → 父收尾。
        // 父轮1：调 Task
        llm.script.add(toolTurn(call("p1", "Task",
                Map.of("description", "找出鉴权逻辑分布在哪些类"))));
        // ↓ 子 run（独立 message 列表）从这里开始 poll：
        // 子轮1：read 文件（会读到 SECRET_MARKER_XYZ —— 属于子的中间步，不该进父上下文）
        llm.script.add(toolTurn(call("c1", "read", Map.of("file_path", REL))));
        // 子轮2：grep 一个只在中间步出现的记号
        llm.script.add(toolTurn(call("c2", "grep", Map.of("pattern", "SECRET_MARKER_XYZ"))));
        // 子轮3：无 tool_call → 返回短摘要（这是唯一回传父的内容）
        llm.script.add(finalTurn(CHILD_SUMMARY));
        // ↑ 子 run 结束，控制权回父
        // 父轮2：无 tool_call → 收尾
        llm.script.add(finalTurn("已根据子代理结论完成分析 parent-done"));

        AgentRunResult res = executor.run(new RunSpec(
                "你是主代理。", "调研鉴权分布", "test-model", ctx, 0, 0));

        assertEquals(AgentRunResult.TerminationReason.NO_TOOL_CALL, res.terminationReason());
        assertTrue(res.finalText().contains("parent-done"), "父应正常收尾");

        // 父 transcript 里 Task 那步的 tool observation：应≈子摘要（含摘要串），且短
        List<LlmMessage> tx = res.transcript();
        String taskObs = tx.stream()
                .filter(m -> "tool".equals(m.getRole()) && "p1".equals(m.getToolCallId()))
                .map(LlmMessage::getContent)
                .findFirst().orElse(null);
        assertTrue(taskObs != null, "父 transcript 应有 Task 步的 observation");
        assertTrue(taskObs.contains(CHILD_SUMMARY), "Task observation 应包含子代理的最终摘要");

        // 隔离硬门：父 transcript 全文不得含子代理中间步才会出现的记号
        String parentDump = tx.stream()
                .map(m -> (m.getContent() == null ? "" : m.getContent()))
                .reduce("", (a, b) -> a + "\n" + b);
        assertFalse(parentDump.contains("SECRET_MARKER_XYZ"),
                "子代理中间 read/grep 的内容绝不能进入父上下文（隔离成立）");
        // 父上下文里不该出现子代理 read 工具回填的「文件 ...（共 N 行）」这类中间 observation
        assertFalse(parentDump.contains("public int add"),
                "子代理读到的源码正文不该进入父上下文");

        // 父只因 Task 这一步的 observation 增长：父自己只发起了 1 次工具调用（Task）
        assertEquals(1, res.toolCallCount(), "父自身只应发起 1 次工具调用（Task）");

        // Task 工具是只读视角；只读工具子集（PLAN 目录）里没有 write/edit
        assertTrue(router.isReadOnly("Task"), "Task 应为只读视角");
        List<String> readOnlyNames = router.definitions(PermissionMode.PLAN).stream()
                .map(ToolDefinition::getName).toList();
        assertFalse(readOnlyNames.contains("write"), "只读子集不应含 write");
        assertFalse(readOnlyNames.contains("edit"), "只读子集不应含 edit");
    }
}
