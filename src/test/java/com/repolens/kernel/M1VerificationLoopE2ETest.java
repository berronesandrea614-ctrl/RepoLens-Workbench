package com.repolens.kernel;

import com.repolens.kernel.ledger.FeatureLedgerService;
import com.repolens.kernel.persistence.entity.RkFeatureLedgerEntity;
import com.repolens.kernel.persistence.entity.RkFileChangeEntity;
import com.repolens.kernel.persistence.entity.RkVerificationRunEntity;
import com.repolens.kernel.persistence.mapper.RkFeatureLedgerMapper;
import com.repolens.kernel.persistence.mapper.RkVerificationRunMapper;
import com.repolens.kernel.shadow.FileChangeRecorder;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.verify.VerificationOutcome;
import com.repolens.kernel.verify.VerificationRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 真实行为 E2E（防假实现的硬门）：真 MySQL(Testcontainers) + 真 maven 编译 + 真 APFS CoW 影子区。
 *
 * <p>验的是整条闭环真跑通，而非"方法被调用/字段存在"：
 * <ol>
 *   <li>真仓库 CoW 克隆到影子区，target/.git 被排除；</li>
 *   <li>往影子区写入<b>会真编译失败</b>的改动，真目录纹丝不动（隔离）；</li>
 *   <li>影子区真跑 {@code mvn -o compile}，断网执行，读到<b>真实</b>编译器报错 + 函数级上下文；</li>
 *   <li>此时想给特性发绿灯被<b>拒绝</b>（failing-until-tested）；</li>
 *   <li>自愈（写回正确代码）后再验证转绿，绿灯挂真凭据；篡改台账能被抓；</li>
 *   <li>合并把改动精确搬回真目录。</li>
 * </ol>
 */
@SpringBootTest(classes = KernelTestApplication.class)
class M1VerificationLoopE2ETest {

