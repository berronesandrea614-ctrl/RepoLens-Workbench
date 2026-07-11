package com.repolens.bridge;

import com.repolens.common.result.Result;
import com.repolens.kernel.drift.ArchDriftDetector;
import com.repolens.kernel.drift.DriftViews.DriftReport;
import com.repolens.kernel.drift.DriftViews.EvolutionTimeline;
import com.repolens.kernel.drift.DriftViews.SnapshotView;
import com.repolens.kernel.drift.GraphSnapshotService;
import com.repolens.security.AuthUserId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * M9 架构漂移时间维度的 REST 接缝（bridge zone）——薄转发到内核 {@link GraphSnapshotService} /
 * {@link ArchDriftDetector}。
 *
 * <p>调用图无历史，内核在此沿时间抓快照（图哈希当时间指纹 + prev_hash 审计链），跨快照做结构漂移比对、
 * 归因到会话/commit。数据经只读端口 {@code CallGraphSnapshotProvider}（隔壁实现）拉当前图，内核零引用其表。
 */
@RestController
@RequestMapping("/api/repos/{repoId}/drift")
public class KernelDriftController {

    private final GraphSnapshotService snapshotService;
    private final ArchDriftDetector driftDetector;

    public KernelDriftController(GraphSnapshotService snapshotService, ArchDriftDetector driftDetector) {
        this.snapshotService = snapshotService;
        this.driftDetector = driftDetector;
    }

    /** 请求体：比对两快照。 */
    public record DetectRequest(Long fromSnapshotId, Long toSnapshotId) {
    }

    /** 抓一次当前调用图快照（图哈希 + 审计链），落库。 */
    @PostMapping("/snapshots")
    public Result<SnapshotView> capture(@AuthUserId Long userId,
                                        @PathVariable Long repoId,
                                        @RequestParam(required = false) Long sessionId,
                                        @RequestParam(required = false) String label) {
        return Result.success(snapshotService.capture(repoId, sessionId, label));
    }

    /** 本 repo 全部快照（按 seq 升序，回放演化用）。 */
    @GetMapping("/snapshots")
    public Result<List<SnapshotView>> list(@AuthUserId Long userId, @PathVariable Long repoId) {
        return Result.success(snapshotService.list(repoId));
    }

    /** 沿时间回放：相邻快照两两比对出的演化时间线。 */
    @GetMapping("/evolution")
    public Result<EvolutionTimeline> evolution(@AuthUserId Long userId, @PathVariable Long repoId) {
        return Result.success(driftDetector.evolution(repoId));
    }

    /** 比对两快照，检出漂移并落库。 */
    @PostMapping("/detect")
    public Result<DriftReport> detect(@AuthUserId Long userId,
                                      @PathVariable Long repoId,
                                      @RequestBody DetectRequest req) {
        return Result.success(driftDetector.detect(req.fromSnapshotId(), req.toSnapshotId()));
    }
}
