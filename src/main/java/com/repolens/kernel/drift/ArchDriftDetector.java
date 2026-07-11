package com.repolens.kernel.drift;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.kernel.persistence.entity.RkArchDriftEntity;
import com.repolens.kernel.persistence.entity.RkGraphSnapshotEdgeEntity;
import com.repolens.kernel.persistence.entity.RkGraphSnapshotEntity;
import com.repolens.kernel.persistence.entity.RkGraphSnapshotFileEntity;
import com.repolens.kernel.persistence.entity.RkGraphSnapshotNodeEntity;
import com.repolens.kernel.persistence.mapper.RkArchDriftMapper;
import com.repolens.kernel.persistence.mapper.RkGraphSnapshotEdgeMapper;
import com.repolens.kernel.persistence.mapper.RkGraphSnapshotFileMapper;
import com.repolens.kernel.persistence.mapper.RkGraphSnapshotMapper;
import com.repolens.kernel.persistence.mapper.RkGraphSnapshotNodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M9 · 架构漂移比对（跨快照的结构 diff + 会话/commit 归因）。
 *
 * <p>拿两份 {@link GraphSnapshotService} 落的快照，用<b>语义稳定 key</b>（不认会变的符号 id）做集合差：
 * 符号增删（NODE_ADDED/REMOVED）、依赖增删（EDGE_ADDED/REMOVED）、文件增删改（FILE_ADDED/REMOVED/CHANGED，
 * 按 content_hash 判）。每处漂移归因到引入它的会话（to 快照的 session）与 commit（优先该符号所在文件的
 * last_commit_id，退化到快照代表 commit）。结果落 {@code rk_arch_drift}（前端时间轴/漂移雷达读它）。
 *
 * <p>{@link #evolution} 把本 repo 全部快照按序两两相邻比对，产出「沿会话/commit 回放调用图演化」的时间线。
 */
@Service
public class ArchDriftDetector {

    private static final Logger log = LoggerFactory.getLogger(ArchDriftDetector.class);

    private final GraphSnapshotService snapshotService;
    private final RkGraphSnapshotMapper snapshotMapper;
    private final RkGraphSnapshotNodeMapper nodeMapper;
    private final RkGraphSnapshotEdgeMapper edgeMapper;
    private final RkGraphSnapshotFileMapper fileMapper;
    private final RkArchDriftMapper driftMapper;

    public ArchDriftDetector(GraphSnapshotService snapshotService,
                             RkGraphSnapshotMapper snapshotMapper,
                             RkGraphSnapshotNodeMapper nodeMapper,
                             RkGraphSnapshotEdgeMapper edgeMapper,
                             RkGraphSnapshotFileMapper fileMapper,
                             RkArchDriftMapper driftMapper) {
        this.snapshotService = snapshotService;
        this.snapshotMapper = snapshotMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.fileMapper = fileMapper;
        this.driftMapper = driftMapper;
    }

    /**
     * 比对两快照（from=旧、to=新），检出全部漂移，落库并返回报告。对同一 (from,to) 幂等（先清旧记录）。
     */
    public DriftViews.DriftReport detect(long fromSnapshotId, long toSnapshotId) {
        RkGraphSnapshotEntity from = snapshotMapper.selectById(fromSnapshotId);
        RkGraphSnapshotEntity to = snapshotMapper.selectById(toSnapshotId);
        if (from == null || to == null) {
            throw new IllegalArgumentException("快照不存在: from=" + fromSnapshotId + " to=" + toSnapshotId);
        }

        Map<String, RkGraphSnapshotNodeEntity> fromNodes = nodesByKey(fromSnapshotId);
        Map<String, RkGraphSnapshotNodeEntity> toNodes = nodesByKey(toSnapshotId);
        Map<String, RkGraphSnapshotEdgeEntity> fromEdges = edgesByKey(fromSnapshotId);
        Map<String, RkGraphSnapshotEdgeEntity> toEdges = edgesByKey(toSnapshotId);
        Map<String, RkGraphSnapshotFileEntity> fromFiles = filesByPath(fromSnapshotId);
        Map<String, RkGraphSnapshotFileEntity> toFiles = filesByPath(toSnapshotId);

        List<DriftViews.DriftItem> drifts = new ArrayList<>();

        // 符号增删
        for (Map.Entry<String, RkGraphSnapshotNodeEntity> e : toNodes.entrySet()) {
            if (!fromNodes.containsKey(e.getKey())) {
                RkGraphSnapshotNodeEntity n = e.getValue();
                drifts.add(new DriftViews.DriftItem("NODE_ADDED", e.getKey(), describeNode(n),
                        n.getFilePath(), n.getLanguage(),
                        to.getSessionId(), commitOfFile(toFiles, n.getFilePath(), to.getCommitRef())));
            }
        }
        for (Map.Entry<String, RkGraphSnapshotNodeEntity> e : fromNodes.entrySet()) {
            if (!toNodes.containsKey(e.getKey())) {
                RkGraphSnapshotNodeEntity n = e.getValue();
                drifts.add(new DriftViews.DriftItem("NODE_REMOVED", e.getKey(), describeNode(n),
                        n.getFilePath(), n.getLanguage(),
                        to.getSessionId(), commitOfFile(fromFiles, n.getFilePath(), from.getCommitRef())));
            }
        }

        // 依赖增删（边 desc 用源节点可读名）
        for (Map.Entry<String, RkGraphSnapshotEdgeEntity> e : toEdges.entrySet()) {
            if (!fromEdges.containsKey(e.getKey())) {
                drifts.add(new DriftViews.DriftItem("EDGE_ADDED", e.getKey(),
                        describeEdge(e.getValue(), toNodes), null, null,
                        to.getSessionId(), to.getCommitRef()));
            }
        }
        for (Map.Entry<String, RkGraphSnapshotEdgeEntity> e : fromEdges.entrySet()) {
            if (!toEdges.containsKey(e.getKey())) {
                drifts.add(new DriftViews.DriftItem("EDGE_REMOVED", e.getKey(),
                        describeEdge(e.getValue(), fromNodes), null, null,
                        to.getSessionId(), from.getCommitRef()));
            }
        }

        // 文件增删改（按 content_hash）
        for (Map.Entry<String, RkGraphSnapshotFileEntity> e : toFiles.entrySet()) {
            RkGraphSnapshotFileEntity tf = e.getValue();
            RkGraphSnapshotFileEntity ff = fromFiles.get(e.getKey());
            if (ff == null) {
                drifts.add(fileDrift("FILE_ADDED", tf, to.getSessionId()));
            } else if (!nz(ff.getContentHash()).equals(nz(tf.getContentHash()))) {
                drifts.add(fileDrift("FILE_CHANGED", tf, to.getSessionId()));
            }
        }
        for (Map.Entry<String, RkGraphSnapshotFileEntity> e : fromFiles.entrySet()) {
            if (!toFiles.containsKey(e.getKey())) {
                drifts.add(fileDrift("FILE_REMOVED", e.getValue(), to.getSessionId()));
            }
        }

        persist(from.getRepoId(), fromSnapshotId, toSnapshotId, drifts);

        boolean changed = !drifts.isEmpty();
        log.info("[drift] 比对 snap {}→{}：{} 处漂移（graphHash {}→{}）",
                fromSnapshotId, toSnapshotId, drifts.size(),
                shortHash(from.getGraphHash()), shortHash(to.getGraphHash()));
        return new DriftViews.DriftReport(fromSnapshotId, toSnapshotId,
                from.getGraphHash(), to.getGraphHash(), changed, drifts);
    }

    /** 沿时间回放：本 repo 全部快照按序两两相邻比对，产出演化时间线。 */
    public DriftViews.EvolutionTimeline evolution(long repoId) {
        List<DriftViews.SnapshotView> snaps = snapshotService.list(repoId);
        List<DriftViews.DriftReport> transitions = new ArrayList<>();
        for (int i = 1; i < snaps.size(); i++) {
            transitions.add(detect(snaps.get(i - 1).snapshotId(), snaps.get(i).snapshotId()));
        }
        return new DriftViews.EvolutionTimeline(repoId, snaps, transitions);
    }

    // ---- 内部 ----

    private void persist(Long repoId, long fromId, long toId, List<DriftViews.DriftItem> drifts) {
        // 幂等：先清同一 (from,to) 的旧漂移记录
        driftMapper.delete(new LambdaQueryWrapper<RkArchDriftEntity>()
                .eq(RkArchDriftEntity::getFromSnapshotId, fromId)
                .eq(RkArchDriftEntity::getToSnapshotId, toId));
        for (DriftViews.DriftItem d : drifts) {
            RkArchDriftEntity e = new RkArchDriftEntity();
            e.setRepoId(repoId);
            e.setFromSnapshotId(fromId);
            e.setToSnapshotId(toId);
            e.setDriftType(d.driftType());
            e.setEntityKeyHash(d.entityKeyHash());
            e.setEntityDesc(truncate(d.entityDesc(), 1000));
            e.setFilePath(d.filePath());
            e.setLanguage(d.language());
            e.setAttributedSessionId(d.attributedSessionId());
            e.setAttributedCommit(d.attributedCommit());
            driftMapper.insert(e);
        }
    }

    private DriftViews.DriftItem fileDrift(String type, RkGraphSnapshotFileEntity f, Long sessionId) {
        return new DriftViews.DriftItem(type, null, f.getFilePath(), f.getFilePath(), null,
                sessionId, f.getLastCommitId());
    }

    private Map<String, RkGraphSnapshotNodeEntity> nodesByKey(long snapshotId) {
        Map<String, RkGraphSnapshotNodeEntity> m = new HashMap<>();
        for (RkGraphSnapshotNodeEntity n : nodeMapper.selectList(
                new LambdaQueryWrapper<RkGraphSnapshotNodeEntity>()
                        .eq(RkGraphSnapshotNodeEntity::getSnapshotId, snapshotId))) {
            m.putIfAbsent(n.getKeyHash(), n);
        }
        return m;
    }

    private Map<String, RkGraphSnapshotEdgeEntity> edgesByKey(long snapshotId) {
        Map<String, RkGraphSnapshotEdgeEntity> m = new HashMap<>();
        for (RkGraphSnapshotEdgeEntity e : edgeMapper.selectList(
                new LambdaQueryWrapper<RkGraphSnapshotEdgeEntity>()
                        .eq(RkGraphSnapshotEdgeEntity::getSnapshotId, snapshotId))) {
            m.putIfAbsent(e.getKeyHash(), e);
        }
        return m;
    }

    private Map<String, RkGraphSnapshotFileEntity> filesByPath(long snapshotId) {
        Map<String, RkGraphSnapshotFileEntity> m = new HashMap<>();
        for (RkGraphSnapshotFileEntity f : fileMapper.selectList(
                new LambdaQueryWrapper<RkGraphSnapshotFileEntity>()
                        .eq(RkGraphSnapshotFileEntity::getSnapshotId, snapshotId))) {
            m.putIfAbsent(f.getFilePath(), f);
        }
        return m;
    }

    private static String commitOfFile(Map<String, RkGraphSnapshotFileEntity> files, String path, String fallback) {
        if (path != null) {
            RkGraphSnapshotFileEntity f = files.get(path);
            if (f != null && f.getLastCommitId() != null && !f.getLastCommitId().isBlank()) {
                return f.getLastCommitId();
            }
        }
        return fallback;
    }

    private static String describeNode(RkGraphSnapshotNodeEntity n) {
        StringBuilder sb = new StringBuilder();
        if (n.getClassName() != null && !n.getClassName().isBlank()) {
            sb.append(n.getClassName());
        }
        if (n.getMethodName() != null && !n.getMethodName().isBlank()) {
            if (sb.length() > 0) {
                sb.append('#');
            }
            sb.append(n.getMethodName());
        }
        if (sb.length() == 0) {
            sb.append(nz(n.getSymbolType())).append(' ').append(nz(n.getFilePath()));
        }
        if (n.getSignature() != null && !n.getSignature().isBlank()) {
            sb.append(n.getSignature());
        }
        return sb.toString();
    }

    private static String describeEdge(RkGraphSnapshotEdgeEntity e,
                                       Map<String, RkGraphSnapshotNodeEntity> nodesByKey) {
        String sourceDesc = null;
        // 源节点 keyHash 与节点表 keyHash 一致，可回连
        RkGraphSnapshotNodeEntity src = nodesByKey.get(e.getSourceKeyHash());
        if (src != null) {
            sourceDesc = describeNode(src);
        }
        if (sourceDesc == null) {
            sourceDesc = "src:" + shortHash(e.getSourceKeyHash());
        }
        return sourceDesc + " -" + nz(e.getRelationType()) + "-> " + nz(e.getTargetName());
    }

    private static String shortHash(String h) {
        return h == null ? "∅" : h.substring(0, Math.min(8, h.length()));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
