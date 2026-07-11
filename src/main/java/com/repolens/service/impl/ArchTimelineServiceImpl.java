package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AgentRunEntity;
import com.repolens.domain.entity.CodeDependencyEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.FrameVO;
import com.repolens.domain.vo.GraphEdgeVO;
import com.repolens.domain.vo.GraphNodeVO;
import com.repolens.domain.vo.TimelineVO;
import com.repolens.mapper.AgentRunMapper;
import com.repolens.mapper.CodeDependencyMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.ArchTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Feature J: 架构时间轴回放服务实现。
 * 纯聚合 file_change_log / agent_run，不建新表。
 *
 * <p>排帧规则：agent_run 按 created_at 升序，frameIndex 从 0 起。
 * 帧内文件变动：file_change_log.agentRunId = run.id AND status IN (APPLIED, PROPOSED)，distinct filePath。
 * 累积图逻辑：0..frameIndex 各帧累积触碰 symbolId，封顶 150 按 touchCount desc，
 * changeType = 本帧触碰 ? (firstSeenFrame==frameIndex ? NEW : MODIFIED) : STABLE。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchTimelineServiceImpl implements ArchTimelineService {

    /** 累积节点封顶数，超则按 touchCount desc 截断。 */
    private static final int MAX_NODES = 150;

    private final AgentRunMapper agentRunMapper;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final CodeFileMapper codeFileMapper;
    private final CodeSymbolMapper codeSymbolMapper;
    private final CodeDependencyMapper codeDependencyMapper;
    private final PermissionService permissionService;

    // ─────────────────────────────────────────────────────────────────────────
    // getTimeline
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public TimelineVO getTimeline(Long userId, Long repoId) {
        checkPermission(userId, repoId);

        // 1. 取 agent_run 列表，按 createdAt 升序
        List<AgentRunEntity> runs = agentRunMapper.selectList(
                Wrappers.<AgentRunEntity>lambdaQuery()
                        .eq(AgentRunEntity::getRepoId, repoId)
                        .orderByAsc(AgentRunEntity::getCreatedAt));

        if (runs.isEmpty()) {
            return TimelineVO.builder()
                    .frames(Collections.emptyList())
                    .frameCount(0)
                    .historyLimited(true)
                    .build();
        }

        // 2. 加载全 repo code_file，构建 pathToFileId 映射（一次加载，全帧复用）
        Map<String, Long> pathToFileId = loadPathToFileId(repoId);

        // 3. 逐帧构建
        List<FrameVO> frames = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            AgentRunEntity run = runs.get(i);
            try {
                FrameVO frame = buildFrame(i, run, repoId, pathToFileId);
                frames.add(frame);
            } catch (Exception e) {
                // fail-safe：单帧失败跳过，不崩整个 timeline
                log.warn("[Timeline] frame {} (runId={}) failed, skipped: {}", i, run.getId(), e.getMessage());
            }
        }

        return TimelineVO.builder()
                .frames(frames)
                .frameCount(frames.size())
                .historyLimited(true)
                .build();
    }

    /**
     * 构建单帧 VO。
     */
    private FrameVO buildFrame(int frameIndex, AgentRunEntity run, Long repoId,
                               Map<String, Long> pathToFileId) {
        // 帧内改动文件路径（distinct，status IN APPLIED/PROPOSED）
        List<String> changedFilePaths = getChangedFilePaths(repoId, run.getId());

        // 帧内触碰符号数：filePath → fileId → code_symbol count
        int touchedSymbolCount = countSymbols(repoId, changedFilePaths, pathToFileId);

        String createdAt = run.getCreatedAt() != null ? run.getCreatedAt().toString() : null;

        return FrameVO.builder()
                .frameIndex(frameIndex)
                .agentRunId(run.getId())
                .sessionId(run.getSessionId())
                .createdAt(createdAt)
                .changedFilePaths(changedFilePaths)
                .changedFileCount(changedFilePaths.size())
                .touchedSymbolCount(touchedSymbolCount)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getFrameGraph
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public CodeGraphVO getFrameGraph(Long userId, Long repoId, int frameIndex) {
        checkPermission(userId, repoId);

        // 1. 取 agent_run 列表，按 createdAt 升序
        List<AgentRunEntity> runs = agentRunMapper.selectList(
                Wrappers.<AgentRunEntity>lambdaQuery()
                        .eq(AgentRunEntity::getRepoId, repoId)
                        .orderByAsc(AgentRunEntity::getCreatedAt));

        // 2. 越界检查 → NOT_FOUND
        if (runs.isEmpty() || frameIndex < 0 || frameIndex >= runs.size()) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "frameIndex " + frameIndex + " out of range (total frames: " + runs.size() + ")");
        }

        // 3. 加载全 repo code_file 映射
        Map<String, Long> pathToFileId = loadPathToFileId(repoId);
        Map<Long, String> fileIdToPath = new HashMap<>();
        for (Map.Entry<String, Long> e : pathToFileId.entrySet()) {
            fileIdToPath.put(e.getValue(), e.getKey());
        }

        // 4. 累积逻辑：遍历 0..frameIndex 各帧
        // Map<symbolId, firstSeenFrame>  Map<symbolId, touchCount>
        Map<Long, Integer> firstSeenFrame = new LinkedHashMap<>();
        Map<Long, Integer> touchCount = new LinkedHashMap<>();
        Set<Long> touchedInLastFrame = new HashSet<>(); // 第 frameIndex 帧触碰的 symbolId

        for (int f = 0; f <= frameIndex; f++) {
            AgentRunEntity run = runs.get(f);
            try {
                Set<Long> symbolIds = getSymbolIdsForRun(repoId, run.getId(), pathToFileId);
                for (Long symId : symbolIds) {
                    firstSeenFrame.putIfAbsent(symId, f);
                    touchCount.merge(symId, 1, Integer::sum);
                }
                if (f == frameIndex) {
                    touchedInLastFrame.addAll(symbolIds);
                }
            } catch (Exception e) {
                // fail-safe：单帧符号映射失败跳过，不崩整个图
                log.warn("[FrameGraph] frame {} (runId={}) symbol mapping failed, skipped: {}",
                        f, run.getId(), e.getMessage());
            }
        }

        // 5. 如果累积为空，返回空图
        if (firstSeenFrame.isEmpty()) {
            return emptyGraph();
        }

        // 6. 封顶 150，按 touchCount desc 截断
        boolean truncated = false;
        List<Long> allSymbolIds = new ArrayList<>(touchCount.keySet());
        allSymbolIds.sort(Comparator.comparingInt((Long id) -> touchCount.getOrDefault(id, 0)).reversed());

        if (allSymbolIds.size() > MAX_NODES) {
            allSymbolIds = allSymbolIds.subList(0, MAX_NODES);
            truncated = true;
        }
        Set<Long> nodeSymbolIds = new HashSet<>(allSymbolIds);

        // 7. 批量加载 symbolId → CodeSymbolEntity
        Map<Long, CodeSymbolEntity> symbolMap = loadSymbols(repoId, nodeSymbolIds);

        // 8. 构建节点
        List<GraphNodeVO> nodes = new ArrayList<>();
        for (Long symId : allSymbolIds) {
            try {
                CodeSymbolEntity sym = symbolMap.get(symId);
                if (sym == null) continue; // 数据缺失跳过

                int fsf = firstSeenFrame.getOrDefault(symId, 0);
                int tc = touchCount.getOrDefault(symId, 0);
                String changeType;
                if (touchedInLastFrame.contains(symId)) {
                    changeType = (fsf == frameIndex) ? "NEW" : "MODIFIED";
                } else {
                    changeType = "STABLE";
                }

                String cls = sym.getClassName();
                String simpleClass = cls == null ? "" : cls.substring(cls.lastIndexOf('.') + 1);
                String methodName = sym.getMethodName() == null ? "" : sym.getMethodName();
                String label = simpleClass.isEmpty() ? methodName
                        : (methodName.isEmpty() ? simpleClass : simpleClass + "." + methodName);
                String filePath = sym.getFileId() != null ? fileIdToPath.get(sym.getFileId()) : null;
                String layer = inferLayer(sym.getSymbolType() == null ? null : sym.getSymbolType().name(), cls);

                nodes.add(GraphNodeVO.builder()
                        .id(String.valueOf(symId))
                        .label(label.isEmpty() ? String.valueOf(symId) : label)
                        .className(cls)
                        .methodName(sym.getMethodName())
                        .symbolType(sym.getSymbolType() == null ? "METHOD" : sym.getSymbolType().name())
                        .signature(sym.getSignature())
                        .summary(sym.getSummary())
                        .filePath(filePath)
                        .startLine(sym.getStartLine())
                        .endLine(sym.getEndLine())
                        .resolved(true)
                        .changeType(changeType)
                        .layer(layer)
                        .firstSeenFrame(fsf)
                        .touchCount(tc)
                        .build());
            } catch (Exception e) {
                // fail-safe：单符号构建失败跳过
                log.warn("[FrameGraph] symbol {} node build failed, skipped: {}", symId, e.getMessage());
            }
        }

        // 9. 构建边（P1 简化：code_dependency sourceSymbolId∈节点集，targetSymbolName∈节点符号名集合）
        List<GraphEdgeVO> edges = buildEdges(repoId, nodeSymbolIds, symbolMap);

        return CodeGraphVO.builder()
                .nodes(nodes)
                .edges(edges)
                .nodeCount(nodes.size())
                .edgeCount(edges.size())
                .truncated(truncated)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void checkPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
    }

    /**
     * 加载 repo 所有 code_file，构建 filePath→fileId 映射。
     */
    private Map<String, Long> loadPathToFileId(Long repoId) {
        List<CodeFileEntity> files = codeFileMapper.selectList(
                Wrappers.<CodeFileEntity>lambdaQuery().eq(CodeFileEntity::getRepoId, repoId));
        Map<String, Long> map = new HashMap<>();
        for (CodeFileEntity f : files) {
            if (f.getFilePath() != null && f.getId() != null) {
                map.put(f.getFilePath(), f.getId());
            }
        }
        return map;
    }

    /**
     * 查询某帧的 distinct changedFilePaths（status IN APPLIED/PROPOSED）。
     */
    private List<String> getChangedFilePaths(Long repoId, Long agentRunId) {
        List<FileChangeLogEntity> logs = fileChangeLogMapper.selectList(
                Wrappers.<FileChangeLogEntity>lambdaQuery()
                        .eq(FileChangeLogEntity::getRepoId, repoId)
                        .eq(FileChangeLogEntity::getAgentRunId, agentRunId)
                        .in(FileChangeLogEntity::getStatus,
                                FileChangeLogEntity.STATUS_APPLIED, FileChangeLogEntity.STATUS_PROPOSED));
        return logs.stream()
                .map(FileChangeLogEntity::getFilePath)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 统计 filePaths 对应的 code_symbol 数量（用于 getTimeline 的 touchedSymbolCount）。
     */
    private int countSymbols(Long repoId, List<String> filePaths, Map<String, Long> pathToFileId) {
        if (filePaths.isEmpty()) return 0;
        List<Long> fileIds = filePaths.stream()
                .map(pathToFileId::get)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (fileIds.isEmpty()) return 0;
        long count = codeSymbolMapper.selectCount(
                Wrappers.<CodeSymbolEntity>lambdaQuery()
                        .eq(CodeSymbolEntity::getRepoId, repoId)
                        .in(CodeSymbolEntity::getFileId, fileIds));
        return (int) count;
    }

    /**
     * 获取某帧触碰的 symbolId 集合（filePath→fileId→symbolId 映射）。
     */
    private Set<Long> getSymbolIdsForRun(Long repoId, Long agentRunId, Map<String, Long> pathToFileId) {
        List<String> filePaths = getChangedFilePaths(repoId, agentRunId);
        if (filePaths.isEmpty()) return Collections.emptySet();

        List<Long> fileIds = filePaths.stream()
                .map(pathToFileId::get)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (fileIds.isEmpty()) return Collections.emptySet();

        List<CodeSymbolEntity> symbols = codeSymbolMapper.selectList(
                Wrappers.<CodeSymbolEntity>lambdaQuery()
                        .eq(CodeSymbolEntity::getRepoId, repoId)
                        .in(CodeSymbolEntity::getFileId, fileIds));
        return symbols.stream()
                .map(CodeSymbolEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 批量加载 symbolId → CodeSymbolEntity（按 repoId + id IN）。
     */
    private Map<Long, CodeSymbolEntity> loadSymbols(Long repoId, Set<Long> symbolIds) {
        if (symbolIds.isEmpty()) return Collections.emptyMap();
        List<CodeSymbolEntity> list = codeSymbolMapper.selectList(
                Wrappers.<CodeSymbolEntity>lambdaQuery()
                        .eq(CodeSymbolEntity::getRepoId, repoId)
                        .in(CodeSymbolEntity::getId, symbolIds));
        Map<Long, CodeSymbolEntity> map = new HashMap<>();
        for (CodeSymbolEntity s : list) {
            map.put(s.getId(), s);
        }
        return map;
    }

    /**
     * P1 边构建：code_dependency.sourceSymbolId∈节点集 且 targetSymbolName 匹配节点内某符号。
     * 匹配规则：methodName、className 简名、或 signature 包含 targetSymbolName。
     */
    private List<GraphEdgeVO> buildEdges(Long repoId, Set<Long> nodeSymbolIds,
                                         Map<Long, CodeSymbolEntity> symbolMap) {
        if (nodeSymbolIds.isEmpty()) return Collections.emptyList();

        // 构建目标符号名称集（用于快速查找）
        // symbolName → symbolId（按 className 简名 + methodName）
        Map<String, Long> nameToSymbolId = new HashMap<>();
        for (Map.Entry<Long, CodeSymbolEntity> e : symbolMap.entrySet()) {
            CodeSymbolEntity sym = e.getValue();
            if (sym.getMethodName() != null && !sym.getMethodName().isBlank()) {
                nameToSymbolId.putIfAbsent(sym.getMethodName(), e.getKey());
            }
            if (sym.getClassName() != null) {
                String simple = sym.getClassName().substring(sym.getClassName().lastIndexOf('.') + 1);
                nameToSymbolId.putIfAbsent(simple, e.getKey());
            }
        }

        // 查 code_dependency：sourceSymbolId IN 节点集
        List<CodeDependencyEntity> deps = codeDependencyMapper.selectList(
                Wrappers.<CodeDependencyEntity>lambdaQuery()
                        .eq(CodeDependencyEntity::getRepoId, repoId)
                        .in(CodeDependencyEntity::getSourceSymbolId, nodeSymbolIds));

        List<GraphEdgeVO> edges = new ArrayList<>();
        Set<String> edgeIdSet = new HashSet<>();
        for (CodeDependencyEntity dep : deps) {
            try {
                Long targetId = nameToSymbolId.get(dep.getTargetSymbolName());
                if (targetId == null || !nodeSymbolIds.contains(targetId)) continue;
                // source 就是 dep.getSourceSymbolId()，target 是 targetId
                String edgeId = dep.getSourceSymbolId() + "->" + targetId;
                if (edgeIdSet.add(edgeId)) {
                    edges.add(GraphEdgeVO.builder()
                            .id(edgeId)
                            .source(String.valueOf(dep.getSourceSymbolId()))
                            .target(String.valueOf(targetId))
                            .relationType(dep.getRelationType())
                            .confidence(dep.getConfidence() == null ? 0.0 : dep.getConfidence().doubleValue())
                            .build());
                }
            } catch (Exception e) {
                log.warn("[FrameGraph] edge build failed for dep {}: {}", dep.getId(), e.getMessage());
            }
        }
        return edges;
    }

    private CodeGraphVO emptyGraph() {
        return CodeGraphVO.builder()
                .nodes(Collections.emptyList())
                .edges(Collections.emptyList())
                .nodeCount(0)
                .edgeCount(0)
                .truncated(false)
                .build();
    }

    /** 分层推断（与 ChangeGraphServiceImpl 保持一致）。 */
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
}
