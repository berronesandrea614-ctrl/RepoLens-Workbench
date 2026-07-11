package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.CodeDependencyEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.ComprehensionDebtFileEntity;
import com.repolens.domain.enums.SymbolType;
import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.GraphEdgeVO;
import com.repolens.domain.vo.GraphNodeVO;
import com.repolens.mapper.CodeDependencyMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.ComprehensionDebtFileMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.CodeGraphService;
import com.repolens.service.support.TargetSymbolResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 静态近似调用图构建：以某符号为根，N 层 BFS 展开调用/被调用关系。
 * 节点=符号（解析不到的外部 target 作 ext 叶节点），边=依赖（带 relationType + confidence）。
 */
@Service
@RequiredArgsConstructor
public class CodeGraphServiceImpl implements CodeGraphService {

    private static final int MAX_NODES = 150;
    private static final int MAX_DEPTH = 4;
    // AST 符号解析得到的高置信度阈值（parse 阶段解析成功=0.95），此类依赖优先取单个最佳匹配。
    private static final double HIGH_CONFIDENCE = 0.9;

    private final PermissionService permissionService;
    private final CodeSymbolMapper codeSymbolMapper;
    private final CodeDependencyMapper codeDependencyMapper;
    private final CodeFileMapper codeFileMapper;
    private final ComprehensionDebtFileMapper debtFileMapper;

    @Override
    public CodeGraphVO buildGraph(Long userId, Long repoId, Long rootSymbolId,
                                  String direction, int depth, double minConfidence) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
        CodeSymbolEntity root = codeSymbolMapper.selectById(rootSymbolId);
        if (root == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Symbol not found: " + rootSymbolId);
        }
        if (root.getRepoId() != null && !root.getRepoId().equals(repoId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "Symbol not found: " + rootSymbolId);
        }
        boolean callees = !"callers".equalsIgnoreCase(direction);
        int boundedDepth = Math.max(1, Math.min(MAX_DEPTH, depth));
        double minConf = Math.max(0.0, Math.min(1.0, minConfidence));

        List<CodeSymbolEntity> allSymbols = codeSymbolMapper.selectList(
                Wrappers.<CodeSymbolEntity>lambdaQuery().eq(CodeSymbolEntity::getRepoId, repoId));
        Map<Long, CodeSymbolEntity> symbolById = new HashMap<>();
        for (CodeSymbolEntity s : allSymbols) {
            if (s.getId() != null) symbolById.put(s.getId(), s);
        }
        // 每请求新建，避免共享可变索引在并发下竞争
        TargetSymbolResolver targetSymbolResolver = new TargetSymbolResolver();
        targetSymbolResolver.index(allSymbols);

        List<CodeDependencyEntity> allDeps = codeDependencyMapper.selectList(
                Wrappers.<CodeDependencyEntity>lambdaQuery().eq(CodeDependencyEntity::getRepoId, repoId));
        // 预解析每条依赖的 target 符号 id。
        // 高置信度（AST 解析出的全限定名）依赖优先取单个最佳匹配，避免同名方法过度扇出；
        // 低置信度（文本兜底 ~0.5）依赖保持多匹配（本就会被 minConfidence 过滤或前端虚线弱化）。
        Map<Long, List<Long>> depResolvedTargets = new HashMap<>();
        for (CodeDependencyEntity d : allDeps) {
            List<Long> resolved = normConf(d.getConfidence()) >= HIGH_CONFIDENCE
                    ? targetSymbolResolver.resolveBest(d.getTargetSymbolName())
                    : targetSymbolResolver.resolve(d.getTargetSymbolName());
            depResolvedTargets.put(d.getId(), resolved);
        }

        // Build adjacency maps for O(1) BFS lookup (eliminates the O(V·E) inner loop over allDeps).
        // calleeAdj: sourceId → list of {dep, resolvedTargetIds}
        // callerAdj: resolvedTargetId → list of {dep, sourceId}
        Map<Long, List<Object[]>> calleeAdj = new HashMap<>();
        Map<Long, List<Object[]>> callerAdj = new HashMap<>();
        for (CodeDependencyEntity dep : allDeps) {
            List<Long> targets = depResolvedTargets.getOrDefault(dep.getId(), List.of());
            Long srcId = dep.getSourceSymbolId();
            if (srcId != null) {
                calleeAdj.computeIfAbsent(srcId, k -> new ArrayList<>())
                        .add(new Object[]{dep, targets});
            }
            for (Long targetId : targets) {
                callerAdj.computeIfAbsent(targetId, k -> new ArrayList<>())
                        .add(new Object[]{dep, srcId});
            }
        }

