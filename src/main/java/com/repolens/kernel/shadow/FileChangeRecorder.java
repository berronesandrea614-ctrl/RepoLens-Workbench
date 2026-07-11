package com.repolens.kernel.shadow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.kernel.persistence.entity.RkFileChangeEntity;
import com.repolens.kernel.persistence.mapper.RkFileChangeMapper;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * 文件改动记录器：把 agent 的每次写盘落成 {@link RkFileChangeEntity} 状态机，
 * 并驱动"合并回真目录 / 回滚"。
 *
 * <p>状态机：{@code WRITTEN_TO_SHADOW}（写进影子区）→ {@code MERGED}（审批后搬回真目录）
 * 或 {@code REVERTED}（撤回，用真目录基线覆盖）。
 * 避坑：DB 不存改动全文——只存 {@code oldHash}/{@code newHash} + {@code diffRef}
 * （全文快照落影子区 {@code .rk/diffs/}），大文件也撑不爆 DB。
 * {@code oldHash} 记录"编辑前磁盘内容指纹"，供 M3 编辑内核做"读后编不变式"校验。
 */
@Slf4j
@Service
public class FileChangeRecorder {

    private final RkFileChangeMapper fileChangeMapper;
    private final ShadowWorkspaceManager shadowManager;

    public FileChangeRecorder(RkFileChangeMapper fileChangeMapper, ShadowWorkspaceManager shadowManager) {
        this.fileChangeMapper = fileChangeMapper;
        this.shadowManager = shadowManager;
    }

    /**
     * agent 写盘的落地点：把 {@code newContent} 写进影子区并记录改动。这是"agent写→影子落盘"闭环的入口。
     *
     * @return 落库后的改动记录
     */
    public RkFileChangeEntity writeToShadow(ShadowHandle shadow, Long repoId, Long sessionId, Long runId,
                                            Path repoDir, String relPath, String newContent) throws IOException, InterruptedException {
        Path shadowFile = shadowManager.resolveInShadow(shadow.root(), relPath);
        boolean existed = Files.exists(shadowFile);
        String oldContent = existed ? Files.readString(shadowFile) : null;

        Files.createDirectories(shadowFile.getParent());
        Files.writeString(shadowFile, newContent);

        String newHash = sha256Hex(newContent.getBytes(StandardCharsets.UTF_8));
        String diffRef = storeSnapshot(shadow.root(), newHash, newContent);

        RkFileChangeEntity e = new RkFileChangeEntity();
        e.setRepoId(repoId);
        e.setSessionId(sessionId);
        e.setRunId(runId);
        e.setShadowId(shadow.id());
        e.setFilePath(relPath);
        e.setOpType(existed ? "WRITE" : "CREATE");
        e.setOldHash(oldContent == null ? null : sha256Hex(oldContent.getBytes(StandardCharsets.UTF_8)));
        e.setNewHash(newHash);
        e.setDiffRef(diffRef);
        e.setStatus("WRITTEN_TO_SHADOW");
        fileChangeMapper.insert(e);
        return e;
    }

    /** 审批通过后，把本影子区里 agent 改过的文件精确重放回真目录（只搬改过的，不碰构建产物）。 */
    public int mergeAll(ShadowHandle shadow, Path repoDir) throws IOException, InterruptedException {
        List<RkFileChangeEntity> changes = fileChangeMapper.selectList(
                new LambdaQueryWrapper<RkFileChangeEntity>()
                        .eq(RkFileChangeEntity::getShadowId, shadow.id())
                        .eq(RkFileChangeEntity::getStatus, "WRITTEN_TO_SHADOW"));
        int merged = 0;
        for (RkFileChangeEntity c : changes) {
            Path shadowFile = shadowManager.resolveInShadow(shadow.root(), c.getFilePath());
            Path repoFile = repoDir.resolve(c.getFilePath()).normalize();
            if ("DELETE".equals(c.getOpType())) {
                Files.deleteIfExists(repoFile);
            } else {
                shadowManager.copyFile(shadowFile, repoFile);
            }
            c.setStatus("MERGED");
            fileChangeMapper.updateById(c);
            merged++;
        }
        shadowManager.markMerged(shadow.id());
        log.info("[filechange] 合并影子区 #{} 的 {} 个改动回真目录", shadow.id(), merged);
        return merged;
    }

    /** 回滚单个改动：用真目录基线覆盖影子区文件（CREATE 则删）。 */
    public void revert(RkFileChangeEntity change, ShadowHandle shadow, Path repoDir) throws IOException, InterruptedException {
        Path shadowFile = shadowManager.resolveInShadow(shadow.root(), change.getFilePath());
        Path repoBase = repoDir.resolve(change.getFilePath()).normalize();
        if ("CREATE".equals(change.getOpType()) || !Files.exists(repoBase)) {
            Files.deleteIfExists(shadowFile);
        } else {
            shadowManager.copyFile(repoBase, shadowFile);
        }
        change.setStatus("REVERTED");
        fileChangeMapper.updateById(change);
    }

    /** 把全文快照落到影子区 {@code .rk/diffs/<hash>.snapshot}，返回相对引用（DB 只存引用）。 */
    private String storeSnapshot(Path shadowRoot, String hash, String content) throws IOException {
        Path diffDir = shadowRoot.resolve(".rk").resolve("diffs");
        Files.createDirectories(diffDir);
        Path snap = diffDir.resolve(hash + ".snapshot");
        if (!Files.exists(snap)) {
            Files.writeString(snap, content);
        }
        return ".rk/diffs/" + hash + ".snapshot";
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
