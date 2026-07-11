package com.repolens.kernel.drift;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.kernel.drift.spi.CallGraphSnapshotProvider;
import com.repolens.kernel.drift.spi.CallGraphView;
import com.repolens.kernel.drift.spi.DependencyEdge;
import com.repolens.kernel.drift.spi.FileFingerprint;
import com.repolens.kernel.drift.spi.SymbolNode;
import com.repolens.kernel.persistence.entity.RkGraphSnapshotEdgeEntity;
import com.repolens.kernel.persistence.entity.RkGraphSnapshotEntity;
import com.repolens.kernel.persistence.entity.RkGraphSnapshotFileEntity;
import com.repolens.kernel.persistence.entity.RkGraphSnapshotNodeEntity;
import com.repolens.kernel.persistence.mapper.RkGraphSnapshotEdgeMapper;
import com.repolens.kernel.persistence.mapper.RkGraphSnapshotFileMapper;
import com.repolens.kernel.persistence.mapper.RkGraphSnapshotMapper;
import com.repolens.kernel.persistence.mapper.RkGraphSnapshotNodeMapper;
import com.repolens.kernel.shadow.FileChangeRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M9 · 调用图快照捕获（给无历史的调用图补时间维度）。
 *
 * <p>隔壁窗口的调用图每次索引全量覆盖、只留当前一份，且符号自增 id 重索引会变。本服务在时间线上「抓拍」：
 * 每次会话/按需经只读端口 {@link CallGraphSnapshotProvider} 拉当前整张图，对每个符号算<b>语义稳定 key</b>
 * （不认会变的 id）、对整张图算<b>确定性图哈希</b>当时间指纹、用 {@code prevHash} 串成防篡改审计链，
 * 全量落 {@code rk_graph_snapshot*} 表。跨快照的漂移比对由 {@link ArchDriftDetector} 负责。
 *
 * <p>端口用 {@link ObjectProvider} 软依赖：隔壁实现未接入时本 bean 仍能构造、app 正常启动，
 * 只有真去 {@link #capture} 时才报「端口未接入」——不阻塞主链路（兑现跨窗口不互相阻塞的约定）。
 */
@Service
public class GraphSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(GraphSnapshotService.class);

    private final ObjectProvider<CallGraphSnapshotProvider> providerRef;
    private final RkGraphSnapshotMapper snapshotMapper;
    private final RkGraphSnapshotNodeMapper nodeMapper;
    private final RkGraphSnapshotEdgeMapper edgeMapper;
    private final RkGraphSnapshotFileMapper fileMapper;

    public GraphSnapshotService(ObjectProvider<CallGraphSnapshotProvider> providerRef,
                                RkGraphSnapshotMapper snapshotMapper,
                                RkGraphSnapshotNodeMapper nodeMapper,
                                RkGraphSnapshotEdgeMapper edgeMapper,
                                RkGraphSnapshotFileMapper fileMapper) {
        this.providerRef = providerRef;
        this.snapshotMapper = snapshotMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.fileMapper = fileMapper;
    }

    /**
     * 抓一次当前调用图快照并落库。
     *
     * @param repoId    仓库
     * @param sessionId 触发的会话（漂移归因锚点，可空）
     * @param label     人读标签（可空）
     * @return 落库后的快照摘要
     */
    public DriftViews.SnapshotView capture(long repoId, Long sessionId, String label) {
        CallGraphSnapshotProvider provider = providerRef.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException(
                    "调用图只读端口 CallGraphSnapshotProvider 未接入（等隔壁窗口实现后自动生效）");
        }
        CallGraphView view = provider.currentGraph(repoId);
        if (view == null) {
            view = CallGraphView.empty(repoId);
        }

        // 符号 id → 语义稳定 key（边的 source 靠它归一，跨快照才稳定）
        Map<Long, String> idToStableKey = new HashMap<>();
        List<RkGraphSnapshotNodeEntity> nodeRows = new ArrayList<>();
        List<String> nodeKeyHashes = new ArrayList<>();
        for (SymbolNode n : view.nodes()) {
            String stableKey = n.stableKey();
            String keyHash = sha256(stableKey);
            idToStableKey.put(n.symbolId(), stableKey);
            RkGraphSnapshotNodeEntity e = new RkGraphSnapshotNodeEntity();
            e.setRepoId(repoId);
            e.setKeyHash(keyHash);
            e.setLanguage(n.language());
            e.setSymbolType(n.symbolType());
            e.setClassName(n.className());
            e.setMethodName(n.methodName());
            e.setSignature(truncate(n.signature(), 1000));
            e.setFilePath(n.filePath());
            e.setStartLine(n.startLine());
            e.setEndLine(n.endLine());
            nodeRows.add(e);
            nodeKeyHashes.add(keyHash);
        }

        List<RkGraphSnapshotEdgeEntity> edgeRows = new ArrayList<>();
        List<String> edgeKeyHashes = new ArrayList<>();
        for (DependencyEdge d : view.edges()) {
            String sourceStableKey = idToStableKey.getOrDefault(d.sourceSymbolId(), "?" + d.sourceSymbolId());
            String edgeKey = sourceStableKey + " -> " + nz(d.targetSymbolName()) + " : " + nz(d.relationType());
            String keyHash = sha256(edgeKey);
            RkGraphSnapshotEdgeEntity e = new RkGraphSnapshotEdgeEntity();
            e.setRepoId(repoId);
            e.setKeyHash(keyHash);
            e.setSourceKeyHash(sha256(sourceStableKey));
            e.setTargetName(truncate(d.targetSymbolName(), 512));
            e.setRelationType(d.relationType());
            e.setConfidence(d.confidence());
            edgeRows.add(e);
            edgeKeyHashes.add(keyHash);
        }

        List<RkGraphSnapshotFileEntity> fileRows = new ArrayList<>();
        Set<String> distinctCommits = new LinkedHashSet<>();
        for (FileFingerprint f : view.files()) {
            RkGraphSnapshotFileEntity e = new RkGraphSnapshotFileEntity();
            e.setRepoId(repoId);
            e.setFilePath(f.filePath());
            e.setContentHash(f.contentHash());
            e.setLastCommitId(f.lastCommitId());
            e.setLineCount(f.lineCount());
            fileRows.add(e);
            if (f.lastCommitId() != null && !f.lastCommitId().isBlank()) {
                distinctCommits.add(f.lastCommitId());
            }
        }

        String graphHash = graphHash(nodeKeyHashes, edgeKeyHashes);
        String commitRef = distinctCommits.size() == 1 ? distinctCommits.iterator().next() : null;

        RkGraphSnapshotEntity prev = latestEntity(repoId);
        int seq = prev == null ? 1 : (prev.getSeq() == null ? 1 : prev.getSeq() + 1);
        String prevHash = prev == null ? null : prev.getGraphHash();

        RkGraphSnapshotEntity snap = new RkGraphSnapshotEntity();
        snap.setRepoId(repoId);
        snap.setSessionId(sessionId);
        snap.setSeq(seq);
        snap.setLabel(truncate(label, 128));
        snap.setCommitRef(commitRef);
        snap.setNodeCount(nodeRows.size());
        snap.setEdgeCount(edgeRows.size());
        snap.setFileCount(fileRows.size());
        snap.setGraphHash(graphHash);
        snap.setPrevHash(prevHash);
        snapshotMapper.insert(snap);
        Long snapshotId = snap.getId();

        for (RkGraphSnapshotNodeEntity e : nodeRows) {
            e.setSnapshotId(snapshotId);
            nodeMapper.insert(e);
        }
        for (RkGraphSnapshotEdgeEntity e : edgeRows) {
            e.setSnapshotId(snapshotId);
            edgeMapper.insert(e);
        }
        for (RkGraphSnapshotFileEntity e : fileRows) {
            e.setSnapshotId(snapshotId);
            fileMapper.insert(e);
        }

        log.info("[drift] 抓快照 repo={} seq={} 节点={} 边={} 文件={} graphHash={} prevHash={}",
                repoId, seq, nodeRows.size(), edgeRows.size(), fileRows.size(),
                shortHash(graphHash), shortHash(prevHash));
        return toView(snap);
    }

    /** 本 repo 全部快照（按 seq 升序，回放演化用）。 */
    public List<DriftViews.SnapshotView> list(long repoId) {
        List<RkGraphSnapshotEntity> rows = snapshotMapper.selectList(
                new LambdaQueryWrapper<RkGraphSnapshotEntity>()
                        .eq(RkGraphSnapshotEntity::getRepoId, repoId)
                        .orderByAsc(RkGraphSnapshotEntity::getSeq));
        List<DriftViews.SnapshotView> out = new ArrayList<>();
        for (RkGraphSnapshotEntity r : rows) {
            out.add(toView(r));
        }
        return out;
    }

    /** 本 repo 最新一份快照摘要（无则 null）。 */
    public DriftViews.SnapshotView latest(long repoId) {
        RkGraphSnapshotEntity e = latestEntity(repoId);
        return e == null ? null : toView(e);
    }

    private RkGraphSnapshotEntity latestEntity(long repoId) {
        return snapshotMapper.selectList(new LambdaQueryWrapper<RkGraphSnapshotEntity>()
                        .eq(RkGraphSnapshotEntity::getRepoId, repoId)
                        .orderByDesc(RkGraphSnapshotEntity::getSeq)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private static DriftViews.SnapshotView toView(RkGraphSnapshotEntity e) {
        return new DriftViews.SnapshotView(e.getId(), e.getRepoId(), e.getSessionId(),
                e.getSeq() == null ? 0 : e.getSeq(), e.getLabel(), e.getCommitRef(),
                e.getNodeCount() == null ? 0 : e.getNodeCount(),
                e.getEdgeCount() == null ? 0 : e.getEdgeCount(),
                e.getFileCount() == null ? 0 : e.getFileCount(),
                e.getGraphHash(), e.getPrevHash());
    }

    /**
     * 整张图的确定性哈希（时间指纹）：节点 keyHash 与边 keyHash 各自<b>排序</b>后拼接再 sha256。
     * 排序保证与符号发现顺序无关——同一张图必得同一哈希，图变了哈希才变。
     */
    private static String graphHash(List<String> nodeKeyHashes, List<String> edgeKeyHashes) {
        List<String> ns = new ArrayList<>(nodeKeyHashes);
        List<String> es = new ArrayList<>(edgeKeyHashes);
        Collections.sort(ns);
        Collections.sort(es);
        StringBuilder sb = new StringBuilder("N:");
        for (String h : ns) {
            sb.append(h).append(',');
        }
        sb.append("|E:");
        for (String h : es) {
            sb.append(h).append(',');
        }
        return sha256(sb.toString());
    }

    private static String sha256(String s) {
        return FileChangeRecorder.sha256Hex(s.getBytes(StandardCharsets.UTF_8));
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
