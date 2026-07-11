package com.repolens.kernel;

import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.checkpoint.CheckpointService;
import com.repolens.kernel.checkpoint.CheckpointService.RewindResult;
import com.repolens.kernel.shadow.FileChangeRecorder;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.LlmMessage;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5.3 会话 Checkpoint 回滚真实行为 E2E（防假实现的硬门）。真 Spring + H2 + 影子区：
 * <ol>
 *   <li>写文件到影子区（真落盘）；</li>
 *   <li>checkpoint（快照影子区代码 + 对话步序）；</li>
 *   <li>继续改文件（覆盖 + 新增）；</li>
 *   <li>rewind → 影子区文件内容<b>真的回到</b> checkpoint 当时（覆盖被撤、新增被删）+ 对话步序回退。</li>
 * </ol>
 */
@SpringBootTest(classes = KernelTestApplication.class)
class M5CheckpointE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m5ckpt;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m5ckpt-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    @Autowired ShadowWorkspaceManager shadowManager;
    @Autowired FileChangeRecorder fileChangeRecorder;
    @Autowired CheckpointService checkpointService;

    private static final String REL = "src/main/java/com/demo/Calc.java";
    private static final String NEW_REL = "src/main/java/com/demo/Extra.java";

    private ToolContext newCtx(long id) throws Exception {
        Path repoDir = Files.createTempDirectory("rk-m5ckpt-repo");
        Path f = repoDir.resolve(REL);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "V0-original\n");
        ShadowHandle shadow = shadowManager.create(id, id, id, repoDir);
        return new ToolContext(id, id, id, repoDir, shadow, new ReadTracker(),
                PermissionMode.DEFAULT, "test-model");
    }

    @Test
    void checkpoint_thenMutate_thenRewind_restoresShadowAndTruncatesTranscript() throws Exception {
        ToolContext ctx = newCtx(1L);
        Path shadowRoot = ctx.shadow().root();

        // 1) 写入 V1（真落影子区）
        fileChangeRecorder.writeToShadow(ctx.shadow(), ctx.repoId(), ctx.sessionId(), ctx.runId(),
                ctx.repoDir(), REL, "V1-at-checkpoint\n");
        assertEquals("V1-at-checkpoint\n",
                Files.readString(shadowManager.resolveInShadow(shadowRoot, REL)));

        // 模拟一段对话（3 步）
        List<LlmMessage> transcript = new ArrayList<>();
        transcript.add(LlmMessage.builder().role("user").content("改 Calc").build());
        transcript.add(LlmMessage.builder().role("assistant").content("写入 V1").build());
        transcript.add(LlmMessage.builder().role("tool").toolCallId("x1").content("已写入").build());

        // 2) checkpoint
        Long ckptId = checkpointService.checkpoint(ctx, "after-V1", transcript);
        assertTrue(ckptId != null && ckptId > 0);

        // 3) 继续改：覆盖 REL 成 V2 + 新增一个文件
        fileChangeRecorder.writeToShadow(ctx.shadow(), ctx.repoId(), ctx.sessionId(), ctx.runId(),
                ctx.repoDir(), REL, "V2-after-checkpoint\n");
        fileChangeRecorder.writeToShadow(ctx.shadow(), ctx.repoId(), ctx.sessionId(), ctx.runId(),
                ctx.repoDir(), NEW_REL, "brand-new-file\n");
        assertEquals("V2-after-checkpoint\n",
                Files.readString(shadowManager.resolveInShadow(shadowRoot, REL)));
        assertTrue(Files.exists(shadowManager.resolveInShadow(shadowRoot, NEW_REL)));

        // 对话又长了 2 步
        transcript.add(LlmMessage.builder().role("assistant").content("写入 V2").build());
        transcript.add(LlmMessage.builder().role("tool").toolCallId("x2").content("已写入 V2").build());
        assertEquals(5, transcript.size());

        // 4) rewind
        RewindResult rr = checkpointService.rewind(ckptId, ctx, transcript);

        // 影子区代码回到 checkpoint 当时：REL 回 V1，checkpoint 后新增的文件被删
        assertEquals("V1-at-checkpoint\n",
                Files.readString(shadowManager.resolveInShadow(shadowRoot, REL)),
                "REL 应回到 checkpoint 当时的 V1");
        assertFalse(Files.exists(shadowManager.resolveInShadow(shadowRoot, NEW_REL)),
                "checkpoint 后新增的文件应被 rewind 删除");

        // 对话步序回退到打点时（3 步）
        assertEquals(3, rr.stepIndex(), "步序应回退到打点时");
        assertEquals(3, rr.transcript().size(), "对话应截断回打点时的 3 条");
        assertEquals("已写入", rr.transcript().get(2).getContent(), "截断后最后一条应是打点时的那条");
    }
}
