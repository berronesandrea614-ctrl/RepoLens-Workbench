package com.repolens.bridge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.kernel.drift.GraphSnapshotService;
import com.repolens.kernel.persistence.entity.RkFileChangeEntity;
import com.repolens.kernel.persistence.mapper.RkFileChangeMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.service.ComprehensionDebtService;
import com.repolens.service.JavaCodeParseService;
import com.repolens.service.SensitiveFileService;
import com.repolens.service.impl.SidecarCodeParseService;
import com.repolens.service.support.RepoWorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内核改动 → 左侧工具应用表的<b>数据同步桥</b>（把自研 AI 内核的改动喂给左侧工具）。
 *
 * <p><b>根因</b>：内核 agent 直接改工作目录、把改动记进内核表 {@code rk_file_change}（+git），
 * 但左侧工具读的是<em>应用表</em>：{@code file_change_log}（需求流/时间轴/churn）、
 * {@code comprehension_debt_file}（aiRatio/债务）、{@code code_symbol}/{@code code_dependency}
 * （调用图/fan-in）。两套表之间没有桥，导致 AI 改完代码后左侧工具全不更新
 * （churn 全 100% 只因一条旧记录、aiRatio 全 0 因没记 AI 归属、评分全 20.0 因债务从不重算）。
 *
 * <p><b>本桥</b>在一次 agent run 收尾后被调用（{@link KernelAgentService#runAgent} 里
 * {@code safeFinish} 之后），做两件事：
 * <ol>
 *   <li><b>同步、轻量</b>：把本次 run 在 {@code rk_file_change} 里记录的改过的文件，
 *       upsert 成 {@code file_change_log} 里的「已应用(APPLIED)」记录、带上 agentRunId、
 *       并写入当前磁盘文件内容（S1 债务信号靠 new/old 内容估算净改动行）——这样需求流/
 *       时间轴/churn 看得到 AI 改动、aiRatio 才不为 0。</li>
 *   <li><b>异步、fail-safe</b>：fire-and-forget 后台线程触发多语言符号重解析（调用图/fan-in）、
 *       理解债务重算、敏感文件重打分、调用图快照。全 try/catch，任一失败只记 warn，
 *       绝不阻塞 agent 的 SSE 响应、绝不因某个 service 抛异常让 agent run 失败。</li>
 * </ol>
 *
 * <p><b>护栏</b>：只 CALL 现有 public 服务，绝不修改隔壁索引流水线类的内部实现。
 * 调用图快照依赖隔壁的只读端口（{@code CallGraphSnapshotProvider}，{@link ObjectProvider} 软依赖），
 * 未接入时静默跳过、不报错。
 */
@Service
public class KernelAppBridge {

    private static final Logger log = LoggerFactory.getLogger(KernelAppBridge.class);

    /** 后台重算线程池（守护线程，绝不阻塞主链路 / SSE）。 */
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "kernel-app-bridge");
        t.setDaemon(true);
        return t;
    });

    private final RkFileChangeMapper rkFileChangeMapper;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final RepoMapper repoMapper;
    private final RepoWorkspaceResolver repoWorkspaceResolver;

    private final JavaCodeParseService javaCodeParseService;
    private final SidecarCodeParseService sidecarCodeParseService;
    private final ComprehensionDebtService comprehensionDebtService;
    private final SensitiveFileService sensitiveFileService;
    /** 软依赖：GraphSnapshotService 自身依赖尚可能未接入的只读端口，用 ObjectProvider 容缺。 */
    private final ObjectProvider<GraphSnapshotService> graphSnapshotServiceRef;
    /** 需求归纳：把本次问答/改动归纳成「需求」卡片入需求流（LLM 自动过滤无意义的）。 */
    private final com.repolens.service.impl.support.RequirementExtractor requirementExtractor;
    private final com.repolens.service.RequirementService requirementService;

    public KernelAppBridge(RkFileChangeMapper rkFileChangeMapper,
                           FileChangeLogMapper fileChangeLogMapper,
                           RepoMapper repoMapper,
                           RepoWorkspaceResolver repoWorkspaceResolver,
                           JavaCodeParseService javaCodeParseService,
                           SidecarCodeParseService sidecarCodeParseService,
                           ComprehensionDebtService comprehensionDebtService,
                           SensitiveFileService sensitiveFileService,
                           ObjectProvider<GraphSnapshotService> graphSnapshotServiceRef,
                           com.repolens.service.impl.support.RequirementExtractor requirementExtractor,
                           com.repolens.service.RequirementService requirementService) {
        this.rkFileChangeMapper = rkFileChangeMapper;
        this.fileChangeLogMapper = fileChangeLogMapper;
        this.repoMapper = repoMapper;
        this.repoWorkspaceResolver = repoWorkspaceResolver;
        this.javaCodeParseService = javaCodeParseService;
        this.sidecarCodeParseService = sidecarCodeParseService;
        this.comprehensionDebtService = comprehensionDebtService;
        this.sensitiveFileService = sensitiveFileService;
        this.graphSnapshotServiceRef = graphSnapshotServiceRef;
        this.requirementExtractor = requirementExtractor;
        this.requirementService = requirementService;
    }

    /**
     * 需求归纳（异步 fail-safe）：把本次问答归纳成「需求」卡片入需求流。LLM 抽取器自动过滤无意义的追问，
     * 只有真代表一次需求/改动才入列。与文件改动无关（Q&A 也可能产出需求卡）。
     */
    public void extractRequirementAsync(Long repoId, Long userId, Long sessionId, Long runId,
                                        String question, String answer) {
        if (repoId == null || question == null || answer == null || answer.isBlank()) {
            return;
        }
        pool.submit(() -> {
            try {
                requirementExtractor.extract(question, answer).ifPresent(note ->
                        requirementService.enqueue(userId, repoId, sessionId, note.title(), note.summary(),
                                java.util.List.of(), runId, note.approach()));
            } catch (Exception e) {
                log.warn("[app-bridge] 需求归纳失败(忽略) repoId={} runId={}: {}", repoId, runId, e.getMessage());
            }
        });
    }

    /**
     * 一次 agent run 收尾后同步左侧工具应用表。<b>本方法整体 fail-safe</b>：
     * 同步部分只做轻量的 file_change_log 落库，重活全部丢后台线程；无论如何都不抛出。
     *
     * @param repoId    仓库 id
     * @param userId    调用方 user id（重解析/重打分权限校验用）
     * @param sessionId 本次会话 id
     * @param runId     本次 agent run id（file_change_log 的 AI 归属锚点）
     */
    public void syncAfterRun(Long repoId, Long userId, Long sessionId, Long runId) {
        if (repoId == null || runId == null) {
            return;
        }
        // 1) 同步、轻量：rk_file_change → file_change_log（标 APPLIED + agentRunId + 当前磁盘内容）
        List<String> changedRelPaths;
        try {
            changedRelPaths = mirrorRkChangesToAppLog(repoId, sessionId, runId);
        } catch (Exception e) {
            log.warn("[app-bridge] rk_file_change → file_change_log 同步失败 repoId={} runId={}: {}",
                    repoId, runId, e.getMessage());
            changedRelPaths = List.of();
        }

        if (changedRelPaths.isEmpty()) {
            // 本次 run 没有改动文件：无需触发任何重算（省掉整轮重解析）。
            log.debug("[app-bridge] run 无文件改动，跳过重算 repoId={} runId={}", repoId, runId);
            return;
        }

        // 2) 异步、fail-safe：重解析 / 债务 / 敏感文件 / 调用图快照，全后台，一次 run 触发一次。
        triggerRecomputeAsync(repoId, userId, sessionId);
    }

    /**
     * 读本次 run 在 {@code rk_file_change} 里记录的改过的文件，对每个文件在 {@code file_change_log}
     * 里 upsert 一条「已应用」记录，标 AI 归属（agentRunId 非空即为 AI 生成的改动，aiRatio 靠此算）。
     *
     * <p>写入当前磁盘文件内容作 {@code newContent}：理解债务 S1 信号靠 new/old 内容估算净改动行，
     * 若只写空内容则 S1=0、aiRatio 仍为 0，达不到目的。直接编辑模式下工作目录即改动落地处，
     * 读盘即得最新内容。读盘失败降级为空内容（至少 churn/需求流/时间轴仍看得到该改动）。
     *
     * @return 本次同步涉及的相对路径列表（空 = 无改动，用于决定是否触发重算）
     */
    private List<String> mirrorRkChangesToAppLog(Long repoId, Long sessionId, Long runId) {
        List<RkFileChangeEntity> rkChanges = rkFileChangeMapper.selectList(
                new LambdaQueryWrapper<RkFileChangeEntity>()
                        .eq(RkFileChangeEntity::getRepoId, repoId)
                        .eq(RkFileChangeEntity::getRunId, runId));
        if (rkChanges == null || rkChanges.isEmpty()) {
            return List.of();
        }

        // 每个文件在本次 run 内只 upsert 一条（同一文件多次改动折叠成最终态），按路径去重取最后一条。
        Map<String, RkFileChangeEntity> latestByPath = new LinkedHashMap<>();
        for (RkFileChangeEntity c : rkChanges) {
            if (c.getFilePath() != null) {
                latestByPath.put(c.getFilePath(), c);
            }
        }

        Path repoDir = resolveRepoDirQuietly(repoId);
        List<String> synced = new ArrayList<>();
        for (Map.Entry<String, RkFileChangeEntity> entry : latestByPath.entrySet()) {
            String relPath = entry.getKey();
            RkFileChangeEntity rk = entry.getValue();
            try {
                upsertAppLog(repoId, sessionId, runId, relPath, rk.getOpType(), repoDir);
                synced.add(relPath);
            } catch (Exception e) {
                log.warn("[app-bridge] file_change_log upsert 失败 repoId={} path={}: {}",
                        repoId, relPath, e.getMessage());
            }
        }
        log.info("[app-bridge] 同步 AI 改动 {} 文件 → file_change_log (APPLIED, agentRunId={})",
                synced.size(), runId);
        return synced;
    }

    /** upsert 单条 file_change_log：同 (repoId, sessionId, filePath, agentRunId) 已存在则更新，否则插入。 */
    private void upsertAppLog(Long repoId, Long sessionId, Long runId, String relPath,
                             String opType, Path repoDir) {
        boolean deleted = "DELETE".equalsIgnoreCase(opType);
        String newContent = deleted ? "" : readDiskContentQuietly(repoDir, relPath);

        FileChangeLogEntity existing = fileChangeLogMapper.selectOne(
                new LambdaQueryWrapper<FileChangeLogEntity>()
                        .eq(FileChangeLogEntity::getRepoId, repoId)
                        .eq(sessionId != null, FileChangeLogEntity::getSessionId, sessionId)
                        .eq(FileChangeLogEntity::getFilePath, relPath)
                        .eq(FileChangeLogEntity::getAgentRunId, runId)
                        .last("LIMIT 1"));

        String appOpType = mapOpType(opType);
        if (existing != null) {
            existing.setNewContent(newContent);
            existing.setStatus(FileChangeLogEntity.STATUS_APPLIED);
            existing.setOpType(appOpType);
            existing.setAgentRunId(runId);
            existing.setReverted(0);
            fileChangeLogMapper.updateById(existing);
            return;
        }

        FileChangeLogEntity row = new FileChangeLogEntity();
        row.setRepoId(repoId);
        row.setSessionId(sessionId);
        row.setFilePath(relPath);
        // oldContent 内核表只留 hash 不留全文，这里无从取旧全文；置空。S1 用 max(0, new-old) 仍成立
        // （create/新增净增行照算；overwrite 无旧内容时 = new 行数，属保守高估，宁可让 AI 改动被看见）。
        row.setOldContent("");
        row.setNewContent(newContent);
        // AI 归属：agentRunId 非空 = 本条为自研 AI 内核生成的改动（左侧 aiRatio/债务 S1 据此认 AI）。
        row.setAgentRunId(runId);
        row.setStatus(FileChangeLogEntity.STATUS_APPLIED);
        row.setOpType(appOpType);
        row.setReverted(0);
        row.setWrittenToShadow(0);
        row.setCreatedAt(LocalDateTime.now());
        fileChangeLogMapper.insert(row);
    }

    /** rk_file_change 的 opType（WRITE/CREATE/DELETE/RENAME）映射到 file_change_log 的 opType 常量。 */
    private String mapOpType(String rkOpType) {
        if (rkOpType == null) {
            return FileChangeLogEntity.OP_TYPE_WRITE;
        }
        switch (rkOpType.toUpperCase()) {
            case "CREATE":
                return FileChangeLogEntity.OP_TYPE_CREATE;
            case "DELETE":
                return FileChangeLogEntity.OP_TYPE_DELETE;
            default:
                // WRITE / RENAME / 未知 → 归为覆盖写
                return FileChangeLogEntity.OP_TYPE_WRITE;
        }
    }

    /**
     * 后台触发重算/重解析，全部 fire-and-forget + try/catch fail-safe。
     * 一次 run 只触发一轮（本方法由 syncAfterRun 每次 run 收尾调一次）。
     */
    private void triggerRecomputeAsync(Long repoId, Long userId, Long sessionId) {
        pool.submit(() -> {
            // 2a) 多语言符号重解析：刷新 code_symbol / code_dependency（调用图、fan-in）。
            // 先 Java 全量解析（其实现开头会清空本 repo 全部符号），再 sidecar 补 TS/JS 等多语言符号——
            // 顺序与 SyncIndexOrchestrator 一致（sidecar 必须在 Java 之后，否则被清空）。
            reparseSymbolsSafe(repoId, userId);

            // 2b) 理解债务重算（aiRatio / 债务分 / 评分）。materializeAsync 自身即失败安全的异步预热。
            try {
                comprehensionDebtService.materializeAsync(repoId, userId);
            } catch (Exception e) {
                log.warn("[app-bridge] 债务重算触发失败 repoId={}: {}", repoId, e.getMessage());
            }

            // 2c) 敏感文件重打分（注意签名参数顺序：userId, repoId）。
            try {
                sensitiveFileService.recompute(userId, repoId);
            } catch (Exception e) {
                log.warn("[app-bridge] 敏感文件重打分失败 repoId={}: {}", repoId, e.getMessage());
            }

            // 2d) 调用图快照（时间轴用）：软依赖，端口未接入时 capture 会抛 IllegalStateException，静默跳过。
            captureGraphSnapshotSafe(repoId, sessionId);
        });
    }

    /** 重解析符号（Java 全量 → sidecar 多语言），每步独立 try/catch，互不影响。 */
    private void reparseSymbolsSafe(Long repoId, Long userId) {
        try {
            javaCodeParseService.parseRepository(repoId, userId);
        } catch (Exception e) {
            log.warn("[app-bridge] Java 符号重解析失败 repoId={}: {}", repoId, e.getMessage());
        }
        try {
            sidecarCodeParseService.parseRepository(repoId);
        } catch (Exception e) {
            log.warn("[app-bridge] 多语言(sidecar)符号重解析失败 repoId={}: {}", repoId, e.getMessage());
        }
    }

    /** 抓一次调用图快照；端口（CallGraphSnapshotProvider）未接入或 bean 缺失时静默跳过。 */
    private void captureGraphSnapshotSafe(Long repoId, Long sessionId) {
        GraphSnapshotService svc = graphSnapshotServiceRef.getIfAvailable();
        if (svc == null) {
            return;
        }
        try {
            svc.capture(repoId, sessionId, "agent-run");
        } catch (Exception e) {
            // 端口未接入是预期内的软失败（"调用图只读端口未接入"），debug 即可。
            log.debug("[app-bridge] 调用图快照跳过 repoId={}: {}", repoId, e.getMessage());
        }
    }

    /** 解析 repo 读目录（AI 改动落地处）；失败返回 null（读盘内容降级为空）。 */
    private Path resolveRepoDirQuietly(Long repoId) {
        try {
            RepoEntity repo = repoMapper.selectById(repoId);
            if (repo == null) {
                return null;
            }
            return repoWorkspaceResolver.resolveReadDirectory(repo);
        } catch (Exception e) {
            log.debug("[app-bridge] 解析 repoDir 失败 repoId={}: {}", repoId, e.getMessage());
            return null;
        }
    }

    /** 读当前磁盘文件内容（直接编辑模式下即最新 AI 改动）；失败/越界降级为空字符串。 */
    private String readDiskContentQuietly(Path repoDir, String relPath) {
        if (repoDir == null || relPath == null) {
            return "";
        }
        try {
            Path file = repoDir.resolve(relPath).normalize();
            // 防越界：解析后的路径必须仍在 repoDir 下。
            if (!file.startsWith(repoDir.normalize())) {
                return "";
            }
            if (!Files.isRegularFile(file)) {
                return "";
            }
            return Files.readString(file);
        } catch (Exception e) {
            log.debug("[app-bridge] 读盘取内容失败 path={}: {}", relPath, e.getMessage());
            return "";
        }
    }
}
