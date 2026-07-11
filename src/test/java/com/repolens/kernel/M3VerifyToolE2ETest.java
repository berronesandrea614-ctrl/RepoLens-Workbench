package com.repolens.kernel;

import com.repolens.kernel.loop.ToolRouter;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 真实行为 E2E：证明 {@code VerifyTool} 已真正接入内核工具注册表，agent 可在主循环里
 * 按名 {@code runVerification} 触发「影子区真跑构建 → 读真错 → 自愈 → 真通过」的真实行为门。
 *
 * <p>不是"方法被调用/字段存在"式测试：全程走 {@link ToolRouter#dispatch}（agent 实际调用路径），
 * 真 Spring + 真 H2 + 真 APFS CoW 影子区 + 真 maven 编译。
 */
@SpringBootTest(classes = KernelTestApplication.class)
class M3VerifyToolE2ETest {

    static Path shadowRootDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m3verify;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        shadowRootDir = Files.createTempDirectory("rk-shadow-root-m3verify");
        registry.add("repolens.shadow-root", () -> shadowRootDir.toString());
    }

    @Autowired ToolRouter toolRouter;
    @Autowired ShadowWorkspaceManager shadowManager;
    @Autowired FileChangeRecorder fileChangeRecorder;

    private static final String CALC_REL = "src/main/java/com/demo/Calc.java";
    private static final String CALC_OK =
            "package com.demo;\npublic class Calc {\n    public int add(int a, int b) {\n        return a + b;\n    }\n}\n";
    private static final String CALC_BROKEN =
            "package com.demo;\npublic class Calc {\n    public int add(int a, int b) {\n        return a + b\n    }\n}\n"; // 缺分号
    private static final String CALC_HEALED =
            "package com.demo;\npublic class Calc {\n    // healed\n    public int add(int a, int b) {\n        return a + b;\n    }\n}\n";

    @Test
    void verifyTool_registeredAndDispatchable_realFailThenSelfHealThenPass() throws Exception {
        // 1) 注册表里确有 runVerification，且被判为写类(串行调度，避免并发构建互踩)
        assertTrue(toolRouter.has("runVerification"), "runVerification 应已注册进工具注册表");
        assertFalse(toolRouter.isReadOnly("runVerification"), "验证工具跑真实构建，应串行(readOnly=false)");
        assertTrue(toolRouter.definitions().stream().anyMatch(d -> "runVerification".equals(d.getName())),
                "工具目录(喂给LLM)应含 runVerification 定义");

        long repoId = 5001L, sessionId = 6001L, runId = 7001L;
        Path repoDir = buildDemoRepo();
        ShadowHandle shadow = shadowManager.create(repoId, sessionId, runId, repoDir);
        ToolContext ctx = new ToolContext(repoId, sessionId, runId, repoDir, shadow, new ReadTracker(),
                com.repolens.domain.enums.PermissionMode.DEFAULT);

        // 2) 写入会编译失败的改动，经注册表按名调度 runVerification → 真失败 + 结构化定位
        fileChangeRecorder.writeToShadow(shadow, repoId, sessionId, runId, repoDir, CALC_REL, CALC_BROKEN);
        String failOut = toolRouter.dispatch("runVerification", ctx, Map.of("kind", "build"));
        assertTrue(failOut.contains("验证未通过"), "改坏后应报未通过。实际=" + failOut);
        assertTrue(failOut.contains("Calc.java"), "失败应定位到 Calc.java。实际=" + failOut);

        // 3) 自愈后再经注册表调度 → 真通过
        fileChangeRecorder.writeToShadow(shadow, repoId, sessionId, runId, repoDir, CALC_REL, CALC_HEALED);
        String okOut = toolRouter.dispatch("runVerification", ctx, Map.of("kind", "build"));
        assertTrue(okOut.contains("验证通过"), "自愈后应报通过。实际=" + okOut);
    }

    /** 造一个最小可离线编译的 maven demo 仓库。 */
    private Path buildDemoRepo() throws IOException {
        Path repo = Files.createTempDirectory("rk-demo-repo-m3verify");
        Files.writeString(repo.resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.demo</groupId>\n" +
                "  <artifactId>calc</artifactId>\n" +
                "  <version>1.0</version>\n" +
                "  <packaging>jar</packaging>\n" +
                "  <properties>\n" +
                "    <maven.compiler.source>17</maven.compiler.source>\n" +
                "    <maven.compiler.target>17</maven.compiler.target>\n" +
                "    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n" +
                "  </properties>\n" +
                "  <build><plugins><plugin>\n" +
                "    <groupId>org.apache.maven.plugins</groupId>\n" +
                "    <artifactId>maven-compiler-plugin</artifactId>\n" +
                "    <version>3.11.0</version>\n" +
                "  </plugin></plugins></build>\n" +
                "</project>\n");
        Path calc = repo.resolve(CALC_REL);
        Files.createDirectories(calc.getParent());
        Files.writeString(calc, CALC_OK);
        return repo;
    }
}
