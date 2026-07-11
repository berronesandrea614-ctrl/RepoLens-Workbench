package com.repolens.kernel;

import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.loop.AgentLoopExecutor;
import com.repolens.kernel.loop.AgentLoopExecutor.RunSpec;
import com.repolens.kernel.loop.RunListener;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实时改动事件流 · 真实行为 E2E（Cursor 式「边写边高亮」的后端）。
 *
 * <p>LLM 脚本化桩，但主循环、影子区落盘、实时改动发射<b>全是真的</b>：
 * <ol>
 *   <li>开实时开关：agent 每把一个文件写进影子区，主循环就实时回调 {@code onFileChange}，
 *       且 {@code before}=真目录基线 / {@code after}=影子区当前内容（正是要高亮的 diff），
 *       新建文件 before 为空、改已存在文件 before 为原内容；</li>
 *   <li>改动仍隔离在影子区、真目录零改动（accept 后才落地）；</li>
 *   <li>关实时开关：同样的改动一条 onFileChange 都不发（开关真生效、零开销）。</li>
 * </ol>
 */
@SpringBootTest(classes = KernelTestApplication.class)
class RealtimeChangeStreamE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_realtime;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-realtime-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    @Autowired AgentLoopExecutor executor;
    @Autowired M3AgentLoopE2ETest.ScriptableLlmClient llm;
    @Autowired ShadowWorkspaceManager shadowManager;

    private static final String CALC = "src/main/java/com/demo/Calc.java";
    private static final String NEWF = "src/main/java/com/demo/New.java";
    private static final String NEW_CONTENT = "package com.demo;\npublic class New {}\n";
    private static final String SRC =
            "package com.demo;\n" +
            "public class Calc {\n" +
            "    public int add(int a, int b) {\n" +
            "        return a + b;\n" +
            "    }\n" +
            "}\n";

    /** 收集实时改动事件的监听器。 */
    static class Collector implements RunListener {
        record Change(Long sessionId, String path, String type, String before, String after) {}
        final List<Change> changes = new CopyOnWriteArrayList<>();

        @Override
        public void onToolStep(int i, String t, String n, String a, String o) {
        }

        @Override
        public void onFileChange(int stepIndex, Long sessionId, String filePath, String changeType,
                                 String before, String after) {
            changes.add(new Change(sessionId, filePath, changeType, before, after));
        }

        @Override
        public void onFinalText(String text) {
        }
    }

    private ToolContext newCtx(long id) throws Exception {
        Path repoDir = Files.createTempDirectory("rk-realtime-repo");
        Path f = repoDir.resolve(CALC);
        Files.createDirectories(f.getParent());
        Files.writeString(f, SRC);
        ShadowHandle shadow = shadowManager.create(id, id, id, repoDir);
        return new ToolContext(id, id, id, repoDir, shadow, new ReadTracker(), PermissionMode.DEFAULT, "test-model");
    }

    private void scriptWriteThenEdit() {
        llm.reset();
        // 轮1：新建 New.java（CREATE）
        llm.script.add(toolTurn(call("t1", "write", Map.of("file_path", NEWF, "content", NEW_CONTENT))));
        // 轮2：读 Calc（编辑前必须先读）
        llm.script.add(toolTurn(call("t2", "read", Map.of("file_path", CALC))));
        // 轮3：改 Calc（WRITE）
        llm.script.add(toolTurn(call("t3", "edit", Map.of(
                "file_path", CALC, "old_string", "return a + b;", "new_string", "return a + b + 42;"))));
        // 轮4：收尾
        llm.script.add(finalTurn("done"));
    }

    @Test
    void realtimeOn_emitsFileChanges_withRealDirBeforeAndShadowAfter_realDirUntouched() throws Exception {
        ToolContext ctx = newCtx(1L);
        scriptWriteThenEdit();
        Collector col = new Collector();

        executor.run(new RunSpec("你是编码 agent。", "先建 New.java 再给 add 加 42",
                "test-model", ctx, 0, 0, 0, /*realtimeDiff*/ true), col);

        assertEquals(2, col.changes.size(), "应实时收到 2 个改动事件（新建 + 改）");

        Collector.Change created = col.changes.get(0);
        assertEquals(1L, created.sessionId(), "改动事件应带会话 id（供前端 accept/reject 定位影子区）");
        assertEquals(NEWF, created.path());
        assertEquals("CREATE", created.type());
        assertEquals("", created.before(), "新建文件 before 应为空");
        assertEquals(NEW_CONTENT, created.after(), "新建文件 after 应为写入内容");

        Collector.Change edited = col.changes.get(1);
        assertEquals(CALC, edited.path());
        assertEquals("WRITE", edited.type());
        assertEquals(SRC, edited.before(), "改已存在文件 before 应为真目录原内容");
        assertTrue(edited.after().contains("a + b + 42;"), "after 应为影子区改后内容");

        // 改动仍隔离在影子区：真目录零改动
        assertEquals(SRC, Files.readString(ctx.repoDir().resolve(CALC)), "实时预览不应改真目录");
        assertTrue(Files.notExists(ctx.repoDir().resolve(NEWF)), "新建文件不应落真目录");
    }

    @Test
    void realtimeOff_emitsNothing_butEditsStillLand() throws Exception {
        ToolContext ctx = newCtx(2L);
        scriptWriteThenEdit();
        Collector col = new Collector();

        executor.run(new RunSpec("你是编码 agent。", "先建 New.java 再给 add 加 42",
                "test-model", ctx, 0, 0, 0, /*realtimeDiff*/ false), col);

        assertTrue(col.changes.isEmpty(), "关实时开关不应发任何改动事件");
        // 但改动照样真落影子区
        Path shadowNew = shadowManager.resolveInShadow(ctx.shadow().root(), NEWF);
        assertTrue(Files.exists(shadowNew), "改动仍应真落影子区（只是不实时外显）");
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
}
