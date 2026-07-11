package com.repolens.kernel;

import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.hook.HookDecision;
import com.repolens.kernel.hook.PostToolUseHook;
import com.repolens.kernel.hook.PreToolUseHook;
import com.repolens.kernel.loop.AgentLoopExecutor;
import com.repolens.kernel.loop.AgentLoopExecutor.RunSpec;
import com.repolens.kernel.loop.AgentRunResult;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.LlmMessage;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M7.1 Hooks 确定性控制层真实行为 E2E（防假实现的硬门）。真 Spring + H2 + 影子区 + 脚本 LLM 驱动真 loop：
 * <ol>
 *   <li><b>PreToolUse BLOCK</b>：agent 试图 write {@code .env} → {@code ProtectedPathWriteHook} 真拦截，
 *       工具<b>不执行</b>、影子区无该文件、observation 说明被拦；</li>
 *   <li><b>PreToolUse 改参</b>：一个测试 hook 把 write 的 file_path 改写 → 真落到改写后的路径；</li>
 *   <li><b>PostToolUse 被调</b>：一个记录型 hook 真收到每次工具执行的 (name, observation)。</li>
 * </ol>
 * 验的是「hook 真接进 scheduler、真改变执行」，不是「方法被调用」。
 */
@SpringBootTest(classes = {KernelTestApplication.class, M7HookE2ETest.TestCfg.class})
class M7HookE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m7hook;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m7hook-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    /** 记录型 PostToolUse hook：捕获每次工具执行的 name 与 observation（验证 Post 被调）。 */
    static class RecordingPostHook implements PostToolUseHook {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public void afterToolUse(String toolName, Map<String, Object> args, String observation, ToolContext ctx) {
            seen.add(toolName + "=>" + (observation == null ? "" : observation.substring(0, Math.min(20, observation.length()))));
        }
    }

    /** 改参型 PreToolUse hook：把写到 REDIRECT_FROM 的 write 改写到 REDIRECT_TO（验证改参真生效）。 */
    static class RedirectPreHook implements PreToolUseHook {
        @Override
        public HookDecision beforeToolUse(String toolName, Map<String, Object> args, ToolContext ctx) {
            if ("write".equals(toolName) && args != null && REDIRECT_FROM.equals(String.valueOf(args.get("file_path")))) {
                Map<String, Object> rewritten = new HashMap<>(args);
                rewritten.put("file_path", REDIRECT_TO);
                return HookDecision.proceedWith(rewritten);
            }
            return HookDecision.proceed();
        }
    }

    @TestConfiguration
    static class TestCfg {
        // 复用 M3 的共享 scriptableLlmClient bean（唯一 LlmClient），此处不重复定义避免 bean 名冲突。
        @Bean
        RecordingPostHook recordingPostHook() {
            return new RecordingPostHook();
        }

        @Bean
        RedirectPreHook redirectPreHook() {
            return new RedirectPreHook();
        }
    }

    @Autowired AgentLoopExecutor executor;
    @Autowired M3AgentLoopE2ETest.ScriptableLlmClient llm;
    @Autowired ShadowWorkspaceManager shadowManager;
    @Autowired RecordingPostHook recordingPostHook;

    private static final String REDIRECT_FROM = "src/main/java/com/demo/From.java";
    private static final String REDIRECT_TO = "src/main/java/com/demo/To.java";
    private static final String JAVA = "package com.demo;\npublic class From {}\n";

    private ToolContext newCtx(long id) throws Exception {
        Path repoDir = Files.createTempDirectory("rk-m7hook-repo");
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
    void protectedPathWrite_blocked_shadowHasNoFile_observationExplains() throws Exception {
        ToolContext ctx = newCtx(1L);
        llm.reset();
        recordingPostHook.seen.clear();
        // 轮1：agent 试图写 .env（密钥类路径）→ 应被 ProtectedPathWriteHook BLOCK
        llm.script.add(toolTurn(call("t1", "write",
                Map.of("file_path", ".env", "content", "SECRET_KEY=abc123\n"))));
        // 轮2：无 tool_call 收尾
        llm.script.add(finalTurn("done"));

        AgentRunResult res = executor.run(new RunSpec(
                "编码 agent", "写一个 .env", "test-model", ctx, 0, 0));

        assertEquals(AgentRunResult.TerminationReason.NO_TOOL_CALL, res.terminationReason());

        // observation 说明被 hook 拦截
        String obs = res.transcript().stream()
                .filter(m -> "tool".equals(m.getRole()) && "t1".equals(m.getToolCallId()))
                .map(LlmMessage::getContent).findFirst().orElse("");
        assertTrue(obs.contains("ProtectedPathWriteHook") && obs.contains("拦截"),
                "observation 应说明被 ProtectedPathWriteHook 拦截，实际=" + obs);

        // 影子区确实没有 .env 文件（工具真没执行）
        Path shadowEnv = shadowManager.resolveInShadow(ctx.shadow().root(), ".env");
        assertFalse(Files.exists(shadowEnv), "被 hook 拦截后影子区不应有 .env 文件");

        // PostToolUse 对被 BLOCK 的调用不应触发 dispatch 后的记录（BLOCK 在 dispatch 前短路）
        assertTrue(recordingPostHook.seen.stream().noneMatch(s -> s.startsWith("write")),
                "被 BLOCK 的 write 不应触发 PostToolUse（未执行）");
    }

    @Test
    void preToolUse_rewritesArgs_writeLandsAtRewrittenPath_andPostHookObserves() throws Exception {
        ToolContext ctx = newCtx(2L);
        llm.reset();
        recordingPostHook.seen.clear();
        // 轮1：write 到 From.java → RedirectPreHook 改参到 To.java
        llm.script.add(toolTurn(call("w1", "write",
                Map.of("file_path", REDIRECT_FROM, "content", JAVA))));
        llm.script.add(finalTurn("done"));

        AgentRunResult res = executor.run(new RunSpec(
                "编码 agent", "写 From.java", "test-model", ctx, 0, 0));
        assertEquals(AgentRunResult.TerminationReason.NO_TOOL_CALL, res.terminationReason());

        // 改参生效：写落到 To.java，From.java 不存在
        Path to = shadowManager.resolveInShadow(ctx.shadow().root(), REDIRECT_TO);
        Path from = shadowManager.resolveInShadow(ctx.shadow().root(), REDIRECT_FROM);
        assertTrue(Files.exists(to), "改参后应写到 To.java");
        assertFalse(Files.exists(from), "改参后不应写到原路径 From.java");

        // PostToolUse 真被调，且看到的是 write（真执行了的）
        assertTrue(recordingPostHook.seen.stream().anyMatch(s -> s.startsWith("write")),
                "PostToolUse 应在 write 执行后被调，实际=" + recordingPostHook.seen);
    }
}
