package com.repolens.kernel.realtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.kernel.persistence.entity.RkFileChangeEntity;
import com.repolens.kernel.persistence.mapper.RkFileChangeMapper;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时改动的<b>逐处 accept/reject</b>（Cursor 式评审的后端）。
 *
 * <p>配合 {@link RealtimeChangeEmitter} 的实时高亮：用户在编辑器逐个文件决定「接受/拒绝」——
 * <ul>
 *   <li><b>accept</b>：把影子区该文件当前内容<b>合并回真目录</b>（这处改动正式落地），标该文件改动 MERGED；</li>
 *   <li><b>reject</b>：用真目录基线<b>覆盖/删除影子区</b>该文件（撤销这处改动，真目录本就没动过），标 REVERTED。</li>
 * </ul>
 * 未 accept 的改动始终只在影子区、真目录零副作用——这正是内核隔离哲学给「实时预览」兜的底。
 *
 * <p>纯内核 zone：只依赖影子区原语 + rk_file_change，不碰 god class。{@code repoDir}（真目录根）由调用方
 * （bridge controller）解析后传入，故本服务无需依赖 RepoMapper/RepoWorkspaceResolver，保持可独立测。
 */
@Service
public class ChangeReviewService {

    private static final Logger log = LoggerFactory.getLogger(ChangeReviewService.class);

    private static final String WRITTEN = "WRITTEN_TO_SHADOW";

    private final RkFileChangeMapper fileChangeMapper;
    private final ShadowWorkspaceManager shadowManager;
    private final com.repolens.kernel.git.GitService gitService;

    public ChangeReviewService(RkFileChangeMapper fileChangeMapper, ShadowWorkspaceManager shadowManager,
                               com.repolens.kernel.git.GitService gitService) {
        this.fileChangeMapper = fileChangeMapper;
        this.shadowManager = shadowManager;
        this.gitService = gitService;
    }

    /** 一处待审改动。 */
    public record PendingChange(String filePath, String changeType) {
    }

    /** 一次 accept/reject 的结果。 */
    public record ReviewResult(String filePath, String action, boolean ok) {
    }

    /** 列出该会话影子区里当前待审（未 accept/reject）的改动，按文件去重。 */
    public List<PendingChange> pending(Long repoId, Long sessionId) {
        ShadowHandle shadow = shadowManager.resolveActive(repoId, sessionId).orElse(null);
        List<RkFileChangeEntity> rows = shadow != null
                ? writtenRows(shadow.id(), null)
                : directRows(sessionId, null);
        Map<String, PendingChange> byPath = new LinkedHashMap<>();
        for (RkFileChangeEntity c : rows) {
            byPath.putIfAbsent(c.getFilePath(), new PendingChange(c.getFilePath(), c.getOpType()));
        }
        return new ArrayList<>(byPath.values());
    }

    /** 接受某文件：影子区该文件合并回真目录，标 MERGED。 */
    public ReviewResult acceptFile(Path repoDir, Long repoId, Long sessionId, String filePath) {
        ShadowHandle shadow = shadowManager.resolveActive(repoId, sessionId).orElse(null);
        if (shadow == null) {
            // 直接编辑模式：改动已在工作目录，accept = 确认保留（标 MERGED，无需回搬）。幂等。
            markStatus(directRows(sessionId, filePath), "MERGED");
            return new ReviewResult(filePath, "ACCEPT", true);
        }
        List<RkFileChangeEntity> rows = writtenRows(shadow.id(), filePath);
        if (rows.isEmpty()) {
            throw new IllegalStateException("无待审改动: " + filePath);
        }
        try {
            Path shadowFile = shadowManager.resolveInShadow(shadow.root(), filePath);
            Path repoFile = repoDir.resolve(filePath).normalize();
            if (Files.exists(shadowFile)) {
                Files.createDirectories(repoFile.getParent());
                shadowManager.copyFile(shadowFile, repoFile);
            } else {
                Files.deleteIfExists(repoFile); // 删除类改动
            }
            markStatus(rows, "MERGED");
            log.info("[review] accept 文件 {} 合并回真目录（shadow #{}）", filePath, shadow.id());
            return new ReviewResult(filePath, "ACCEPT", true);
        } catch (Exception e) {
            throw new IllegalStateException("accept " + filePath + " 失败: " + e.getMessage(), e);
        }
    }

