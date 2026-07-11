package com.repolens.kernel;

import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.loop.AgentLoopExecutor;
import com.repolens.kernel.loop.AgentLoopExecutor.RunSpec;
import com.repolens.kernel.loop.AgentRunResult;
import com.repolens.kernel.loop.ToolRouter;
import com.repolens.kernel.prompt.KernelPromptBuilder;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import com.repolens.llm.model.ToolDefinition;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 权限体系真实行为 E2E（防假实现的硬门）。真 Spring + H2 + 影子区，LLM 用脚本桩确定性驱动，
 * 但工具目录过滤、执行侧权限门、影子区落盘全是真的——验的是「权限真生效」，不是「方法被调用」：
 * <ol>
 *   <li><b>PLAN 目录过滤</b>：{@code router.definitions(PLAN)} 里无 write/edit/bash/runVerification，
 *       只有 read/grep/glob；</li>
 *   <li><b>PLAN 执行侧兜底</b>：脚本强行让 agent edit → gate DENY，真目录/影子区无改动；</li>
 *   <li><b>AUTO/DEFAULT 破坏性 bash</b>：脚本跑 {@code rm -rf /} → gate DENY，命令不执行；
 *       普通 edit → ALLOW 真落影子区；</li>
 *   <li><b>PromptBuilder</b>：产出的 system prompt 含 {@code <env>} 与「先 read 再 edit」关键约束。</li>
 * </ol>
 */
