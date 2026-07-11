package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AgentRunEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.vo.BlastSubgraphVO;
import com.repolens.domain.vo.ChangedFileVO;
import com.repolens.domain.vo.ChangeGraphVO;
import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.GraphEdgeVO;
import com.repolens.domain.vo.GraphNodeVO;
import com.repolens.mapper.AgentRunMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.ChangeGraphService;
import com.repolens.service.CodeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 改动影响面（Blast Radius）图服务。
 * 由 runId 经 sessionId 关联 file_change_log，再经 code_file/code_symbol 找被改符号，
 * 最后对每个被改符号做 BFS 上游（callers）与下游（callees）扩展，返回可供前端渲染的影响面图。
 *
 * <p>runId → agent_run.sessionId → file_change_log.sessionId 是本实现的关联路径。
 * 可靠性依据：两张表共用同一个 session（chat_session.id），file_change_log 在 (repo_id, session_id)
 * 上有索引，数据一致性由同一次 agent loop 写入保证，无截断风险（不同于 target_files 字符串）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeGraphServiceImpl implements ChangeGraphService {

    /** 被改符号上限：超过则截断，避免单次请求跑超过 20×2 个 buildGraph。 */
    private static final int MAX_CHANGED_SYMBOLS = 20;
    /** 上/下游各方向节点上限，超限时截断并标记 truncated。 */
    private static final int MAX_BLAST_NODES = 80;
    /** BFS 深度：深度 2 给上/下游各 2 跳，对大多数调用链够用。 */
    private static final int BLAST_DEPTH = 2;
    /** 最小置信度：设为 0 以尽可能覆盖，低置信度边交给前端弱化显示。 */
    private static final double BLAST_MIN_CONF = 0.0;

    private final AgentRunMapper agentRunMapper;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final CodeSymbolMapper codeSymbolMapper;
    private final CodeFileMapper codeFileMapper;
    private final CodeGraphService codeGraphService;
    private final PermissionService permissionService;

    @Override
    public ChangeGraphVO getChangeGraph(Long userId, Long repoId, Long runId) {
        // 1. 权限校验（前置）
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }

        // 2. 取 run 记录
        AgentRunEntity run = agentRunMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Agent run not found: " + runId);
        }

        // 3. run 归属校验
        if (!repoId.equals(run.getRepoId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "Agent run does not belong to repo " + repoId);
        }

        // 4. 无 sessionId → 该 run 无文件改动上下文
        if (run.getSessionId() == null) {
            return emptyResult(Collections.emptyList());
        }

        // 5. 按 (repoId, sessionId) 取文件变更记录
        List<FileChangeLogEntity> changeLogs = fileChangeLogMapper.selectList(
                Wrappers.<FileChangeLogEntity>lambdaQuery()
                        .eq(FileChangeLogEntity::getRepoId, repoId)
                        .eq(FileChangeLogEntity::getSessionId, run.getSessionId()));
        if (changeLogs.isEmpty()) {
            return emptyResult(Collections.emptyList());
        }

        // 6. 按 filePath 去重，保留 id 最大（即最新）的一条
        Map<String, FileChangeLogEntity> latestByPath = new LinkedHashMap<>();
        for (FileChangeLogEntity cl : changeLogs) {
            latestByPath.merge(cl.getFilePath(), cl,
                    (existing, incoming) -> incoming.getId() > existing.getId() ? incoming : existing);
        }

        List<ChangedFileVO> changedFiles = latestByPath.values().stream()
                .map(cl -> ChangedFileVO.builder()
                        .filePath(cl.getFilePath())
                        .changeStatus(cl.getStatus())
                        .changeLogId(cl.getId())
                        .build())
                .collect(Collectors.toList());

        // 7. 加载 repo 所有代码文件，构建 filePath→fileId 映射
        List<CodeFileEntity> allFiles = codeFileMapper.selectList(
                Wrappers.<CodeFileEntity>lambdaQuery().eq(CodeFileEntity::getRepoId, repoId));
        if (allFiles.isEmpty()) {
            // repo 尚未解析索引，fail-safe 返回只有改动文件列表的空图
            return emptyResult(changedFiles);
        }

        Map<String, Long> pathToFileId = new HashMap<>();
        Map<Long, String> fileIdToPath = new HashMap<>();
        for (CodeFileEntity f : allFiles) {
            if (f.getFilePath() != null && f.getId() != null) {
                pathToFileId.put(f.getFilePath(), f.getId());
                fileIdToPath.put(f.getId(), f.getFilePath());
            }
        }

        // 8. 找被改文件对应的 fileId
        List<Long> changedFileIds = latestByPath.keySet().stream()
                .map(pathToFileId::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (changedFileIds.isEmpty()) {
            return emptyResult(changedFiles);
        }

        // 9. 加载被改文件的所有符号
        List<CodeSymbolEntity> symbols = codeSymbolMapper.selectList(
                Wrappers.<CodeSymbolEntity>lambdaQuery()
                        .eq(CodeSymbolEntity::getRepoId, repoId)
                        .in(CodeSymbolEntity::getFileId, changedFileIds));
        if (symbols.isEmpty()) {
            return emptyResult(changedFiles);
        }

        // (a) Symbol-list cap: record fact but keep processing the retained symbols.
        boolean symbolsTruncated = false;
        if (symbols.size() > MAX_CHANGED_SYMBOLS) {
            symbols = new ArrayList<>(symbols.subList(0, MAX_CHANGED_SYMBOLS));
            symbolsTruncated = true;
        }

        // 10. 转为 GraphNodeVO（加 changeType=MODIFIED）
        List<GraphNodeVO> changedNodes = symbols.stream()
                .map(s -> buildChangedNode(s, fileIdToPath))
                .collect(Collectors.toList());
        Set<String> changedNodeIds = changedNodes.stream()
                .map(GraphNodeVO::getId)
                .collect(Collectors.toSet());

        // 11. 对每个被改符号扩展上/下游图
        Map<String, GraphNodeVO> upstreamNodes = new LinkedHashMap<>();
        Map<String, GraphEdgeVO> upstreamEdges = new LinkedHashMap<>();
        Map<String, GraphNodeVO> downstreamNodes = new LinkedHashMap<>();
        Map<String, GraphEdgeVO> downstreamEdges = new LinkedHashMap<>();
        boolean nodeBudgetHit = false;
        boolean graphTruncated = false;

        for (CodeSymbolEntity sym : symbols) {
            // (b) Break only when BOTH direction budgets are exhausted.
            boolean upstreamFull = upstreamNodes.size() >= MAX_BLAST_NODES;
            boolean downstreamFull = downstreamNodes.size() >= MAX_BLAST_NODES;
            if (upstreamFull && downstreamFull) break;
            try {
                if (!upstreamFull) {
                    // 上游：谁调用了被改符号（callers 方向）
                    CodeGraphVO callers = codeGraphService.buildGraph(
                            userId, repoId, sym.getId(), "callers", BLAST_DEPTH, BLAST_MIN_CONF);
                    graphTruncated |= callers.isTruncated();
                    for (GraphNodeVO n : callers.getNodes()) {
                        if (!changedNodeIds.contains(n.getId())) {
                            if (upstreamNodes.size() < MAX_BLAST_NODES) {
                                upstreamNodes.putIfAbsent(n.getId(), n);
                            } else {
                                nodeBudgetHit = true;
                            }
                        }
                    }
                    for (GraphEdgeVO e : callers.getEdges()) {
                        upstreamEdges.putIfAbsent(e.getId(), e);
                    }
                }

                if (!downstreamFull) {
                    // 下游：被改符号调用了谁（callees 方向）
                    CodeGraphVO callees = codeGraphService.buildGraph(
                            userId, repoId, sym.getId(), "callees", BLAST_DEPTH, BLAST_MIN_CONF);
                    graphTruncated |= callees.isTruncated();
                    for (GraphNodeVO n : callees.getNodes()) {
                        if (!changedNodeIds.contains(n.getId())) {
                            if (downstreamNodes.size() < MAX_BLAST_NODES) {
                                downstreamNodes.putIfAbsent(n.getId(), n);
                            } else {
                                nodeBudgetHit = true;
                            }
                        }
                    }
                    for (GraphEdgeVO e : callees.getEdges()) {
                        downstreamEdges.putIfAbsent(e.getId(), e);
                    }
                }
            } catch (Exception e) {
                // fail-safe：图数据缺失时记录警告，不影响其他符号的处理
                log.warn("Failed to build blast graph for symbol {}: {}", sym.getId(), e.getMessage());
            }
        }

        // 12. (c) 过滤悬空边：仅保留 source 和 target 均在图中的边
        Set<String> upstreamNodeIdSet = new HashSet<>(upstreamNodes.keySet());
        upstreamNodeIdSet.addAll(changedNodeIds);
        List<GraphEdgeVO> filteredUpstreamEdges = upstreamEdges.values().stream()
                .filter(e -> upstreamNodeIdSet.contains(e.getSource())
                        && upstreamNodeIdSet.contains(e.getTarget()))
                .collect(Collectors.toList());

        Set<String> downstreamNodeIdSet = new HashSet<>(downstreamNodes.keySet());
        downstreamNodeIdSet.addAll(changedNodeIds);
        List<GraphEdgeVO> filteredDownstreamEdges = downstreamEdges.values().stream()
                .filter(e -> downstreamNodeIdSet.contains(e.getSource())
                        && downstreamNodeIdSet.contains(e.getTarget()))
                .collect(Collectors.toList());

        // (c) truncated if any of: symbol cap hit, node budget hit, inner buildGraph truncated
        boolean truncated = symbolsTruncated || nodeBudgetHit || graphTruncated;

        return ChangeGraphVO.builder()
                .changedFiles(changedFiles)
                .changedSymbols(changedNodes)
                .upstream(BlastSubgraphVO.builder()
                        .nodes(new ArrayList<>(upstreamNodes.values()))
                        .edges(filteredUpstreamEdges)
                        .build())
                .downstream(BlastSubgraphVO.builder()
                        .nodes(new ArrayList<>(downstreamNodes.values()))
                        .edges(filteredDownstreamEdges)
                        .build())
                .truncated(truncated)
                .build();
    }

    /** 将 CodeSymbolEntity 转为带 changeType=MODIFIED 的 GraphNodeVO。 */
    private GraphNodeVO buildChangedNode(CodeSymbolEntity s, Map<Long, String> fileIdToPath) {
        String cls = s.getClassName();
        String simpleClass = cls == null ? "" : cls.substring(cls.lastIndexOf('.') + 1);
        String methodName = s.getMethodName() == null ? "" : s.getMethodName();
        String label = simpleClass.isEmpty() ? methodName : (simpleClass + "." + methodName);
        String filePath = s.getFileId() != null ? fileIdToPath.get(s.getFileId()) : null;
        String layer = inferLayer(s.getSymbolType() == null ? null : s.getSymbolType().name(), cls);
        return GraphNodeVO.builder()
                .id(String.valueOf(s.getId()))
                .label(label.isEmpty() ? simpleClass : label)
                .className(cls)
                .methodName(s.getMethodName())
                .symbolType(s.getSymbolType() == null ? "METHOD" : s.getSymbolType().name())
                .signature(s.getSignature())
                .summary(s.getSummary())
                .filePath(filePath)
                .startLine(s.getStartLine())
                .endLine(s.getEndLine())
                .resolved(true)
                .changeType("MODIFIED")
                .layer(layer)
                .build();
    }

    /** 与 CodeGraphServiceImpl 保持一致的分层推断逻辑。 */
    private static String inferLayer(String symbolTypeName, String className) {
        String simple = className == null ? "" : className.substring(className.lastIndexOf('.') + 1);
        if ("API".equals(symbolTypeName) || simple.endsWith("Controller")) return "Controller";
        if (simple.endsWith("ServiceImpl") || simple.endsWith("Service")) return "Service";
        if (simple.endsWith("Mapper") || simple.endsWith("Repository") || simple.endsWith("Dao")) return "Mapper";
        if (simple.endsWith("Entity")) return "Entity";
        if (symbolTypeName != null) {
            return switch (symbolTypeName) {
                case "CONTROLLER" -> "Controller";
                case "SERVICE" -> "Service";
                case "MAPPER" -> "Mapper";
                case "ENTITY" -> "Entity";
                default -> symbolTypeName;
            };
        }
        return "METHOD";
    }

    private ChangeGraphVO emptyResult(List<ChangedFileVO> changedFiles) {
        BlastSubgraphVO empty = BlastSubgraphVO.builder()
                .nodes(Collections.emptyList())
                .edges(Collections.emptyList())
                .build();
        return ChangeGraphVO.builder()
                .changedFiles(changedFiles)
                .changedSymbols(Collections.emptyList())
                .upstream(empty)
                .downstream(empty)
                .truncated(false)
                .build();
    }
}