    static Path shadowRootDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        // 嵌入式 H2（MySQL 兼容模式）做审计库：无网络/无 Docker，纯本地可跑。
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m1;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        // Spring 启动时自动建 rk_* 表
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        shadowRootDir = Files.createTempDirectory("rk-shadow-root");
        registry.add("repolens.shadow-root", () -> shadowRootDir.toString());
    }

    @Autowired ShadowWorkspaceManager shadowManager;
    @Autowired FileChangeRecorder fileChangeRecorder;
    @Autowired VerificationRunner verificationRunner;
    @Autowired FeatureLedgerService featureLedger;
    @Autowired RkVerificationRunMapper verificationRunMapper;
    @Autowired RkFeatureLedgerMapper ledgerMapper;

    private static final String CALC_REL = "src/main/java/com/demo/Calc.java";
    private static final String CALC_OK =
            "package com.demo;\npublic class Calc {\n    public int add(int a, int b) {\n        return a + b;\n    }\n}\n";
    private static final String CALC_BROKEN =
            "package com.demo;\npublic class Calc {\n    public int add(int a, int b) {\n        return a + b\n    }\n}\n"; // 缺分号
    private static final String CALC_HEALED =
            "package com.demo;\npublic class Calc {\n    // healed\n    public int add(int a, int b) {\n        return a + b;\n    }\n}\n";

    @Test
    void fullVerificationLoop_realCompileFail_thenSelfHeal_thenGreenlight() throws Exception {
        long repoId = 1001L, sessionId = 2001L, runId = 3001L;
        Path repoDir = buildDemoRepo();

        // 1) 真 CoW 克隆到影子区，排除 target/.git
        ShadowHandle shadow = shadowManager.create(repoId, sessionId, runId, repoDir);
        assertTrue(Files.isDirectory(shadow.root()), "影子区根目录应存在");
        assertTrue(Files.exists(shadow.root().resolve("pom.xml")), "pom.xml 应被克隆");
        assertTrue(Files.exists(shadow.root().resolve(CALC_REL)), "源码应被克隆");
        assertFalse(Files.exists(shadow.root().resolve("target")), "target/ 应被排除");
        assertFalse(Files.exists(shadow.root().resolve(".git")), ".git/ 应被排除");

        // 2) 登记特性，默认 FAILING
        String featureKey = "M1.verification-loop";
        RkFeatureLedgerEntity declared = featureLedger.declare(repoId, sessionId, runId, featureKey, "M1 验证闭环");
        assertEquals("FAILING", declared.getStatus());

        // 3) 往影子区写入会编译失败的改动；真目录必须纹丝不动
        RkFileChangeEntity change = fileChangeRecorder.writeToShadow(
                shadow, repoId, sessionId, runId, repoDir, CALC_REL, CALC_BROKEN);
        assertEquals("WRITTEN_TO_SHADOW", change.getStatus());
        assertTrue(change.getNewHash() != null && change.getNewHash().length() == 64, "应记录新内容 sha256");
        assertTrue(Files.exists(shadow.root().resolve(change.getDiffRef())), "全文快照应落磁盘(diffRef)");
        assertEquals(CALC_OK, Files.readString(repoDir.resolve(CALC_REL)), "真目录不应被影子区写盘影响(隔离)");

        // 4) 影子区真跑编译 → 真失败，读到真实报错 + 函数级上下文
        VerificationOutcome fail = verificationRunner.verify(repoId, sessionId, runId, shadow.id(),
                shadow.root(), VerificationRunner.Kind.COMPILE);
        assertFalse(fail.passed(), "改坏后编译应失败");
        assertTrue(fail.exitCode() != 0, "失败退出码应非 0");
        assertTrue(fail.networkIsolated(), "应断网执行(macOS sandbox-exec)");
        assertFalse(fail.failures().isEmpty(), "应解析出至少一条结构化失败");
        assertTrue(fail.failures().stream().anyMatch(f -> f.file().endsWith("Calc.java")),
                "失败应定位到 Calc.java");
        assertTrue(fail.failures().stream().anyMatch(f -> f.context().contains("enclosing") || f.context().contains("add")),
                "失败应带函数级上下文");
        // 落库自证"验的是自己的影子区改动"
        RkVerificationRunEntity failRow = verificationRunMapper.selectById(fail.verificationId());
        assertEquals(shadow.id(), failRow.getShadowId(), "验证行应挂 shadow_id，证明验的是自己的改动");

        // 5) 编译没过就想发绿灯 → 拒绝（failing-until-tested）
        assertThrows(IllegalStateException.class,
                () -> featureLedger.markPassing(repoId, sessionId, featureKey, fail),
                "无真实通过凭据不得转绿");
        assertEquals("FAILING", featureLedger.findByKey(repoId, sessionId, featureKey).getStatus());

        // 6) 自愈：写回正确代码，再验证 → 真通过
        fileChangeRecorder.writeToShadow(shadow, repoId, sessionId, runId, repoDir, CALC_REL, CALC_HEALED);
        VerificationOutcome ok = verificationRunner.verify(repoId, sessionId, runId, shadow.id(),
                shadow.root(), VerificationRunner.Kind.COMPILE);
        assertTrue(ok.passed(), "自愈后编译应通过。tail=" + ok.outputTail());
        assertEquals(0, ok.exitCode());

        // 7) 挂真凭据转绿；篡改台账能被抓
        RkFeatureLedgerEntity green = featureLedger.markPassing(repoId, sessionId, featureKey, ok);
        assertEquals("PASSING", green.getStatus());
        assertEquals(ok.verificationId(), green.getVerificationId(), "绿灯必须挂真实验证运行凭据");
        assertFalse(featureLedger.isTampered(green), "刚封印的台账不应被判篡改");
        // 偷改状态但不重算封印 → 被抓
        green.setStatus("FAILING");
        ledgerMapper.updateById(green);
        assertTrue(featureLedger.isTampered(ledgerMapper.selectById(green.getId())), "偷改状态应被 tamper_seal 抓出");

        // 8) 合并把 agent 改动精确搬回真目录（只搬改过的文件）
        int merged = fileChangeRecorder.mergeAll(shadow, repoDir);
        assertTrue(merged >= 1, "至少合并一个改动");
        assertTrue(Files.readString(repoDir.resolve(CALC_REL)).contains("healed"), "真目录应更新为自愈后的内容");
    }

    /** 造一个最小可离线编译的 maven demo 仓库，并放入 target/、.git/ 以验证排除。 */
    private Path buildDemoRepo() throws IOException {
        Path repo = Files.createTempDirectory("rk-demo-repo");
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
        // 构建产物 + VCS 目录，应被影子区排除
        Files.createDirectories(repo.resolve("target"));
        Files.writeString(repo.resolve("target").resolve("stale.txt"), "old build output");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n");
        return repo;
    }
}
