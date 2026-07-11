package com.repolens.kernel.realtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.kernel.loop.RunListener;
import com.repolens.kernel.persistence.entity.RkFileChangeEntity;
import com.repolens.kernel.persistence.mapper.RkFileChangeMapper;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.spi.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 实时改动流发射器（§3.7 可视化外显）——把 agent 每步在影子区落下的文件改动，实时喂给 {@link RunListener#onFileChange}，
 * 供前端像 Cursor 那样边写边高亮 diff。
 *
 * <p>做法：主循环每轮工具调度后调 {@link #emitSince}，查该 run 自上次以来<b>新增</b>的 {@code rk_file_change} 行
 * （agent 写→影子区落盘的唯一记录），对每个改动读「真目录基线 before / 影子区当前 after」回调出去。
 * 因此推给前端的正是<b>影子区相对真目录的 diff</b>——改动仍隔离在影子区、未落真目录（诚实：这是预览不是落地）。
 *
 * <p>无副作用、只读 DB + 文件系统；实时开关关闭时主循环根本不调本类，零开销。
 */
@Service
public class RealtimeChangeEmitter {

    private static final Logger log = LoggerFactory.getLogger(RealtimeChangeEmitter.class);

    private final RkFileChangeMapper fileChangeMapper;
    private final ShadowWorkspaceManager shadowManager;
    private final com.repolens.kernel.git.GitService gitService;

    public RealtimeChangeEmitter(RkFileChangeMapper fileChangeMapper, ShadowWorkspaceManager shadowManager,
                                 com.repolens.kernel.git.GitService gitService) {
        this.fileChangeMapper = fileChangeMapper;
        this.shadowManager = shadowManager;
        this.gitService = gitService;
    }

    /** 本 run 当前最大 file_change id——作为实时流的起始基线，避免把 run 开始前的历史改动误当新改动推。 */
    public long baseline(Long runId) {
        if (runId == null) {
            return 0L;
        }
        RkFileChangeEntity last = fileChangeMapper.selectList(new LambdaQueryWrapper<RkFileChangeEntity>()
                        .eq(RkFileChangeEntity::getRunId, runId)
                        .orderByDesc(RkFileChangeEntity::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        return last == null || last.getId() == null ? 0L : last.getId();
    }

    /**
     * 发射自 {@code sinceId} 以来该 run 新增的文件改动，返回新的最大 id（供下一轮当 sinceId）。
     * 全程 fail-safe：任一改动读取失败只跳过该条、不中断主循环（实时外显不该拖垮 agent）。
     */
    public long emitSince(ToolContext ctx, long sinceId, int stepIndex, RunListener listener) {
        if (ctx == null || ctx.runId() == null || listener == null || ctx.shadow() == null) {
            return sinceId;
        }
        List<RkFileChangeEntity> fresh = fileChangeMapper.selectList(new LambdaQueryWrapper<RkFileChangeEntity>()
                .eq(RkFileChangeEntity::getRunId, ctx.runId())
                .eq(RkFileChangeEntity::getStatus, "WRITTEN_TO_SHADOW")
                .gt(RkFileChangeEntity::getId, sinceId)
                .orderByAsc(RkFileChangeEntity::getId));
        long maxId = sinceId;
        Path repoDir = ctx.repoDir();
        Path shadowRoot = ctx.shadow().root();
        // 直接编辑模式（shadow.id()==null）：工作目录==影子根，改动已落工作目录，
        // before 取自 git HEAD（工作树 vs HEAD 的 diff，Cursor/Claude Code 模型）；否则 before=真目录基线。
        boolean direct = ctx.shadow().id() == null;
        for (RkFileChangeEntity c : fresh) {
            maxId = Math.max(maxId, c.getId() == null ? maxId : c.getId());
            try {
                String rel = c.getFilePath();
                String before = "";
                if (direct) {
                    String head = gitService.showHead(repoDir, rel);
                    before = head == null ? "" : head;
                } else if (repoDir != null) {
                    Path base = repoDir.resolve(rel).normalize();
                    if (Files.exists(base)) {
                        before = Files.readString(base);
                    }
                }
                String after = "";
                Path shadowFile = shadowManager.resolveInShadow(shadowRoot, rel);
                if (Files.exists(shadowFile)) {
                    after = Files.readString(shadowFile);
                }
                listener.onFileChange(stepIndex, ctx.sessionId(), rel, c.getOpType(), before, after);
            } catch (Exception e) {
                log.debug("[realtime] 发射改动 {} 失败，跳过: {}", c.getFilePath(), e.getMessage());
            }
        }
        return maxId;
    }
}