    /** 拒绝某文件：用真目录基线覆盖/删除影子区该文件，标 REVERTED（真目录不动）。 */
    public ReviewResult rejectFile(Path repoDir, Long repoId, Long sessionId, String filePath) {
        ShadowHandle shadow = shadowManager.resolveActive(repoId, sessionId).orElse(null);
        if (shadow == null) {
            // 直接编辑模式：改动已落工作目录，撤销 = git 把该文件恢复到 HEAD（本次新建的则删除）。
            boolean ok = gitService.restoreFile(repoDir, filePath);
            markStatus(directRows(sessionId, filePath), "REVERTED");
            log.info("[review] reject 文件 {} 走 git 恢复到 HEAD（direct 模式，ok={}）", filePath, ok);
            return new ReviewResult(filePath, "REJECT", ok);
        }
        List<RkFileChangeEntity> rows = writtenRows(shadow.id(), filePath);
        if (rows.isEmpty()) {
            throw new IllegalStateException("无待审改动: " + filePath);
        }
        try {
            Path shadowFile = shadowManager.resolveInShadow(shadow.root(), filePath);
            Path repoBase = repoDir.resolve(filePath).normalize();
            if (Files.exists(repoBase)) {
                shadowManager.copyFile(repoBase, shadowFile); // 回到真目录基线
            } else {
                Files.deleteIfExists(shadowFile); // 新建的 → 从影子区抹去
            }
            markStatus(rows, "REVERTED");
            log.info("[review] reject 文件 {} 撤销影子区改动（shadow #{}）", filePath, shadow.id());
            return new ReviewResult(filePath, "REJECT", true);
        } catch (Exception e) {
            throw new IllegalStateException("reject " + filePath + " 失败: " + e.getMessage(), e);
        }
    }

    /** 接受全部待审改动。 */
    public List<ReviewResult> acceptAll(Path repoDir, Long repoId, Long sessionId) {
        List<ReviewResult> out = new ArrayList<>();
        for (PendingChange p : pending(repoId, sessionId)) {
            out.add(acceptFile(repoDir, repoId, sessionId, p.filePath()));
        }
        return out;
    }

    /** 拒绝全部待审改动。 */
    public List<ReviewResult> rejectAll(Path repoDir, Long repoId, Long sessionId) {
        List<ReviewResult> out = new ArrayList<>();
        for (PendingChange p : pending(repoId, sessionId)) {
            out.add(rejectFile(repoDir, repoId, sessionId, p.filePath()));
        }
        return out;
    }

    // ---- 内部 ----

    /** 直接编辑模式的改动行：shadow_id 为空、按会话 + 状态查（agent 直接改工作目录时写入的记录）。 */
    private List<RkFileChangeEntity> directRows(Long sessionId, String filePath) {
        LambdaQueryWrapper<RkFileChangeEntity> q = new LambdaQueryWrapper<RkFileChangeEntity>()
                .isNull(RkFileChangeEntity::getShadowId)
                .eq(RkFileChangeEntity::getSessionId, sessionId)
                .eq(RkFileChangeEntity::getStatus, WRITTEN)
                .orderByAsc(RkFileChangeEntity::getId);
        if (filePath != null) {
            q.eq(RkFileChangeEntity::getFilePath, filePath);
        }
        return fileChangeMapper.selectList(q);
    }

    private List<RkFileChangeEntity> writtenRows(Long shadowId, String filePath) {
        LambdaQueryWrapper<RkFileChangeEntity> q = new LambdaQueryWrapper<RkFileChangeEntity>()
                .eq(RkFileChangeEntity::getShadowId, shadowId)
                .eq(RkFileChangeEntity::getStatus, WRITTEN)
                .orderByAsc(RkFileChangeEntity::getId);
        if (filePath != null) {
            q.eq(RkFileChangeEntity::getFilePath, filePath);
        }
        return fileChangeMapper.selectList(q);
    }

    private void markStatus(List<RkFileChangeEntity> rows, String status) {
        for (RkFileChangeEntity c : rows) {
            c.setStatus(status);
            fileChangeMapper.updateById(c);
        }
    }
}
