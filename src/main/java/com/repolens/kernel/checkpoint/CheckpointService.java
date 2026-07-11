package com.repolens.kernel.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.kernel.persistence.entity.RkCheckpointEntity;
import com.repolens.kernel.persistence.mapper.RkCheckpointMapper;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.LlmMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 会话 Checkpoint 服务（M5.3）：给「影子区代码 + 对话步序」打可回滚的点。
 *
 * <ul>
 *   <li>{@link #checkpoint}：把当前影子区的<b>全部工作文件</b>快照到影子区内部的
 *       {@code .rk/checkpoints/<id>/}（排除 {@code .rk} 自身，避免自包含递归），
 *       并把当时的对话（transcript JSON + 步序）落 {@code rk_checkpoint}。</li>
 *   <li>{@link #rewind}：把影子区工作文件<b>整体还原</b>到该快照当时（清掉快照后新增/改动的文件），
 *       并把对话<b>截断</b>回打点时的长度——返回可继续跑的对话列表。</li>
 * </ul>
 *
 * <p>取舍：为求"真实可验证的最小闭环"，快照用全量文件复制（非增量 diff），语义最直白、回滚最可靠；
 * 影子区已排除 VCS/构建产物，量可控。增量化是后续优化点，不影响正确性。
 */
@Slf4j
@Service("kernelCheckpointService")
public class CheckpointService {

    /** 影子区内部目录，不进快照（避免快照包含快照的递归）。 */
    private static final String RK_DIR = ".rk";
    private static final String CKPT_SUBDIR = ".rk/checkpoints";

    private final RkCheckpointMapper checkpointMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CheckpointService(RkCheckpointMapper checkpointMapper) {
        this.checkpointMapper = checkpointMapper;
    }

    /** rewind 的返回：还原后的对话列表 + 元信息。 */
    public record RewindResult(List<LlmMessage> transcript, int stepIndex, Long checkpointId) {}

    /**
     * 打点：快照当前影子区代码 + 记录对话。
     *
     * @param transcript 当前对话（可空）；会被序列化并记录步序
     * @return 落库后的 checkpoint id
     */
    public Long checkpoint(ToolContext ctx, String label, List<LlmMessage> transcript) {
        ShadowHandle shadow = ctx.shadow();
        if (shadow == null || shadow.root() == null) {
            throw new IllegalStateException("无活跃影子区，无法建 checkpoint");
        }
        // 先落库拿 id（用 id 命名快照目录，避免碰撞）
        RkCheckpointEntity e = new RkCheckpointEntity();
        e.setRepoId(ctx.repoId());
        e.setSessionId(ctx.sessionId());
        e.setRunId(ctx.runId());
        e.setShadowId(shadow.id());
        e.setLabel(label);
        e.setStepIndex(transcript == null ? 0 : transcript.size());
        e.setTranscriptJson(serialize(transcript));
        e.setShadowSnapshotRef("PENDING");
        checkpointMapper.insert(e);

        String ref = CKPT_SUBDIR + "/" + e.getId();
        Path snapDir = shadow.root().resolve(ref);
        try {
            snapshotShadow(shadow.root(), snapDir);
        } catch (IOException ex) {
            throw new IllegalStateException("checkpoint 快照影子区失败: " + ex.getMessage(), ex);
        }
        e.setShadowSnapshotRef(ref);
        checkpointMapper.updateById(e);
        log.info("[checkpoint] 建点 #{} session={} shadow={} step={} label={}",
                e.getId(), ctx.sessionId(), shadow.id(), e.getStepIndex(), label);
        return e.getId();
    }

    /**
     * 回滚到某个 checkpoint：还原影子区代码 + 截断对话回打点时。
     *
     * @param currentTranscript 当前对话（用于按 stepIndex 截断，得到回滚后的对话）
     */
    public RewindResult rewind(Long checkpointId, ToolContext ctx, List<LlmMessage> currentTranscript) {
        RkCheckpointEntity e = checkpointMapper.selectById(checkpointId);
        if (e == null) {
            throw new IllegalArgumentException("checkpoint 不存在: " + checkpointId);
        }
        ShadowHandle shadow = ctx.shadow();
        if (shadow == null || shadow.root() == null) {
            throw new IllegalStateException("无活跃影子区，无法 rewind");
        }
        Path snapDir = shadow.root().resolve(e.getShadowSnapshotRef());
        if (!Files.isDirectory(snapDir)) {
            throw new IllegalStateException("checkpoint 快照丢失: " + snapDir);
        }
        try {
            restoreShadow(shadow.root(), snapDir);
        } catch (IOException ex) {
            throw new IllegalStateException("rewind 还原影子区失败: " + ex.getMessage(), ex);
        }

        int step = e.getStepIndex() == null ? 0 : e.getStepIndex();
        List<LlmMessage> rewound = truncate(currentTranscript, step, e.getTranscriptJson());
        log.info("[checkpoint] rewind 到 #{}：影子区已还原，对话截断回 {} 步", checkpointId, step);
        return new RewindResult(rewound, step, checkpointId);
    }

    // ---- 影子区快照/还原 ----

    /** 把影子区工作文件（排除 .rk）复制到快照目录。 */
    private void snapshotShadow(Path shadowRoot, Path snapDir) throws IOException {
        Files.createDirectories(snapDir);
        Path shadowNorm = shadowRoot.normalize();
        try (Stream<Path> walk = Files.walk(shadowNorm)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> !isUnderRk(shadowNorm, p))
                    .toList();
            for (Path f : files) {
                Path rel = shadowNorm.relativize(f);
                Path dst = snapDir.resolve(rel);
                Files.createDirectories(dst.getParent());
                Files.copy(f, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /**
     * 用快照整体覆盖影子区工作文件：
     * 1) 删掉影子区里当前所有工作文件（排除 .rk）——清掉快照后新增/改的；
     * 2) 把快照文件逐一复制回影子区。
     */
    private void restoreShadow(Path shadowRoot, Path snapDir) throws IOException {
        Path shadowNorm = shadowRoot.normalize();
        // 1) 清当前工作文件（保留 .rk 内部）
        try (Stream<Path> walk = Files.walk(shadowNorm)) {
            List<Path> toDelete = walk
                    .filter(p -> !p.equals(shadowNorm))
                    .filter(p -> !isUnderRk(shadowNorm, p))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path p : toDelete) {
                Files.deleteIfExists(p);
            }
        }
        // 2) 快照复制回来
        Path snapNorm = snapDir.normalize();
        try (Stream<Path> walk = Files.walk(snapNorm)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path f : files) {
                Path rel = snapNorm.relativize(f);
                Path dst = shadowNorm.resolve(rel);
                Files.createDirectories(dst.getParent());
                Files.copy(f, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private boolean isUnderRk(Path shadowRoot, Path p) {
        Path rk = shadowRoot.resolve(RK_DIR).normalize();
        return p.normalize().startsWith(rk);
    }

    // ---- 对话序列化/截断 ----

    private String serialize(List<LlmMessage> transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(transcript);
        } catch (Exception ex) {
            log.warn("[checkpoint] transcript 序列化失败，退化为空: {}", ex.getMessage());
            return "[]";
        }
    }

    /**
     * 得到 rewind 后的对话：优先用当前对话截断到 stepIndex（保留引用一致），
     * 当前对话为空时退回从快照 JSON 反序列化。
     */
    private List<LlmMessage> truncate(List<LlmMessage> current, int stepIndex, String snapshotJson) {
        if (current != null && current.size() >= stepIndex) {
            return new ArrayList<>(current.subList(0, stepIndex));
        }
        try {
            LlmMessage[] arr = objectMapper.readValue(
                    snapshotJson == null ? "[]" : snapshotJson, LlmMessage[].class);
            List<LlmMessage> list = new ArrayList<>(List.of(arr));
            return list.size() > stepIndex ? new ArrayList<>(list.subList(0, stepIndex)) : list;
        } catch (Exception ex) {
            log.warn("[checkpoint] transcript 反序列化失败: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }
}
