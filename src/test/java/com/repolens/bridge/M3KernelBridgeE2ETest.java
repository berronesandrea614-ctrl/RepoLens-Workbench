package com.repolens.bridge;

import com.repolens.domain.dto.chat.CodeAnswerRequest;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.vo.AgentStepVO;
import com.repolens.domain.vo.CodeAnswerVO;
import com.repolens.kernel.KernelTestApplication;
import com.repolens.kernel.loop.AgentLoopExecutor;
import com.repolens.kernel.loop.RunListener;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import com.repolens.mapper.ChatSessionMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.service.AgentRunService;
import com.repolens.service.support.RepoWorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M3 收官「接主链路」真实行为 E2E：证明新内核 agent 主循环已接进真实 app 请求入口
 * （{@link KernelAgentService}，即 RepoController /chat/answer/stream 在开关打开时委托的服务）。
 *
 * <p>LLM 用脚本桩（确定性），但 <b>全链路真跑</b>：bridge 编排 → 内核主循环 → 工具注册表分发 →
 * 影子区真落盘 → runVerification 影子区真跑 maven 编译 → 自愈 → transcript 映射成前端 AgentStepVO。
 * 老 app 依赖（RepoMapper/RepoWorkspaceResolver/AgentRunService/LlmRuntimeConfig）用 Mockito stub，
 * 聚焦"内核接进 app 入口后端到端真跑通"这一硬门。
 */
