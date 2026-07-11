package com.repolens.kernel;

import com.repolens.kernel.edit.EditTool;
import com.repolens.kernel.edit.MultiEditTool;
import com.repolens.kernel.edit.ReadTool;
import com.repolens.kernel.edit.WriteTool;
import com.repolens.kernel.shadow.FileChangeRecorder;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * M3 编辑内核真实行为 E2E（防假实现的硬门）：真 Spring + 真 MyBatis(H2) + 真 APFS 影子区 + 真 JavaParser。
 *
 * <p>验的是编辑内核的每条不变式真生效、坏编辑真被挡在影子区之外，而非"字段存在/方法被调用"：
 * <ol>
 *   <li>read 后 edit 成功、结果真落影子区；</li>
 *   <li><b>读后写不变式</b>：没 read 就 edit/write 被拒；read 后文件被改又拿旧视图 edit 被拒；</li>
 *   <li><b>唯一性</b>：old_string 不唯一被拒，补足上下文或 replace_all 才行；</li>
 *   <li><b>语法护栏</b>：edit/write 产出非法 Java 被拒、且影子区内容不被污染；</li>
 *   <li><b>MultiEdit 原子</b>：一处失败整批回滚、一字不落盘。</li>
 * </ol>
 */
@SpringBootTest(classes = KernelTestApplication.class)
class M3EditKernelE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m3edit;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m3edit-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    @Autowired ShadowWorkspaceManager shadowManager;
    @Autowired FileChangeRecorder recorder;
    @Autowired ReadTool readTool;
    @Autowired WriteTool writeTool;
    @Autowired EditTool editTool;
    @Autowired MultiEditTool multiEditTool;

    private static final String REL = "src/main/java/com/demo/Calc.java";
    private static final String SRC =
            "package com.demo;\n" +
            "public class Calc {\n" +
            "    public int add(int a, int b) {\n" +
            "        return a + b;\n" +
            "    }\n" +
            "    public int sub(int a, int b) {\n" +
            "        return a - b;\n" +
            "    }\n" +
            "}\n";

    private ToolContext newCtx(long repoId, long sessionId, long runId) throws Exception {
        Path repoDir = buildRepo();
        ShadowHandle shadow = shadowManager.create(repoId, sessionId, runId, repoDir);
        return new ToolContext(repoId, sessionId, runId, repoDir, shadow, new ReadTracker(),
                com.repolens.domain.enums.PermissionMode.DEFAULT);
    }

    @Test
    void readThenEdit_succeeds_andLandsInShadow() throws Exception {
        ToolContext ctx = newCtx(1L, 1L, 1L);
        String read = readTool.execute(ctx, Map.of("file_path", REL));
        assertTrue(read.contains("1\tpackage com.demo;"), "read 应带行号：" + read);

        String r = editTool.execute(ctx, Map.of(
                "file_path", REL,
                "old_string", "return a + b;",
                "new_string", "return a + b + 0;"));
        assertTrue(r.startsWith("已编辑"), "read 后 edit 应成功：" + r);

        Path shadowFile = shadowManager.resolveInShadow(ctx.shadow().root(), REL);
        assertTrue(Files.readString(shadowFile).contains("a + b + 0"), "改动应真落影子区");
    }

    @Test
    void edit_withoutRead_rejected_byReadBeforeWriteInvariant() throws Exception {
        ToolContext ctx = newCtx(2L, 2L, 2L);
        String r = editTool.execute(ctx, Map.of(
                "file_path", REL,
                "old_string", "return a + b;",
                "new_string", "return a + b + 1;"));
        assertTrue(r.contains("写前必须先 read"), "没读就编辑应被拒：" + r);
        // 影子区内容未变
        Path shadowFile = shadowManager.resolveInShadow(ctx.shadow().root(), REL);
        assertEquals(SRC, Files.readString(shadowFile), "被拒的编辑不应改动影子区");
    }

    @Test
    void edit_ambiguousOldString_rejected_untilUnique() throws Exception {
        ToolContext ctx = newCtx(3L, 3L, 3L);
        readTool.execute(ctx, Map.of("file_path", REL));
        // "int a, int b" 在 add 和 sub 两处出现 → 不唯一
        String ambiguous = editTool.execute(ctx, Map.of(
                "file_path", REL,
                "old_string", "int a, int b",
                "new_string", "int a, int b /*x*/"));
        assertTrue(ambiguous.contains("出现了 2 次") || ambiguous.contains("无法唯一定位"),
                "不唯一片段应被拒：" + ambiguous);

        // replace_all=true 才放行
        String all = editTool.execute(ctx, Map.of(
                "file_path", REL,
                "old_string", "int a, int b",
                "new_string", "int a, int b /*x*/",
                "replace_all", true));
        assertTrue(all.startsWith("已编辑") && all.contains("2 处"), "replace_all 应替换两处：" + all);
    }

    @Test
    void edit_producingInvalidJava_rejected_bySyntaxGuardrail() throws Exception {
        ToolContext ctx = newCtx(4L, 4L, 4L);
        readTool.execute(ctx, Map.of("file_path", REL));
        // 删掉分号 → 结果 Java 非法
        String r = editTool.execute(ctx, Map.of(
                "file_path", REL,
                "old_string", "return a + b;",
                "new_string", "return a + b"));
        assertTrue(r.contains("语法护栏"), "产出非法 Java 应被语法护栏拒绝：" + r);
        Path shadowFile = shadowManager.resolveInShadow(ctx.shadow().root(), REL);
        assertEquals(SRC, Files.readString(shadowFile), "被语法护栏拒的编辑不应污染影子区");
    }

    @Test
    void editAfterFileChangedSinceRead_rejected() throws Exception {
        ToolContext ctx = newCtx(5L, 5L, 5L);
        readTool.execute(ctx, Map.of("file_path", REL));
        // 模拟"读之后文件被改动"（如另一路径写入），读视图 hash 已过期
        recorder.writeToShadow(ctx.shadow(), 5L, 5L, 5L, ctx.repoDir(), REL,
                SRC.replace("a - b", "a - b - 9"));
        String r = editTool.execute(ctx, Map.of(
                "file_path", REL,
                "old_string", "return a + b;",
                "new_string", "return a + b + 2;"));
        assertTrue(r.contains("自上次 read 后已发生变化"), "文件读后被改应要求重读：" + r);
    }

    @Test
    void write_overwriteWithoutRead_rejected_butNewFileAllowed() throws Exception {
        ToolContext ctx = newCtx(6L, 6L, 6L);
        // 覆盖已存在文件但没 read → 拒
        String overwrite = writeTool.execute(ctx, Map.of(
                "file_path", REL, "content", "package com.demo; public class Calc {}"));
        assertTrue(overwrite.contains("写前必须先 read"), "没读就覆盖应被拒：" + overwrite);

        // 新建文件豁免读要求
        String created = writeTool.execute(ctx, Map.of(
                "file_path", "src/main/java/com/demo/New.java",
                "content", "package com.demo;\npublic class New {}\n"));
        assertTrue(created.startsWith("已新建"), "新建文件应放行：" + created);
    }

    @Test
    void multiEdit_atomic_oneBadEditRollsBackWhole() throws Exception {
        ToolContext ctx = newCtx(7L, 7L, 7L);
        readTool.execute(ctx, Map.of("file_path", REL));
        // 第一处能改，第二处 old_string 找不到 → 整批拒绝、一字不落盘
        String r = multiEditTool.execute(ctx, Map.of(
                "file_path", REL,
                "edits", List.of(
                        Map.of("old_string", "return a + b;", "new_string", "return a + b + 3;"),
                        Map.of("old_string", "THIS_DOES_NOT_EXIST", "new_string", "x"))));
        assertTrue(r.contains("整体拒绝"), "任一处失败应整批拒绝：" + r);
        Path shadowFile = shadowManager.resolveInShadow(ctx.shadow().root(), REL);
        assertEquals(SRC, Files.readString(shadowFile), "原子性：第一处也不能落盘");

        // 全部合法 → 原子落盘
        String ok = multiEditTool.execute(ctx, Map.of(
                "file_path", REL,
                "edits", List.of(
                        Map.of("old_string", "return a + b;", "new_string", "return a + b + 3;"),
                        Map.of("old_string", "return a - b;", "new_string", "return a - b - 3;"))));
        assertTrue(ok.startsWith("已原子编辑"), "全部合法应落盘：" + ok);
        String after = Files.readString(shadowFile);
        assertTrue(after.contains("a + b + 3") && after.contains("a - b - 3"), "两处都应生效");
    }

    private Path buildRepo() throws IOException {
        Path repo = Files.createTempDirectory("rk-m3edit-repo");
        Path f = repo.resolve(REL);
        Files.createDirectories(f.getParent());
        Files.writeString(f, SRC);
        return repo;
    }
}