        // Sort adjacency lists by confidence DESC so highest-confidence neighbors are
        // enqueued/added first; when MAX_NODES is reached, lower-confidence nodes are the ones excluded.
        calleeAdj.values().forEach(list -> list.sort((a, b) -> {
            double ca = normConf(((CodeDependencyEntity) a[0]).getConfidence());
            double cb = normConf(((CodeDependencyEntity) b[0]).getConfidence());
            return Double.compare(cb, ca);
        }));
        callerAdj.values().forEach(list -> list.sort((a, b) -> {
            double ca = normConf(((CodeDependencyEntity) a[0]).getConfidence());
            double cb = normConf(((CodeDependencyEntity) b[0]).getConfidence());
            return Double.compare(cb, ca);
        }));

        Map<String, GraphNodeVO> nodes = new LinkedHashMap<>();
        Map<String, GraphEdgeVO> edges = new LinkedHashMap<>();
        boolean[] truncated = {false};

        String rootId = String.valueOf(root.getId());
        nodes.put(rootId, toNode(root));

        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{root.getId(), 0});
        Set<Long> visited = new HashSet<>();
        visited.add(root.getId());

        while (!queue.isEmpty()) {
            Object[] cur = queue.poll();
            Long curId = (Long) cur[0];
            int d = (int) cur[1];
            if (d >= boundedDepth) continue;

            if (callees) {
                for (Object[] entry : calleeAdj.getOrDefault(curId, List.of())) {
                    CodeDependencyEntity dep = (CodeDependencyEntity) entry[0];
                    @SuppressWarnings("unchecked")
                    List<Long> targets = (List<Long>) entry[1];
                    if (normConf(dep.getConfidence()) < minConf) continue;
                    if (targets.isEmpty()) {
                        addExternalEdge(nodes, edges, curId, dep, truncated);
                    } else {
                        for (Long t : targets) {
                            if (linkResolved(nodes, edges, symbolById, curId, t, dep, truncated)) {
                                if (visited.add(t) && !truncated[0]) {
                                    queue.add(new Object[]{t, d + 1});
                                }
                            }
                        }
                    }
                }
            } else {
                for (Object[] entry : callerAdj.getOrDefault(curId, List.of())) {
                    CodeDependencyEntity dep = (CodeDependencyEntity) entry[0];
                    Long src = (Long) entry[1];
                    if (normConf(dep.getConfidence()) < minConf) continue;
                    if (src != null && linkResolved(nodes, edges, symbolById, src, curId, dep, truncated)) {
                        if (visited.add(src) && !truncated[0]) {
                            queue.add(new Object[]{src, d + 1});
                        }
                    }
                }
            }
        }

        resolveFilePaths(nodes, symbolById);
        injectDebtScores(nodes, repoId);

        List<GraphNodeVO> nodeList = new ArrayList<>(nodes.values());
        List<GraphEdgeVO> edgeList = new ArrayList<>(edges.values());
        return CodeGraphVO.builder()
                .rootId(rootId)
                .nodes(nodeList)
                .edges(edgeList)
                .nodeCount(nodeList.size())
                .edgeCount(edgeList.size())
                .truncated(truncated[0])
                .build();
    }

    /** 建立 source->target(已解析符号) 的节点与边；返回 true 表示 target 已入图可继续扩展。 */
    private boolean linkResolved(Map<String, GraphNodeVO> nodes, Map<String, GraphEdgeVO> edges,
                                 Map<Long, CodeSymbolEntity> symbolById, Long sourceId, Long targetId,
                                 CodeDependencyEntity dep, boolean[] truncated) {
        CodeSymbolEntity srcSym = symbolById.get(sourceId);
        CodeSymbolEntity tgtSym = symbolById.get(targetId);
        if (srcSym == null || tgtSym == null) return false;
        if (!ensureNode(nodes, toNode(srcSym), truncated)) return false;
        if (!ensureNode(nodes, toNode(tgtSym), truncated)) return false;
        putEdge(edges, String.valueOf(sourceId), String.valueOf(targetId), dep,
                parseReturnType(tgtSym.getSignature()));
        return true;
    }

    private void addExternalEdge(Map<String, GraphNodeVO> nodes, Map<String, GraphEdgeVO> edges,
                                 Long sourceId, CodeDependencyEntity dep, boolean[] truncated) {
        String extId = "ext:" + dep.getTargetSymbolName();
        GraphNodeVO ext = GraphNodeVO.builder()
                .id(extId).label(shortLabel(dep.getTargetSymbolName()))
                .symbolType("EXTERNAL").layer("External").resolved(false).build();
        if (!ensureNode(nodes, ext, truncated)) return;
        putEdge(edges, String.valueOf(sourceId), extId, dep, null);
    }

    private boolean ensureNode(Map<String, GraphNodeVO> nodes, GraphNodeVO node, boolean[] truncated) {
        if (nodes.containsKey(node.getId())) return true;
        if (nodes.size() >= MAX_NODES) { truncated[0] = true; return false; }
        nodes.put(node.getId(), node);
        return true;
    }

    private void putEdge(Map<String, GraphEdgeVO> edges, String source, String target,
                         CodeDependencyEntity dep, String dataType) {
        String rel = dep.getRelationType() == null ? "CALL" : dep.getRelationType();
        String id = source + "->" + target + ":" + rel;
        edges.putIfAbsent(id, GraphEdgeVO.builder()
                .id(id).source(source).target(target).relationType(rel)
                .confidence(normConf(dep.getConfidence())).dataType(dataType).build());
    }

    private GraphNodeVO toNode(CodeSymbolEntity s) {
        String cls = s.getClassName();
        String simpleClass = cls == null ? "" : cls.substring(cls.lastIndexOf('.') + 1);
        String label = (simpleClass.isEmpty() ? "" : simpleClass + ".") + (s.getMethodName() == null ? "" : s.getMethodName());
        return GraphNodeVO.builder()
                .id(String.valueOf(s.getId()))
                .label(label.isEmpty() ? simpleClass : label)
                .className(cls).methodName(s.getMethodName())
                .symbolType(s.getSymbolType() == null ? "METHOD" : s.getSymbolType().name())
                .signature(s.getSignature()).summary(s.getSummary())
                .layer(inferLayer(s.getSymbolType(), cls))
                .startLine(s.getStartLine()).endLine(s.getEndLine())
                .resolved(true).build();
    }

    /** 依据 symbolType 与类名后缀推断分层（Controller/Service/Mapper/Entity/External），失败回退 symbolType 名。 */
    private static String inferLayer(SymbolType symbolType, String className) {
        String simple = className == null ? "" : className.substring(className.lastIndexOf('.') + 1);
        if (symbolType == SymbolType.API) return "Controller";
        if (simple.endsWith("Controller")) return "Controller";
        if (simple.endsWith("ServiceImpl") || simple.endsWith("Service")) return "Service";
        if (simple.endsWith("Mapper") || simple.endsWith("Repository") || simple.endsWith("Dao")) return "Mapper";
        if (simple.endsWith("Entity")) return "Entity";
        if (symbolType != null) {
            switch (symbolType) {
                case CONTROLLER: return "Controller";
                case SERVICE: return "Service";
                case MAPPER: return "Mapper";
                case ENTITY: return "Entity";
                default: return symbolType.name();
            }
        }
        return "METHOD";
    }

    private static final Set<String> METHOD_MODIFIERS = Set.of(
            "public", "private", "protected", "static", "final", "abstract",
            "synchronized", "native", "default", "transient", "volatile", "strictfp");

    /**
     * 从方法签名解析返回类型，例如 "User getUserById(Long)" -> "User"；
     * "Map&lt;String, User&gt; get(...)" -> "Map&lt;String, User&gt;"（泛型作为整体，不在内部逗号/尖括号处切断）；
     * 构造器（无返回类型，如 "public UserService(X)"）返回 null；无法解析或为空返回 null。
     *
     * 思路：方法名是 '(' 前紧邻的标识符；其之前的部分去掉修饰符后即返回类型，为空则视为构造器。
     */
    static String parseReturnType(String signature) {
        if (signature == null || signature.isBlank()) return null;
        int paren = signature.indexOf('(');
        String head = (paren >= 0 ? signature.substring(0, paren) : signature).trim();
        if (head.isEmpty()) return null;

        // 方法名 = head 尾部连续的标识符字符；其前面的内容是“修饰符 + 返回类型”。
        int nameStart = head.length();
        while (nameStart > 0 && Character.isJavaIdentifierPart(head.charAt(nameStart - 1))) {
            nameStart--;
        }
        String beforeName = head.substring(0, nameStart).trim();
        if (beforeName.isEmpty()) return null; // 构造器：方法名前无返回类型

        // 逐个剥离前缀修饰符（修饰符不含泛型尖括号，按顶层空白切分即可）。
        // 若全部内容都是修饰符（如构造器只剩 "public"），剥完为空 => 返回 null。
        while (true) {
            int ws = firstTopLevelWhitespace(beforeName);
            String firstToken = ws < 0 ? beforeName : beforeName.substring(0, ws);
            if (METHOD_MODIFIERS.contains(firstToken)) {
                beforeName = ws < 0 ? "" : beforeName.substring(ws).trim();
                if (beforeName.isEmpty()) {
                    break;
                }
            } else {
                break;
            }
        }
        return beforeName.isEmpty() ? null : beforeName;
    }

    /** 返回第一个不在泛型尖括号 &lt;&gt; 内的空白字符下标，没有则返回 -1（避免在泛型参数间的空白处误切）。 */
    private static int firstTopLevelWhitespace(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth = Math.max(0, depth - 1);
            else if (depth == 0 && Character.isWhitespace(c)) return i;
        }
        return -1;
    }

    private void resolveFilePaths(Map<String, GraphNodeVO> nodes, Map<Long, CodeSymbolEntity> symbolById) {
        List<Long> fileIds = nodes.values().stream()
                .filter(GraphNodeVO::isResolved)
                .map(n -> {
                    try {
                        return symbolById.get(Long.valueOf(n.getId()));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull).map(CodeSymbolEntity::getFileId)
                .filter(Objects::nonNull).distinct().toList();
        if (fileIds.isEmpty()) return;
        Map<Long, String> pathById = new HashMap<>();
        for (CodeFileEntity f : codeFileMapper.selectBatchIds(fileIds)) {
            if (f != null && f.getId() != null) pathById.put(f.getId(), f.getFilePath());
        }
        for (GraphNodeVO n : nodes.values()) {
            if (!n.isResolved()) continue;
            Long nodeId;
            try {
                nodeId = Long.valueOf(n.getId());
            } catch (NumberFormatException e) {
                continue;
            }
            CodeSymbolEntity s = symbolById.get(nodeId);
            if (s != null && s.getFileId() != null) n.setFilePath(pathById.get(s.getFileId()));
        }
    }

    /**
     * Feature A 调用图热力染色：按 file_path 批量查 comprehension_debt_file，
     * 将 debtScore / debtColor 注入每个已解析节点。
     * 失败安全：异常时静默跳过（不影响图的正常加载）。
     */
    private void injectDebtScores(Map<String, GraphNodeVO> nodes, Long repoId) {
        try {
            // 收集所有已解析节点的 filePath（去重）
            List<String> filePaths = nodes.values().stream()
                    .filter(GraphNodeVO::isResolved)
                    .map(GraphNodeVO::getFilePath)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (filePaths.isEmpty()) return;

            // 批量查询债务物化表
            List<ComprehensionDebtFileEntity> debtRows = debtFileMapper.selectList(
                    Wrappers.<ComprehensionDebtFileEntity>lambdaQuery()
                            .eq(ComprehensionDebtFileEntity::getRepoId, repoId)
                            .in(ComprehensionDebtFileEntity::getFilePath, filePaths)
                            .eq(ComprehensionDebtFileEntity::getStale, 0)); // 只用非 stale 数据

            // 建 filePath → debtScore 快速查找
            Map<String, Integer> scoreByPath = new HashMap<>();
            for (ComprehensionDebtFileEntity row : debtRows) {
                if (row.getFilePath() != null && row.getScore() != null) {
                    scoreByPath.put(row.getFilePath(), row.getScore());
                }
            }

            // 注入每个节点
            for (GraphNodeVO node : nodes.values()) {
                if (!node.isResolved() || node.getFilePath() == null) continue;
                Integer score = scoreByPath.get(node.getFilePath());
                if (score != null) {
                    node.setDebtScore(score);
                    node.setDebtColor(debtColorHex(score));
                }
            }
        } catch (Exception ex) {
            // 失败安全：债务染色失败不影响图的主体展示
        }
    }

    /** 债务分 → 热力颜色（与前端 graphLayout.ts debtColor 函数保持一致）。 */
    private static String debtColorHex(int score) {
        if (score >= 70) return "#e74c3c"; // RED
        if (score >= 40) return "#f39c12"; // YELLOW
        return "#27ae60";                  // GREEN
    }

    private static double normConf(BigDecimal c) {
        return c == null ? 0.0 : c.doubleValue();
    }

    private static String shortLabel(String target) {
        if (target == null) return "external";
        String t = target.contains("#") ? target.substring(target.indexOf('#') + 1) : target;
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }
}