@SpringBootTest(classes = {KernelTestApplication.class, M4PermissionE2ETest.TestCfg.class})
class M4PermissionE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m4perm;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m4perm-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    /**
     * 复用 {@link M3AgentLoopE2ETest.ScriptableLlmClient}——它已被 {@code @ComponentScan("com.repolens.kernel")}
     * 扫进上下文并作为唯一 LlmClient 驱动 loop。本测试直接 autowire 同一个桩，往它的脚本队列压响应，
     * 避免再引入第二个 @Primary LlmClient 污染其它测试上下文。
     */
    @TestConfiguration
    static class TestCfg {
    }

    @Autowired AgentLoopExecutor executor;
    @Autowired M3AgentLoopE2ETest.ScriptableLlmClient llm;
    @Autowired ShadowWorkspaceManager shadowManager;
    @Autowired ToolRouter router;
    @Autowired KernelPromptBuilder promptBuilder;

    private static final String REL = "src/main/java/com/demo/Calc.java";
    private static final String SRC =
            "package com.demo;\n" +
            "public class Calc {\n" +
            "    public int add(int a, int b) {\n" +
            "        return a + b;\n" +
            "    }\n" +
            "}\n";

    private ToolContext newCtx(long id, PermissionMode mode) throws Exception {
        Path repoDir = Files.createTempDirectory("rk-m4perm-repo");
        Path f = repoDir.resolve(REL);
        Files.createDirectories(f.getParent());
        Files.writeString(f, SRC);
        ShadowHandle shadow = shadowManager.create(id, id, id, repoDir);
        return new ToolContext(id, id, id, repoDir, shadow, new ReadTracker(), mode);
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

    // ---- 1) PLAN：目录只暴露只读工具 ----
    @Test
    void plan_catalogExposesOnlyReadOnlyTools() {
        Set<String> planNames = router.definitions(PermissionMode.PLAN).stream()
                .map(ToolDefinition::getName).collect(java.util.stream.Collectors.toSet());
        // M5：Task（只读子代理）+ TodoWrite（控制类不碰文件）也属只读视角，PLAN 下仍可用；
        // askUser（只是向用户提问、不碰文件）同属只读，PLAN 下也可反问澄清。
        // Skill 系统：Skill（加载 skill 说明，只读）+ WebSearch/WebFetch（联网只读，且默认被出网门控）
        // 同属只读视角，PLAN 规划/研究阶段本就该可用。
        assertEquals(Set.of("read", "grep", "glob", "Task", "TodoWrite", "askUser",
                        "Skill", "WebSearch", "WebFetch"), planNames,
                "PLAN 目录应只含只读/控制类工具。实际=" + planNames);
        assertFalse(planNames.contains("edit"), "PLAN 目录不应含 edit");
        assertFalse(planNames.contains("write"), "PLAN 目录不应含 write");
        assertFalse(planNames.contains("bash"), "PLAN 目录不应含 bash");
        assertFalse(planNames.contains("runVerification"), "PLAN 目录不应含 runVerification");

        // 非 PLAN 模式暴露全部 14 个工具（11 个原有 + Skill/WebSearch/WebFetch）
        assertEquals(14, router.definitions(PermissionMode.DEFAULT).size(), "DEFAULT 应暴露全部工具");
        assertEquals(14, router.definitions(PermissionMode.AUTO).size(), "AUTO 应暴露全部工具");
    }

    // ---- 2) PLAN：执行侧 gate 兜底，强行 edit 被 DENY，真目录/影子无改动 ----
    @Test
    void plan_forcedEdit_deniedByGate_noChangeToShadowOrRealDir() throws Exception {
        ToolContext ctx = newCtx(101L, PermissionMode.PLAN);
        llm.reset();
        // 脚本强行让 agent 试图 edit（即便目录里没这工具，模拟越权发起）
        llm.script.add(toolTurn(call("e1", "edit", Map.of(
                "file_path", REL, "old_string", "return a + b;", "new_string", "return a + b + 10;"))));
        llm.script.add(finalTurn("done"));

        AgentRunResult res = executor.run(new RunSpec(
                "系统", "计划：改 add", "test-model", ctx, 0, 0));

        // edit 被权限门拒绝并回填说明性 observation
        boolean denied = res.transcript().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .anyMatch(m -> m.getContent() != null && m.getContent().contains("被权限策略拒绝"));
        assertTrue(denied, "PLAN 下 edit 应被 gate DENY 并回填说明。transcript=" + res.transcript());

        // 影子区未被改动（仍是原文，无 +10）
        Path shadowFile = shadowManager.resolveInShadow(ctx.shadow().root(), REL);
        assertFalse(Files.readString(shadowFile).contains("a + b + 10"), "PLAN 下影子区不应有改动");
        // 真目录未被改动
        assertEquals(SRC, Files.readString(ctx.repoDir().resolve(REL)), "PLAN 下真目录不应被改动");
    }

    // ---- 3a) AUTO：破坏性 bash 被 DENY，命令不执行 ----
    @Test
    void auto_destructiveBash_deniedByGate_notExecuted() throws Exception {
        ToolContext ctx = newCtx(102L, PermissionMode.AUTO);
        llm.reset();
        llm.script.add(toolTurn(call("b1", "bash", Map.of("command", "rm -rf /"))));
        llm.script.add(finalTurn("done"));

        AgentRunResult res = executor.run(new RunSpec(
                "系统", "跑个命令", "test-model", ctx, 0, 0));

        String bashObs = res.transcript().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .map(m -> m.getContent() == null ? "" : m.getContent())
                .findFirst().orElse("");
        assertTrue(bashObs.contains("被权限策略拒绝"), "rm -rf / 应被 gate DENY。实际=" + bashObs);
        assertTrue(bashObs.contains("破坏性"), "拒绝理由应说明破坏性。实际=" + bashObs);
        // 不应出现 bash 真执行的退出码痕迹（命令根本没跑）
        assertFalse(bashObs.contains("退出码"), "破坏性命令不应真执行");
    }

    // ---- 3b) AUTO：普通 edit → ALLOW 真落影子区 ----
    @Test
    void auto_normalEdit_allowed_realWriteToShadow() throws Exception {
        ToolContext ctx = newCtx(103L, PermissionMode.AUTO);
        llm.reset();
        llm.script.add(toolTurn(call("r1", "read", Map.of("file_path", REL))));
        llm.script.add(toolTurn(call("e1", "edit", Map.of(
                "file_path", REL, "old_string", "return a + b;", "new_string", "return a + b + 10;"))));
        llm.script.add(finalTurn("done"));

        AgentRunResult res = executor.run(new RunSpec(
                "系统", "把 add 改成 a+b+10", "test-model", ctx, 0, 0));

        assertEquals(AgentRunResult.TerminationReason.NO_TOOL_CALL, res.terminationReason());
        Path shadowFile = shadowManager.resolveInShadow(ctx.shadow().root(), REL);
        assertTrue(Files.readString(shadowFile).contains("a + b + 10"),
                "AUTO 下普通 edit 应被放行且真落影子区");
    }

    // ---- 3c) DEFAULT：破坏性 bash 同样 DENY ----
    @Test
    void default_destructiveBash_deniedByGate() throws Exception {
        ToolContext ctx = newCtx(104L, PermissionMode.DEFAULT);
        llm.reset();
        llm.script.add(toolTurn(call("b1", "bash", Map.of("command", "sudo rm -rf /"))));
        llm.script.add(finalTurn("done"));

        AgentRunResult res = executor.run(new RunSpec(
                "系统", "跑命令", "test-model", ctx, 0, 0));
        boolean denied = res.transcript().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .anyMatch(m -> m.getContent() != null && m.getContent().contains("被权限策略拒绝"));
        assertTrue(denied, "DEFAULT 下破坏性 bash 应被 DENY");
    }

    // ---- 4) PromptBuilder：含 <env> 与「先 read 再 edit」关键约束 ----
    @Test
    void promptBuilder_containsEnvAndReadBeforeEditConstraint() throws Exception {
        ToolContext ctx = newCtx(105L, PermissionMode.DEFAULT);
        String prompt = promptBuilder.build(ctx, "demo-repo", "main");

        assertTrue(prompt.contains("<env>"), "system prompt 应含 <env> 块");
        assertTrue(prompt.contains("</env>"), "system prompt 应含 </env> 收尾");
        assertTrue(prompt.contains("os.name") || prompt.contains(System.getProperty("os.name")),
                "<env> 应注入 os.name");
        assertTrue(prompt.contains("demo-repo"), "<env> 应注入仓库名");
        assertTrue(prompt.contains("main"), "<env> 应注入分支名");
        assertTrue(prompt.contains(ctx.repoDir().toString()), "<env> 应注入工作目录=repoDir");
        assertTrue(prompt.contains(java.time.LocalDate.now().toString()), "<env> 应注入当前日期");
        // 「先 read 再 edit」关键约束
        assertTrue(prompt.contains("read") && prompt.contains("edit"), "应提及 read 与 edit");
        assertTrue(prompt.contains("没读过") || prompt.contains("先用 read"),
                "应含「绝不改没读过的代码 / 先 read 再 edit」约束");
        // 引用代码用「文件路径:行号」的约定（提示词改写后用中文表述 + 具体例子）
        assertTrue(prompt.contains("文件路径:行号") || prompt.contains(":行号") || prompt.contains("Calc.java:12"),
                "应含「文件路径:行号」引用约定");
    }
}