@SpringBootTest(classes = {KernelTestApplication.class, M3KernelBridgeE2ETest.TestCfg.class})
class M3KernelBridgeE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_bridge;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-bridge-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    /** 脚本化 LLM 桩：按预置队列逐轮返回，确定性驱动 bridge → 内核主循环。 */
    static class ScriptableLlmClient implements LlmClient {
        final Deque<LlmResponse> script = new ArrayDeque<>();

        @Override
        public LlmResponse generate(LlmRequest request) {
            LlmResponse r = script.poll();
            return r != null ? r : LlmResponse.builder().success(false).errorMessage("脚本已空").build();
        }
    }

    @TestConfiguration
    static class TestCfg {
        // @Primary：盖过 component-scan 扫进来的其它测试的 LlmClient 桩，让内核 loop 用本测试的脚本桩
        @Bean @org.springframework.context.annotation.Primary
        ScriptableLlmClient bridgeLlm() { return new ScriptableLlmClient(); }

        @Bean RepoMapper bridgeRepoMapper() { return mock(RepoMapper.class); }
        @Bean RepoWorkspaceResolver bridgeRepoWorkspaceResolver() { return mock(RepoWorkspaceResolver.class); }
        @Bean ChatSessionMapper bridgeChatSessionMapper() { return mock(ChatSessionMapper.class); }
        @Bean AgentRunService bridgeAgentRunService() { return mock(AgentRunService.class); }
        @Bean LlmRuntimeConfig bridgeLlmRuntimeConfig() { return mock(LlmRuntimeConfig.class); }

        // KernelAgentService 在 com.repolens.bridge（内核测试上下文不扫），显式装配供本测试用。
        @Bean KernelAgentService kernelAgentService(
                RepoMapper rm, RepoWorkspaceResolver rw, ChatSessionMapper cm,
                AgentRunService ar, ShadowWorkspaceManager sm, AgentLoopExecutor loop, LlmRuntimeConfig cfg,
                com.repolens.kernel.prompt.KernelPromptBuilder pb,
                com.repolens.kernel.rules.HierarchicalRulesLoader rl,
                com.repolens.kernel.skill.SkillSlashResolver ssr) {
            return new KernelAgentService(rm, rw, cm, ar, sm, loop, cfg, pb, rl,
                    new com.repolens.bridge.AskUserService(), new com.repolens.kernel.git.GitService(), ssr,
                    mock(com.repolens.mapper.ChatMessageMapper.class),
                    mock(com.repolens.bridge.KernelAppBridge.class));
        }
    }

    @Autowired KernelAgentService kernelAgentService;
    @Autowired ScriptableLlmClient llm;
    @Autowired ShadowWorkspaceManager shadowManager;
    @Autowired RepoMapper repoMapper;
    @Autowired RepoWorkspaceResolver repoWorkspaceResolver;
    @Autowired AgentRunService agentRunService;
    @Autowired LlmRuntimeConfig llmRuntimeConfig;

    private static final String REL = "src/main/java/com/demo/Calc.java";
    private static final String CALC_OK =
            "package com.demo;\npublic class Calc {\n    public int add(int a, int b) {\n        return a + b;\n    }\n}\n";

    private Path repoDir;

    @BeforeEach
    void setup() throws Exception {
        llm.script.clear();
        repoDir = buildDemoRepo();
        when(repoMapper.selectById(anyLong())).thenReturn(new RepoEntity());
        when(repoWorkspaceResolver.resolveRepoDirectory(any())).thenReturn(repoDir);
        when(repoWorkspaceResolver.resolveReadDirectory(any())).thenReturn(repoDir);
        when(agentRunService.begin(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(9001L);
        when(llmRuntimeConfig.getModelName()).thenReturn("scripted-model");
    }

    @Test
    void bridge_drivesKernelLoop_realEditFailVerifySelfHealPass_throughAppEntry() throws Exception {
        long repoId = 5001L, userId = 1L, sessionId = 6001L;

        // 脚本：read → 改成编译失败(引用未定义符号,语法合法过护栏但编译失败) → runVerification❌
        //       → 修正 → runVerification✅ → 收尾
        llm.script.add(toolTurn(call("t1", "read", Map.of("file_path", REL))));
        llm.script.add(toolTurn(call("t2", "edit", Map.of(
                "file_path", REL, "old_string", "return a + b;", "new_string", "return a + b + undefinedVar;"))));
        llm.script.add(toolTurn(call("t3", "runVerification", Map.of("kind", "build"))));
        llm.script.add(toolTurn(call("t4", "edit", Map.of(
                "file_path", REL, "old_string", "return a + b + undefinedVar;", "new_string", "return a + b + 10;"))));
        llm.script.add(toolTurn(call("t5", "runVerification", Map.of("kind", "build"))));
        llm.script.add(finalTurn("已把 add 改为 a+b+10 并通过编译验证 done"));

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setSessionId(sessionId);
        request.setMode("code");
        request.setQuestion("把 add 改成 a+b+10 并验证");

        // 外部监听：证明过程可被逐步 emit（SSE 路径复用它）
        List<String> emittedSteps = new CopyOnWriteArrayList<>();
        StringBuilder finalTextSink = new StringBuilder();
        RunListener listener = new RunListener() {
            @Override public void onToolStep(int i, String thought, String name, String args, String obs) {
                emittedSteps.add(name);
            }
            @Override public void onFinalText(String text) { finalTextSink.append(text == null ? "" : text); }
        };

        CodeAnswerVO vo = kernelAgentService.runAgent(repoId, userId, request, listener);

        // 1) bridge 用 begin/finish 管 runId（不是旧 god class 的事后 record）
        verify(agentRunService, times(1)).begin(anyLong(), anyLong(), anyLong(), anyString());
        verify(agentRunService, times(1)).finish(anyLong(), anyString(), anyInt(), anyLong());

        // 2) VO 组装正确（前端强依赖字段）
        assertEquals(Boolean.TRUE, vo.getAgentMode());
        assertEquals(9001L, vo.getAgentRunId());
        assertEquals(sessionId, vo.getSessionId());
        assertTrue(vo.getAnswer().contains("done"), "最终答案应为收尾语。实际=" + vo.getAnswer());
        assertEquals(5, vo.getAgentToolCalls(), "应发起 5 次工具调用");

        // 3) 过程被逐步外显（SSE 复用同一 listener）
        assertTrue(emittedSteps.contains("runVerification"), "过程应 emit runVerification 步");
        assertTrue(finalTextSink.toString().contains("done"), "最终文本应被 emit");

        // 4) 真实行为门：两次 runVerification 步——先 ❌ 后 ✅（自愈真发生）
        List<AgentStepVO> verifySteps = vo.getAgentSteps().stream()
                .filter(s -> "runVerification".equals(s.getToolName())).toList();
        assertEquals(2, verifySteps.size(), "应有两次验证");
        assertTrue(verifySteps.get(0).getObservation().contains("未通过"),
                "首次验证应真编译失败。实际=" + verifySteps.get(0).getObservation());
        assertTrue(verifySteps.get(1).getObservation().contains("通过"),
                "自愈后验证应真通过。实际=" + verifySteps.get(1).getObservation());

        // 5) 直接编辑模式（新默认）：修正真落工作目录本身，git 基线兜底撤销（影子隔离另由 M1/ChangeReview 覆盖）
        assertTrue(Files.readString(repoDir.resolve(REL)).contains("a + b + 10"),
                "直接编辑模式：最终修正应真落工作目录");
        assertTrue(Files.isDirectory(repoDir.resolve(".git")), "应已为工作目录建立 git 基线");
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

    private Path buildDemoRepo() throws IOException {
        Path repo = Files.createTempDirectory("rk-bridge-repo");
        Files.writeString(repo.resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.demo</groupId>\n  <artifactId>calc</artifactId>\n  <version>1.0</version>\n" +
                "  <packaging>jar</packaging>\n  <properties>\n" +
                "    <maven.compiler.source>17</maven.compiler.source>\n" +
                "    <maven.compiler.target>17</maven.compiler.target>\n" +
                "    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n  </properties>\n" +
                "  <build><plugins><plugin>\n" +
                "    <groupId>org.apache.maven.plugins</groupId>\n" +
                "    <artifactId>maven-compiler-plugin</artifactId>\n    <version>3.11.0</version>\n" +
                "  </plugin></plugins></build>\n</project>\n");
        Path calc = repo.resolve(REL);
        Files.createDirectories(calc.getParent());
        Files.writeString(calc, CALC_OK);
        return repo;
    }
}
