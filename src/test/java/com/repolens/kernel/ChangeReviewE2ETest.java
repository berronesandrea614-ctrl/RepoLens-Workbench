package com.repolens.kernel;

import com.repolens.kernel.persistence.mapper.RkFileChangeMapper;
import com.repolens.kernel.realtime.ChangeReviewService;
import com.repolens.kernel.realtime.ChangeReviewService.PendingChange;
import com.repolens.kernel.shadow.FileChangeRecorder;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐处 accept/reject · 真实行为 E2E（Cursor 式评审的后端）。
 *
 * <p>真影子区 + 真文件系统：agent 的改动先 stage 到影子区，逐个文件评审——
 * <ol>
 *   <li>accept 单文件 → 影子区该文件真合并回真目录，其余仍待审、真目录仍不含它们；</li>
 *   <li>reject 单文件 → 影子区该文件撤销（新建的抹掉），真目录始终没动过；</li>
 *   <li>accept-all/reject-all 正确清空待审。</li>
 * </ol>
 */
@SpringBootTest(classes = KernelTestApplication.class)
class ChangeReviewE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_review;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-review-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    @Autowired ChangeReviewService review;
    @Autowired FileChangeRecorder recorder;
    @Autowired ShadowWorkspaceManager shadowManager;
    @Autowired RkFileChangeMapper fileChangeMapper;

    private static final String CALC = "src/main/java/com/demo/Calc.java";
    private static final String NEWF = "src/main/java/com/demo/New.java";
    private static final String SRC = "package com.demo;\npublic class Calc { int x; }\n";
    private static final String CALC_EDITED = "package com.demo;\npublic class Calc { int x = 42; }\n";
    private static final String NEW_CONTENT = "package com.demo;\npublic class New {}\n";

    /** 建 repoDir + active 影子区，stage 两处改动（改 Calc + 新建 New），返回 repoDir。 */
    private Path stageTwoChanges(long id) throws Exception {
        Path repoDir = Files.createTempDirectory("rk-review-repo");
        Path calc = repoDir.resolve(CALC);
        Files.createDirectories(calc.getParent());
        Files.writeString(calc, SRC);
        ShadowHandle shadow = shadowManager.resolveOrCreate(id, id, id, repoDir);
        recorder.writeToShadow(shadow, id, id, id, repoDir, CALC, CALC_EDITED);
        recorder.writeToShadow(shadow, id, id, id, repoDir, NEWF, NEW_CONTENT);
        return repoDir;
    }

    @Test
    void acceptOneFile_mergesToRealDir_othersStillPending_realDirOtherwiseUntouched() throws Exception {
        long id = 1L;
        Path repoDir = stageTwoChanges(id);

        List<PendingChange> pending = review.pending(id, id);
        assertEquals(2, pending.size(), "应有 2 处待审");

        // accept 只接受 Calc.java
        review.acceptFile(repoDir, id, id, CALC);

        // 真目录的 Calc 现在是改后内容；New 仍未落真目录（没 accept）
        assertEquals(CALC_EDITED, Files.readString(repoDir.resolve(CALC)), "accept 后真目录 Calc 应为改后内容");
        assertTrue(Files.notExists(repoDir.resolve(NEWF)), "未 accept 的 New 不应落真目录");

        // Calc 不再待审，New 仍待审
        List<PendingChange> after = review.pending(id, id);
        assertEquals(1, after.size());
        assertEquals(NEWF, after.get(0).filePath());
    }

    @Test
    void rejectNewFile_removesFromShadow_realDirNeverTouched() throws Exception {
        long id = 2L;
        Path repoDir = stageTwoChanges(id);

        // reject 新建的 New.java
        review.rejectFile(repoDir, id, id, NEWF);

        // 影子区的 New 被抹掉；真目录本就没有它
        ShadowHandle shadow = shadowManager.resolveActive(id, id).orElseThrow();
        assertTrue(Files.notExists(shadowManager.resolveInShadow(shadow.root(), NEWF)),
                "reject 新建文件应从影子区抹掉");
        assertTrue(Files.notExists(repoDir.resolve(NEWF)), "真目录始终不该有 New");

        // reject Calc → 影子区 Calc 回到基线，真目录 Calc 仍是原内容
        review.rejectFile(repoDir, id, id, CALC);
        assertEquals(SRC, Files.readString(shadowManager.resolveInShadow(shadow.root(), CALC)),
                "reject 后影子区 Calc 应回到真目录基线");
        assertEquals(SRC, Files.readString(repoDir.resolve(CALC)), "真目录 Calc 始终没动");

        assertTrue(review.pending(id, id).isEmpty(), "两处都 reject 后应无待审");
    }

    @Test
    void acceptAll_landsEverything_thenNothingPending() throws Exception {
        long id = 3L;
        Path repoDir = stageTwoChanges(id);

        review.acceptAll(repoDir, id, id);

        assertEquals(CALC_EDITED, Files.readString(repoDir.resolve(CALC)));
        assertEquals(NEW_CONTENT, Files.readString(repoDir.resolve(NEWF)));
        assertTrue(review.pending(id, id).isEmpty(), "accept-all 后应无待审");
    }

    @Test
    void rejectAll_landsNothing_realDirClean() throws Exception {
        long id = 4L;
        Path repoDir = stageTwoChanges(id);

        review.rejectAll(repoDir, id, id);

        assertEquals(SRC, Files.readString(repoDir.resolve(CALC)), "真目录 Calc 保持原样");
        assertFalse(Files.exists(repoDir.resolve(NEWF)), "New 从未落真目录");
        assertTrue(review.pending(id, id).isEmpty(), "reject-all 后应无待审");
    }
}
