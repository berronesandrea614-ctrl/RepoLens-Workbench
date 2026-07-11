package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.config.InsightProperties;
import com.repolens.domain.entity.AgentRunEntity;
import com.repolens.domain.entity.AgentRunPlanEntity;
import com.repolens.domain.entity.AgentRunStepEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RequirementEntity;
import com.repolens.domain.entity.RequirementSymbolEntity;
import com.repolens.domain.vo.FlowEdgeVO;
import com.repolens.domain.vo.FlowNodeVO;
import com.repolens.domain.vo.RequirementInsightVO;
import com.repolens.mapper.AgentRunMapper;
import com.repolens.mapper.AgentRunPlanMapper;
import com.repolens.mapper.AgentRunStepMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RequirementMapper;
import com.repolens.mapper.RequirementSymbolMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.RequirementInsightService;
import com.repolens.service.impl.support.FileContentSummarizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 需求意图可视化聚合服务实现。
 *
 * <h2>toolReads 归属启发式</h2>
 * 读工具步（type=TOOL）的 target_files（逗号分隔）按 declaredFiles 后缀/包含匹配归到对应计划步；
 * 匹配不到的读步 target 放入第一步（兜底）。
 *
 * <h2>flow 节点组装</h2>
 * 每步 changes 的文件 → 查 code_symbol（文件下第一个主符号）→ FlowNodeVO；
 * 相邻节点间插入 FlowEdgeVO，data 字段 MVP 使用「调用」占位（无法从 why/insight 语义提取）。
 * 每步 flow 节点上限 8（超出后追加 data="…仅显示主要路径" 的 FlowEdgeVO 占位符）。
 *
 * <h2>敏感区规则</h2>
 * sensitivePatterns 为 glob 风格（*Xxx*、Xxx*、*Xxx）、大小写不敏感，
 * 匹配文件路径的最后一段（文件名去扩展名）或类名简称。
 * 空列表 = 关闭规则。
 *
 * <h2>off+sensitive 优先级</h2>
 * 当一个步骤同时是「计划外」和「敏感区命中」时，kind 保持 "off"（计划外是更强的审查信号），
 * 但 riskNote 仍会填写敏感区说明。
 *
 * <h2>panorama</h2>
 * 仅 hasChanges=true 时生成。按 inferLayer 分组所有触碰文件的主符号，
 * 每层节点上限 8，层数上限 4；MVP 跳过 dim 邻居节点（取不到子图时可省，per 规格 §2 说明）。
 *
 * <h2>symbol 缓存</h2>
 * filePath → CodeSymbolEntity 的查询结果在单次 insight() 请求内通过 symbolCache 共享，
 * 避免 steps + panorama 对同一文件重复 DB 往返。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementInsightServiceImpl implements RequirementInsightService {

    /** 工具循环中被视为「读工具」的工具名前缀集合（大小写精确匹配落库值）。 */
    private static final Set<String> READ_TOOL_NAMES = Set.of(
            "readFile", "getFileContent", "grep", "searchCode",
            "readSymbol", "searchSymbol", "listFiles", "getFileTree"
    );

    /** panorama 每层节点上限。 */
    private static final int PANORAMA_MAX_PER_LAYER = 8;

    /** panorama 层数上限（Controller/Service/Mapper/Entity + 其他 = 最多 5，但显示上限 4）。 */
    private static final int PANORAMA_MAX_LAYERS = 4;

    /** flow 每步节点上限（超出后追加省略占位边）。 */
    private static final int FLOW_MAX_NODES_PER_STEP = 8;

    /** steps 列表上限（超出后在 footer 注明已省略）。 */
    private static final int STEPS_MAX = 12;

    private final RequirementMapper requirementMapper;
    private final AgentRunMapper agentRunMapper;
    private final AgentRunPlanMapper agentRunPlanMapper;
    private final AgentRunStepMapper agentRunStepMapper;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final CodeFileMapper codeFileMapper;
    private final CodeSymbolMapper codeSymbolMapper;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;
    private final InsightProperties insightProperties;
    private final RequirementSymbolMapper requirementSymbolMapper;

    @Override
    public RequirementInsightVO insight(Long userId, Long repoId, Long requirementId) {
        // ── 1. 权限 + 归属校验 ────────────────────────────────────────────────────
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
        RequirementEntity req = requirementMapper.selectById(requirementId);
        if (req == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Requirement not found: " + requirementId);
        }
        if (!userId.equals(req.getUserId()) || !repoId.equals(req.getRepoId())) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "Requirement does not belong to repo " + repoId);
        }

        // ── 2. 加载 agent_run + plan ──────────────────────────────────────────────
        Long runId = req.getAgentRunId();
        AgentRunEntity agentRun = null;
        AgentRunPlanEntity planEntity = null;
        if (runId != null) {
            try {
                agentRun = agentRunMapper.selectById(runId);
                planEntity = agentRunPlanMapper.selectOne(
                        Wrappers.<AgentRunPlanEntity>lambdaQuery()
                                .eq(AgentRunPlanEntity::getAgentRunId, runId));
            } catch (Exception ex) {
                log.warn("load agent_run/plan failed, runId={}, err={}", runId, ex.getMessage());
            }
        }

        // ── 3. 解析计划步骤 ───────────────────────────────────────────────────────
        List<PlanStepData> planSteps = parsePlanSteps(planEntity);
        boolean planned = planEntity != null && !planSteps.isEmpty();

        // ── 4. 取 file_change_log（APPLIED / PROPOSED）────────────────────────────
        Long sessionId = req.getSessionId();
        if (sessionId == null && agentRun != null) {
            sessionId = agentRun.getSessionId();
        }
        List<FileChangeLogEntity> changes = loadChanges(sessionId);
        boolean hasChanges = !changes.isEmpty();

        // ── 4b. B2 fix: external requirements store changed files in requirement_symbol, not
        //        file_change_log.  If hasChanges is still false for a source="external" requirement,
        //        check requirement_symbol for file paths.  "Pure QA" = neither source has files.
        List<String> externalFilePaths = List.of();
        if (!hasChanges && "external".equals(req.getSource())) {
            externalFilePaths = loadExternalFilePaths(requirementId);
            if (!externalFilePaths.isEmpty()) {
                hasChanges = true;
            }
        }

        // ── 5. 取 agent_run_step（读工具步）────────────────────────────────────────
        List<AgentRunStepEntity> toolSteps = loadToolSteps(runId);

        // ── 6. 组装 approach（优先 plan.approach，其次 req.approach）──────────────
        String approach = null;
        if (planEntity != null && StringUtils.hasText(planEntity.getApproach())) {
            approach = planEntity.getApproach();
        } else if (StringUtils.hasText(req.getApproach())) {
            approach = req.getApproach();
        }

        // ── 7. 按请求粒度共享 symbol 缓存（F2：避免重复 DB 往返）──────────────────
        Map<String, Optional<CodeSymbolEntity>> symbolCache = new HashMap<>();

        // ── 8. 分形态组装 VO ──────────────────────────────────────────────────────
        if (!hasChanges) {
            // Truly pure QA: no file_change_log AND no requirement_symbol files.
            return buildPureAskVO(req, approach, toolSteps);
        }
        if (!planned) {
            // B2: External requirements with requirement_symbol files use the external path.
            if (!externalFilePaths.isEmpty()) {
                return buildExternalVO(req, approach, externalFilePaths, repoId, symbolCache);
            }
            return buildNoplanVO(req, approach, changes, repoId, symbolCache);
        }
        return buildFullVO(req, approach, planSteps, changes, toolSteps, repoId, symbolCache);
    }

    // ── 形态 A：纯问答（无改动）────────────────────────────────────────────────────

    private RequirementInsightVO buildPureAskVO(RequirementEntity req, String approach,
                                                 List<AgentRunStepEntity> toolSteps) {
        // 收集所有读工具步的 target_files 作为「AI 的回答依据」
        List<String> allReads = collectAllToolReads(toolSteps);

        RequirementInsightVO.InsightStep step = RequirementInsightVO.InsightStep.builder()
                .index(0)
                .title("AI 的回答依据")
                .kind("in")
                .toolReads(allReads)
                .flow(List.of())
                .build();

        return RequirementInsightVO.builder()
                .intent(req.getTitle())
                .approach(approach)
                .planned(false)
                .hasChanges(false)
                .chips(RequirementInsightVO.Chips.builder()
                        .filesChanged(0).added(0).modified(0)
                        .plannedStepsDone(0).plannedStepsTotal(0).offPlanCount(0)
                        .build())
                .deviation(null)
                .steps(List.of(step))
                .footer(RequirementInsightVO.InsightFooter.builder()
                        .plannedDone(null).offPlanPending(0)
                        .impactNote("纯问答需求，无代码改动")
                        .build())
                .panorama(null)
                .build();
    }

    // ── 形态 B：有改动但无结构化计划 ───────────────────────────────────────────────

    private RequirementInsightVO buildNoplanVO(RequirementEntity req, String approach,
                                               List<FileChangeLogEntity> changes, Long repoId,
                                               Map<String, Optional<CodeSymbolEntity>> symbolCache) {
        // F1: 先按路径去重，再统计 added/modified（与 buildFlowNodesForChanges 的去重逻辑保持一致）
        Map<String, FileChangeLogEntity> dedupedByPath = new LinkedHashMap<>();
        for (FileChangeLogEntity c : changes) {
            if (c.getFilePath() != null) dedupedByPath.putIfAbsent(c.getFilePath(), c);
        }
        List<FileChangeLogEntity> dedupedChanges = new ArrayList<>(dedupedByPath.values());
        int added = countByOp(dedupedChanges, FileChangeLogEntity.OP_TYPE_CREATE);
        int modified = dedupedChanges.size() - added;

        List<Object> flowNodes = buildFlowNodesForChanges(changes, repoId, Set.of(), false, symbolCache);

        RequirementInsightVO.InsightStep step = RequirementInsightVO.InsightStep.builder()
                .index(0)
                .title("改动概览")
                .kind("in")
                .toolReads(List.of())
                .flow(flowNodes)
                .build();

        // 全景（hasChanges=true，无计划但仍可展示）
        RequirementInsightVO.Panorama panorama = buildPanorama(changes, repoId, symbolCache);

        List<String> changedPaths = new ArrayList<>(dedupedByPath.keySet());

        return RequirementInsightVO.builder()
                .intent(req.getTitle())
                .approach(approach)
                .planned(false)
                .hasChanges(true)
                .chips(RequirementInsightVO.Chips.builder()
                        .filesChanged(changedPaths.size())
                        .added(added).modified(modified)
                        .plannedStepsDone(0).plannedStepsTotal(0).offPlanCount(0)
                        .build())
                .deviation(null)
                .steps(List.of(step))
                .footer(RequirementInsightVO.InsightFooter.builder()
                        .plannedDone(null).offPlanPending(0)
                        .impactNote("共改动 " + changedPaths.size() + " 个文件，无结构化计划")
                        .build())
                .panorama(panorama)
                .build();
    }

    // ── 形态 B2：外部改动（source=external，改动文件来自 requirement_symbol）──────────

    /**
     * B2 fix: external requirement insight path.
     * Changed files come from {@code requirement_symbol} (filePath entries, symbolId=null),
     * not from {@code file_change_log}. Builds a single "Claude 改动的文件" step.
     *
     * <p>Degradation: no plan, no panorama (no file_change_log), no deviation.
     * hasChanges=true as long as requirement_symbol has file entries.
     */
    private RequirementInsightVO buildExternalVO(RequirementEntity req, String approach,
                                                  List<String> externalFilePaths, Long repoId,
                                                  Map<String, Optional<CodeSymbolEntity>> symbolCache) {
        int fileCount = externalFilePaths.size();
        List<Object> flowNodes = buildExternalFlowNodes(externalFilePaths, repoId, symbolCache);

        RequirementInsightVO.InsightStep step = RequirementInsightVO.InsightStep.builder()
                .index(0)
                .title("Claude 改动的文件")
                .kind("in")
                .toolReads(List.of())
                .flow(flowNodes)
                .build();

        return RequirementInsightVO.builder()
                .intent(req.getTitle())
                .approach(approach)
                .planned(false)
                .hasChanges(true)
                .chips(RequirementInsightVO.Chips.builder()
                        .filesChanged(fileCount)
                        .added(0).modified(fileCount)
                        .plannedStepsDone(0).plannedStepsTotal(0).offPlanCount(0)
                        .build())
                .deviation(null)
                .steps(List.of(step))
                .footer(RequirementInsightVO.InsightFooter.builder()
                        .plannedDone(null).offPlanPending(0)
                        .impactNote("Claude Code 外部改动 " + fileCount + " 个文件")
                        .build())
                .panorama(null)
                .build();
    }

    /**
     * Build flow nodes for external file paths (no FileChangeLogEntity available).
     * Each file path becomes a node with {@code cls="mod"} and {@code tag="外部改动"}.
     */
    private List<Object> buildExternalFlowNodes(List<String> filePaths, Long repoId,
                                                  Map<String, Optional<CodeSymbolEntity>> symbolCache) {
        if (filePaths.isEmpty()) return List.of();
        List<Object> result = new ArrayList<>();
        boolean first = true;
        int nodeCount = 0;
        for (String filePath : filePaths) {
            if (!StringUtils.hasText(filePath)) continue;
            if (nodeCount >= FLOW_MAX_NODES_PER_STEP) {
                result.add(FlowEdgeVO.builder()
                        .nodeType("edge")
                        .data("…仅显示主要路径")
                        .mut(false)
                        .build());
                break;
            }
            if (!first) {
                result.add(FlowEdgeVO.builder()
                        .nodeType("edge")
                        .data("改动")
                        .mut(false)
                        .build());
            }
            result.add(buildExternalFlowNode(filePath, repoId, symbolCache));
            first = false;
            nodeCount++;
        }
        return result;
    }

    /** Build a single FlowNodeVO for an externally-changed file (no FileChangeLogEntity). */
    private FlowNodeVO buildExternalFlowNode(String filePath, Long repoId,
                                              Map<String, Optional<CodeSymbolEntity>> symbolCache) {
        CodeSymbolEntity symbol = findPrimarySymbol(repoId, filePath, symbolCache);

        String name;
        String sig = null;
        String note = null;
        String role;
        Long symbolId = null;
        Integer startLine = null;
        Integer endLine = null;

        if (symbol != null) {
            String className = symbol.getClassName();
            String simpleClass = className == null ? "" :
                    className.substring(className.lastIndexOf('.') + 1);
            String method = symbol.getMethodName();
            name = (method != null && !method.isEmpty())
                    ? simpleClass + "." + method : simpleClass;
            sig = symbol.getSignature();
            note = symbol.getSummary();
            role = inferLayer(symbol.getSymbolType() == null ? null : symbol.getSymbolType().name(),
                    className);
            symbolId = symbol.getId();
            startLine = symbol.getStartLine();
            endLine = symbol.getEndLine();
        } else {
            name = fileBaseName(filePath);
            role = inferLayerFromPath(filePath);
        }

        return FlowNodeVO.builder()
                .nodeType("node")
                .role(role)
                .name(name)
                .sig(sig)
                .note(note)
                .delta(null)         // No line-count info for external changes
                .cls("mod")          // Treat all external changes as modifications
                .tag("外部改动")
                .symbolId(symbolId)
                .filePath(filePath)
                .startLine(startLine)
                .endLine(endLine)
                .changeId(null)      // No file_change_log entry
                .external(false)     // These are code files, not Redis/DB external nodes
                .build();
    }

    // ── 形态 C：有计划 + 有改动（完整视图）──────────────────────────────────────────

    private RequirementInsightVO buildFullVO(RequirementEntity req, String approach,
                                              List<PlanStepData> planSteps,
                                              List<FileChangeLogEntity> changes,
                                              List<AgentRunStepEntity> toolSteps,
                                              Long repoId,
                                              Map<String, Optional<CodeSymbolEntity>> symbolCache) {
        // ── 计算 deviation ────────────────────────────────────────────────────────
        Set<String> plannedFilesAll = new HashSet<>();
        for (PlanStepData ps : planSteps) {
            if (ps.declaredFiles != null) {
                plannedFilesAll.addAll(ps.declaredFiles);
            }
        }
        // F7: actualFiles 保持插入顺序（changes 列表顺序），offPlanFiles 同理
        LinkedHashSet<String> actualFiles = changes.stream()
                .map(FileChangeLogEntity::getFilePath)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> offPlanFiles = new LinkedHashSet<>(actualFiles);
        for (String pf : plannedFilesAll) {
            // 计划声明的文件可能是相对路径/类名，用后缀匹配尽力过滤
            offPlanFiles.removeIf(actual -> pathMatches(actual, pf));
        }

        // ── toolReads 分配 ────────────────────────────────────────────────────────
        // 按计划步的 declaredFiles 后缀匹配，分配各读步的 target_files
        List<List<String>> stepToolReads = assignToolReads(planSteps, toolSteps);

        // ── 敏感区规则 ────────────────────────────────────────────────────────────
        List<String> sensitivePatterns = insightProperties.getSensitivePatterns();

        // ── 组装每个计划步 ────────────────────────────────────────────────────────
        List<RequirementInsightVO.InsightStep> steps = new ArrayList<>();
        int plannedDone = 0;

        for (int i = 0; i < planSteps.size(); i++) {
            PlanStepData ps = planSteps.get(i);
            List<String> stepDeclared = ps.declaredFiles == null ? List.of() : ps.declaredFiles;

            // 该步改动的文件
            List<FileChangeLogEntity> stepChanges = changes.stream()
                    .filter(c -> c.getFilePath() != null &&
                            stepDeclared.stream().anyMatch(d -> pathMatches(c.getFilePath(), d)))
                    .collect(Collectors.toList());

            boolean hasDone = !stepChanges.isEmpty();
            if (hasDone) plannedDone++;

            // 敏感区检测
            String riskHit = detectSensitive(stepDeclared, stepChanges, sensitivePatterns);
            String kind = riskHit != null ? "risk" : "in";
            String riskNote = riskHit != null ? "触及 " + riskHit + "，建议复审" : null;

            // AI 洞察：优先 plan step insight，否则从 why 派生
            String insight = StringUtils.hasText(ps.insight)
                    ? ps.insight
                    : (StringUtils.hasText(ps.why) ? "该步围绕 " + ps.title + " 展开：" + ps.why : null);

            // 构建 flow 节点（该步的改动文件）
            List<Object> flow = buildFlowNodesForChanges(stepChanges, repoId, offPlanFiles, false,
                    symbolCache);

            steps.add(RequirementInsightVO.InsightStep.builder()
                    .index(i)
                    .title(ps.title)
                    .why(ps.why)
                    .kind(kind)
                    .riskNote(riskNote)
                    .insight(insight)
                    .toolReads(stepToolReads.get(i))
                    .flow(flow)
                    .build());
        }

        // ── 追加计划外步骤（off-plan steps）──────────────────────────────────────
        if (!offPlanFiles.isEmpty()) {
            List<FileChangeLogEntity> offChanges = changes.stream()
                    .filter(c -> c.getFilePath() != null && offPlanFiles.contains(c.getFilePath()))
                    .collect(Collectors.toList());
            List<Object> offFlow = buildFlowNodesForChanges(offChanges, repoId, offPlanFiles, true,
                    symbolCache);

            // F5: off 优先于 risk（计划外是更强的审查信号）；riskNote 仍填写敏感区说明
            String riskHit = detectSensitive(new ArrayList<>(offPlanFiles), offChanges, sensitivePatterns);
            String offKind = "off";   // always "off" regardless of sensitive hit
            String offRiskNote = riskHit != null ? "触及 " + riskHit + "，建议复审" : null;

            steps.add(RequirementInsightVO.InsightStep.builder()
                    .index(steps.size())
                    .title("🚩 计划外改动")
                    .kind(offKind)
                    .riskNote(offRiskNote)
                    .insight("AI 在计划声明范围外额外改动了 " + offPlanFiles.size() + " 个文件，请确认是否合理")
                    .toolReads(List.of())
                    .flow(offFlow)
                    .build());
        }

        // F4: cap steps at STEPS_MAX (12)
        String truncNote = null;
        if (steps.size() > STEPS_MAX) {
            int dropped = steps.size() - STEPS_MAX;
            steps = new ArrayList<>(steps.subList(0, STEPS_MAX));
            truncNote = "（超出显示上限，已省略 " + dropped + " 个步骤）";
        }

        // ── chips ─────────────────────────────────────────────────────────────────
        // F1: dedup by path before counting added/modified
        Map<String, FileChangeLogEntity> dedupedByPath = new LinkedHashMap<>();
        for (FileChangeLogEntity c : changes) {
            if (c.getFilePath() != null) dedupedByPath.putIfAbsent(c.getFilePath(), c);
        }
        List<FileChangeLogEntity> dedupedChanges = new ArrayList<>(dedupedByPath.values());
        int added = countByOp(dedupedChanges, FileChangeLogEntity.OP_TYPE_CREATE);
        int modified = dedupedChanges.size() - added;
        int totalFiles = dedupedChanges.size();  // already distinct

        RequirementInsightVO.Chips chips = RequirementInsightVO.Chips.builder()
                .filesChanged(totalFiles)
                .added(added).modified(modified)
                .plannedStepsDone(plannedDone)
                .plannedStepsTotal(planSteps.size())
                .offPlanCount(offPlanFiles.size())
                .build();

        // ── deviation ─────────────────────────────────────────────────────────────
        RequirementInsightVO.Deviation deviation = null;
        if (!offPlanFiles.isEmpty()) {
            deviation = RequirementInsightVO.Deviation.builder()
                    .files(new ArrayList<>(offPlanFiles))
                    .note("AI 声明只改 " + plannedFilesAll.size() + " 处，实际多动了：" +
                            String.join(", ", offPlanFiles))
                    .build();
        }

        // ── footer ────────────────────────────────────────────────────────────────
        String impactNote = "共改动 " + totalFiles + " 个文件，影响层次见全景图";
        if (truncNote != null) {
            impactNote = impactNote + "；" + truncNote;
        }
        RequirementInsightVO.InsightFooter footer = RequirementInsightVO.InsightFooter.builder()
                .plannedDone(plannedDone + "/" + planSteps.size())
                .offPlanPending(offPlanFiles.size())
                .impactNote(impactNote)
                .build();

        // ── panorama ──────────────────────────────────────────────────────────────
        RequirementInsightVO.Panorama panorama = buildPanorama(changes, repoId, symbolCache);

        return RequirementInsightVO.builder()
                .intent(req.getTitle())
                .approach(approach)
                .planned(true)
                .hasChanges(true)
                .chips(chips)
                .deviation(deviation)
                .steps(steps)
                .footer(footer)
                .panorama(panorama)
                .build();
    }

    // ── Flow 节点构建 ───────────────────────────────────────────────────────────

    /**
     * 为 changes 列表构建 FlowNodeVO + FlowEdgeVO 混合列表。
     * 节点顺序 = change 在列表中的顺序；相邻节点之间插入 FlowEdgeVO（data="调用"，MVP 降级）。
     * F4: 节点数超过 FLOW_MAX_NODES_PER_STEP 时截断，并追加 data="…仅显示主要路径" 的占位边。
     *
     * @param offPlanFiles 计划外文件集合，用于决定 cls=offp
     * @param forceOffp    true = 所有节点强制 cls=offp（用于专门的计划外步骤）
     * @param symbolCache  per-request filePath→symbol 缓存，避免重复 DB 往返（F2）
     */
    private List<Object> buildFlowNodesForChanges(List<FileChangeLogEntity> changes, Long repoId,
                                                   Set<String> offPlanFiles, boolean forceOffp,
                                                   Map<String, Optional<CodeSymbolEntity>> symbolCache) {
        if (changes.isEmpty()) return List.of();

        // 按文件路径去重（同文件多条 change log 只取最新）
        Map<String, FileChangeLogEntity> deduped = new LinkedHashMap<>();
        for (FileChangeLogEntity c : changes) {
            if (c.getFilePath() != null) {
                deduped.putIfAbsent(c.getFilePath(), c);
            }
        }

        List<Object> result = new ArrayList<>();
        boolean first = true;
        int nodeCount = 0;
        for (FileChangeLogEntity change : deduped.values()) {
            // F4: cap at FLOW_MAX_NODES_PER_STEP nodes; append truncation edge
            if (nodeCount >= FLOW_MAX_NODES_PER_STEP) {
                result.add(FlowEdgeVO.builder()
                        .nodeType("edge")
                        .data("…仅显示主要路径")
                        .mut(false)
                        .build());
                break;
            }
            if (!first) {
                result.add(FlowEdgeVO.builder()
                        .nodeType("edge")
                        .data("调用")
                        .mut(false)
                        .build());
            }
            FlowNodeVO node = buildFlowNode(change, repoId, offPlanFiles, forceOffp, symbolCache);
            result.add(node);
            first = false;
            nodeCount++;
        }
        return result;
    }

    /** 从单条 FileChangeLogEntity 构建 FlowNodeVO，尝试从 code_symbol 取主符号信息。 */
    private FlowNodeVO buildFlowNode(FileChangeLogEntity change, Long repoId,
                                      Set<String> offPlanFiles, boolean forceOffp,
                                      Map<String, Optional<CodeSymbolEntity>> symbolCache) {
        String filePath = change.getFilePath();
        boolean isCreate = FileChangeLogEntity.OP_TYPE_CREATE.equals(change.getOpType());
        boolean isOffPlan = forceOffp || (offPlanFiles != null && offPlanFiles.contains(filePath));

        // 计算 delta
        String delta = computeDelta(change, isCreate);

        // cls 决策：offp > danger（由调用方处理） > new > mod
        String cls;
        if (isOffPlan) {
            cls = "offp";
        } else if (isCreate) {
            cls = "new";
        } else {
            cls = "mod";
        }

        // F8: off-plan CREATE 节点 tag 为"计划外"，而非"+新增"
        String tag;
        if (isOffPlan && isCreate) {
            tag = "计划外";
        } else if (isCreate) {
            tag = "+新增";
        } else if (isOffPlan) {
            tag = "计划外·改";
        } else {
            tag = "~改";
        }

        // 尝试查主符号（F2: 使用 symbolCache 避免重复 DB 往返）
        CodeSymbolEntity symbol = findPrimarySymbol(repoId, filePath, symbolCache);

        String name;
        String sig = null;
        String note = null;
        String role = null;
        Long symbolId = null;
        Integer startLine = null;
        Integer endLine = null;

        if (symbol != null) {
            String className = symbol.getClassName();
            String simpleClass = className == null ? "" :
                    className.substring(className.lastIndexOf('.') + 1);
            String method = symbol.getMethodName();
            name = (method != null && !method.isEmpty())
                    ? simpleClass + "." + method : simpleClass;
            sig = symbol.getSignature();
            note = symbol.getSummary();
            role = inferLayer(symbol.getSymbolType() == null ? null : symbol.getSymbolType().name(), className);
            symbolId = symbol.getId();
            startLine = symbol.getStartLine();
            endLine = symbol.getEndLine();
        } else {
            // 退化：用文件名作为节点名
            name = filePath != null ? fileBaseName(filePath) : "(unknown)";
            role = inferLayerFromPath(filePath);
        }

        // F-enrich: 用 new_content 解析内容摘要，补全 sig/note（失败安全，不影响其他字段）
        try {
            String newContent = change.getNewContent();
            if (newContent != null && !newContent.isEmpty()) {
                FileContentSummarizer.FileSummary summary =
                        FileContentSummarizer.summarize(filePath, newContent);
                if (symbol == null) {
                    // 无 code_symbol：name 优先用解析到的类名，sig/note 全由 summarizer 填充
                    if (summary.className() != null) name = summary.className();
                    if (summary.sig()  != null) sig  = summary.sig();
                    if (summary.note() != null) note = summary.note();
                } else {
                    // 有 code_symbol 但 sig/note 为空：用 summarizer 补空
                    if (!StringUtils.hasText(sig)  && summary.sig()  != null) sig  = summary.sig();
                    if (!StringUtils.hasText(note) && summary.note() != null) note = summary.note();
                }
            }
        } catch (Exception ex) {
            log.debug("FileContentSummarizer enrich skipped for path={}: {}", filePath, ex.getMessage());
        }

        return FlowNodeVO.builder()
                .nodeType("node")
                .role(role)
                .name(name)
                .sig(sig)
                .note(note)
                .delta(delta)
                .cls(cls)
                .tag(tag)
                .symbolId(symbolId)
                .filePath(filePath)
                .startLine(startLine)
                .endLine(endLine)
                .changeId(change.getId())
                .external(false)
                .build();
    }

    /** 计算 delta 标注（+N 或 ~N）。 */
    private String computeDelta(FileChangeLogEntity change, boolean isCreate) {
        try {
            if (isCreate) {
                int lines = lineCount(change.getNewContent());
                return "+" + lines;
            }
            int oldLines = lineCount(change.getOldContent());
            int newLines = lineCount(change.getNewContent());
            int diff = Math.abs(newLines - oldLines);
            return "~" + diff;
        } catch (Exception ex) {
            return null;
        }
    }

    private int lineCount(String content) {
        if (content == null || content.isEmpty()) return 0;
        return content.split("\n", -1).length;
    }

    // ── Panorama 构建 ─────────────────────────────────────────────────────────

    /**
     * 构建分层全景图：将 changes 的文件按 inferLayer 分组，每层最多 PANORAMA_MAX_PER_LAYER 节点，
     * 全景最多 PANORAMA_MAX_LAYERS 层。MVP 跳过 dim 邻居（per 规格 §2 注释）。
     *
     * @param symbolCache per-request symbol 缓存（F2）
     */
    private RequirementInsightVO.Panorama buildPanorama(List<FileChangeLogEntity> changes, Long repoId,
                                                         Map<String, Optional<CodeSymbolEntity>> symbolCache) {
        if (changes.isEmpty()) return null;
        try {
            // 按 filePath 去重，查主符号，按 layer 分组
            Map<String, FileChangeLogEntity> byPath = new LinkedHashMap<>();
            for (FileChangeLogEntity c : changes) {
                if (c.getFilePath() != null) byPath.putIfAbsent(c.getFilePath(), c);
            }

            // 层名 → 该层的 flow 节点列表（确保稳定顺序）
            Map<String, List<Object>> layerFlows = new LinkedHashMap<>();

            for (FileChangeLogEntity change : byPath.values()) {
                CodeSymbolEntity sym = findPrimarySymbol(repoId, change.getFilePath(), symbolCache);
                String layer;
                if (sym != null) {
                    layer = inferLayer(sym.getSymbolType() == null ? null : sym.getSymbolType().name(),
                            sym.getClassName());
                } else {
                    layer = inferLayerFromPath(change.getFilePath());
                }
                if (layer == null) layer = "Other";

                List<Object> layerFlow = layerFlows.computeIfAbsent(layer, k -> new ArrayList<>());
                if (layerFlow.size() < PANORAMA_MAX_PER_LAYER) {
                    // 无 offPlanFiles 上下文用于 panorama——全景不标计划外，只标 new/mod/danger
                    FlowNodeVO node = buildFlowNode(change, repoId, Collections.emptySet(), false,
                            symbolCache);
                    layerFlow.add(node);
                }
            }

            List<RequirementInsightVO.PanoramaLayer> layers = new ArrayList<>();
            int count = 0;
            for (Map.Entry<String, List<Object>> entry : layerFlows.entrySet()) {
                if (count >= PANORAMA_MAX_LAYERS) break;
                layers.add(RequirementInsightVO.PanoramaLayer.builder()
                        .label(entry.getKey())
                        .flow(entry.getValue())
                        .build());
                count++;
            }
            return RequirementInsightVO.Panorama.builder().layers(layers).build();
        } catch (Exception ex) {
            log.warn("panorama build failed, skip, err={}", ex.getMessage());
            return null;
        }
    }

    // ── toolReads 分配 ─────────────────────────────────────────────────────────

    /**
     * 将 TOOL 类型的 agent_run_step target_files 分配到对应计划步的 toolReads。
     * 分配规则（启发式）：
     * <ol>
     *   <li>target 路径 ∈ 某步的 declaredFiles（后缀/包含匹配）→ 归入该步。</li>
     *   <li>匹配不到的 → 归入第一步（兜底）。</li>
     * </ol>
     *
     * @return 与 planSteps 等长的 List，每个元素是该步的 toolReads 路径列表
     */
    private List<List<String>> assignToolReads(List<PlanStepData> planSteps,
                                                List<AgentRunStepEntity> toolSteps) {
        // 初始化每步的 toolReads 容器
        List<Set<String>> sets = new ArrayList<>();
        for (int i = 0; i < planSteps.size(); i++) {
            sets.add(new java.util.LinkedHashSet<>());
        }

        for (AgentRunStepEntity ts : toolSteps) {
            List<String> targets = splitFiles(ts.getTargetFiles());
            for (String target : targets) {
                boolean matched = false;
                for (int i = 0; i < planSteps.size(); i++) {
                    List<String> declared = planSteps.get(i).declaredFiles;
                    if (declared != null && declared.stream().anyMatch(d -> pathMatches(target, d))) {
                        sets.get(i).add(target);
                        matched = true;
                        break;
                    }
                }
                if (!matched && !sets.isEmpty()) {
                    sets.get(0).add(target); // 兜底放第一步
                }
            }
        }

        return sets.stream()
                .map(s -> new ArrayList<>(s))
                .collect(Collectors.toList());
    }

    /** 收集所有 TOOL 步（任意 target_files）——用于纯问答形态。 */
    private List<String> collectAllToolReads(List<AgentRunStepEntity> toolSteps) {
        Set<String> all = new java.util.LinkedHashSet<>();
        for (AgentRunStepEntity ts : toolSteps) {
            all.addAll(splitFiles(ts.getTargetFiles()));
        }
        return new ArrayList<>(all);
    }

    // ── 敏感区检测 ─────────────────────────────────────────────────────────────

    /**
     * 在 declaredFiles + stepChanges 的文件名/类名中检测敏感模式。
     * 返回第一个命中的简称（如 "SecurityConfig"），无命中返回 null。
     */
    private String detectSensitive(List<String> declaredFiles, List<FileChangeLogEntity> stepChanges,
                                    List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return null;

        Set<String> candidates = new HashSet<>();
        if (declaredFiles != null) {
            for (String f : declaredFiles) {
                candidates.add(fileBaseName(f));
            }
        }
        for (FileChangeLogEntity c : stepChanges) {
            if (c.getFilePath() != null) {
                candidates.add(fileBaseName(c.getFilePath()));
            }
        }

        for (String candidate : candidates) {
            for (String pattern : patterns) {
                if (globMatch(pattern, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * 简单 glob 匹配（大小写不敏感）。
     * 支持 *Xxx*（包含）、Xxx*（前缀）、*Xxx（后缀）、Xxx（精确）。
     */
    static boolean globMatch(String pattern, String name) {
        if (pattern == null || name == null) return false;
        String p = pattern.toLowerCase();
        String n = name.toLowerCase();
        if (p.startsWith("*") && p.endsWith("*") && p.length() > 2) {
            return n.contains(p.substring(1, p.length() - 1));
        }
        if (p.startsWith("*")) {
            return n.endsWith(p.substring(1));
        }
        if (p.endsWith("*")) {
            return n.startsWith(p.substring(0, p.length() - 1));
        }
        return n.equals(p);
    }

    // ── 辅助：DB 查询 ───────────────────────────────────────────────────────────

    private List<FileChangeLogEntity> loadChanges(Long sessionId) {
        if (sessionId == null) return List.of();
        try {
            return fileChangeLogMapper.selectList(
                    Wrappers.<FileChangeLogEntity>lambdaQuery()
                            .eq(FileChangeLogEntity::getSessionId, sessionId)
                            .in(FileChangeLogEntity::getStatus,
                                    FileChangeLogEntity.STATUS_APPLIED,
                                    FileChangeLogEntity.STATUS_PROPOSED));
        } catch (Exception ex) {
            log.warn("load changes failed, sessionId={}, err={}", sessionId, ex.getMessage());
            return List.of();
        }
    }

    private List<AgentRunStepEntity> loadToolSteps(Long runId) {
        if (runId == null) return List.of();
        try {
            return agentRunStepMapper.selectList(
                    Wrappers.<AgentRunStepEntity>lambdaQuery()
                            .eq(AgentRunStepEntity::getRunId, runId)
                            .eq(AgentRunStepEntity::getType, "TOOL"));
        } catch (Exception ex) {
            log.warn("load tool steps failed, runId={}, err={}", runId, ex.getMessage());
            return List.of();
        }
    }

    /**
     * B2 fix: load distinct file paths from {@code requirement_symbol} for a given requirement.
     * Used for {@code source="external"} requirements that store changed files in requirement_symbol
     * (symbolId=null) rather than in file_change_log.
     */
    private List<String> loadExternalFilePaths(Long requirementId) {
        if (requirementId == null) return List.of();
        try {
            List<RequirementSymbolEntity> syms = requirementSymbolMapper.selectList(
                    Wrappers.<RequirementSymbolEntity>lambdaQuery()
                            .eq(RequirementSymbolEntity::getRequirementId, requirementId));
            if (syms == null || syms.isEmpty()) return List.of();
            // Collect distinct, non-blank file paths preserving order.
            List<String> paths = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (RequirementSymbolEntity sym : syms) {
                String fp = sym.getFilePath();
                if (StringUtils.hasText(fp) && seen.add(fp)) {
                    paths.add(fp);
                }
            }
            return paths;
        } catch (Exception ex) {
            log.warn("loadExternalFilePaths failed, requirementId={}, err={}", requirementId, ex.getMessage());
            return List.of();
        }
    }

    /**
     * 查文件下的第一个主符号（CLASS 类型优先，否则取 id 最小的）。
     * F2: 使用 symbolCache 避免对同一 filePath 的重复 DB 往返。
     * cache 存储 Optional 以区分「已查且为空」与「未查」两种状态。
     */
    private CodeSymbolEntity findPrimarySymbol(Long repoId, String filePath,
                                                Map<String, Optional<CodeSymbolEntity>> symbolCache) {
        if (repoId == null || !StringUtils.hasText(filePath)) return null;
        // 检查缓存（Optional.empty() 表示已查但无结果）
        if (symbolCache != null && symbolCache.containsKey(filePath)) {
            return symbolCache.get(filePath).orElse(null);
        }
        CodeSymbolEntity result = null;
        try {
            List<CodeFileEntity> files = codeFileMapper.selectList(
                    Wrappers.<CodeFileEntity>lambdaQuery()
                            .eq(CodeFileEntity::getRepoId, repoId)
                            .eq(CodeFileEntity::getFilePath, filePath));
            if (files != null && !files.isEmpty()) {
                Long fileId = files.get(0).getId();
                List<CodeSymbolEntity> syms = codeSymbolMapper.selectList(
                        Wrappers.<CodeSymbolEntity>lambdaQuery()
                                .eq(CodeSymbolEntity::getFileId, fileId));
                if (syms != null && !syms.isEmpty()) {
                    // CLASS 优先
                    for (CodeSymbolEntity s : syms) {
                        if (s.getSymbolType() != null && "CLASS".equals(s.getSymbolType().name())) {
                            result = s;
                            break;
                        }
                    }
                    if (result == null) result = syms.get(0);
                }
            }
        } catch (Exception ex) {
            log.warn("findPrimarySymbol failed, filePath={}, err={}", filePath, ex.getMessage());
        }
        if (symbolCache != null) {
            symbolCache.put(filePath, Optional.ofNullable(result));
        }
        return result;
    }

    // ── 辅助：plan steps 解析 ──────────────────────────────────────────────────

    private List<PlanStepData> parsePlanSteps(AgentRunPlanEntity plan) {
        if (plan == null || !StringUtils.hasText(plan.getPlanJson())) return List.of();
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(
                    plan.getPlanJson(), new TypeReference<>() {});
            if (raw == null) return List.of();
            List<PlanStepData> result = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                PlanStepData ps = new PlanStepData();
                ps.title = (String) m.get("title");
                ps.why = (String) m.get("why");
                ps.insight = (String) m.get("insight");
                Object df = m.get("declaredFiles");
                if (df instanceof List) {
                    ps.declaredFiles = ((List<?>) df).stream()
                            .filter(Objects::nonNull)
                            .map(Object::toString)
                            .collect(Collectors.toList());
                } else {
                    ps.declaredFiles = List.of();
                }
                if (ps.title == null) ps.title = "步骤 " + (result.size() + 1);
                result.add(ps);
            }
            return result;
        } catch (Exception ex) {
            log.warn("parse plan steps failed, err={}", ex.getMessage());
            return List.of();
        }
    }

    // ── 辅助：路径/字符串工具 ──────────────────────────────────────────────────

    /**
     * 路径匹配：actual 是否"属于" declared（后缀包含或精确匹配，宽容化处理）。
     * declared 可能是：相对路径（src/A.java）、类名（SecurityConfig）。
     *
     * <p>F3: 移除无边界的 a.endsWith(d) 子句（会导致 "Config.java" 匹配 "AppConfig.java"）；
     * 保留 a.endsWith("/"+d) 和类名 base-name 分支，base-name 比较大小写不敏感。
     */
    static boolean pathMatches(String actual, String declared) {
        if (actual == null || declared == null) return false;
        // 统一为 / 分隔
        String a = actual.replace('\\', '/');
        String d = declared.replace('\\', '/');
        if (a.equals(d)) return true;
        // 只保留带路径分隔符边界的后缀匹配（F3: 去掉 a.endsWith(d) 的无边界版本）
        if (a.endsWith("/" + d)) return true;
        // declared 是类名（无路径分隔符），匹配文件名去扩展名（大小写不敏感）
        if (!d.contains("/")) {
            String base = fileBaseName(a);
            return base.equalsIgnoreCase(d) || base.equalsIgnoreCase(d.replace(".java", ""));
        }
        return false;
    }

    /** 取路径末段（文件名），去除 .java/.kt 扩展名。 */
    static String fileBaseName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 从逗号分隔的 targetFiles 字符串拆分为路径列表。 */
    private List<String> splitFiles(String targetFiles) {
        if (!StringUtils.hasText(targetFiles)) return List.of();
        List<String> result = new ArrayList<>();
        for (String p : targetFiles.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    /** 统计 CREATE 操作数（对已去重的 changes 列表调用）。 */
    private int countByOp(List<FileChangeLogEntity> changes, String opType) {
        int count = 0;
        for (FileChangeLogEntity c : changes) {
            if (opType.equals(c.getOpType())) count++;
        }
        return count;
    }

    /**
     * 依据 symbolTypeName 与类名后缀推断分层标签（与 CodeGraphServiceImpl 保持一致）。
     * SymbolType 以字符串形式传入，避免枚举跨模块硬依赖。
     */
    static String inferLayer(String symbolTypeName, String className) {
        String simple = className == null ? "" : className.substring(className.lastIndexOf('.') + 1);
        if ("API".equals(symbolTypeName) || simple.endsWith("Controller")) return "Controller";
        if (simple.endsWith("ServiceImpl") || simple.endsWith("Service")) return "Service";
        if (simple.endsWith("Mapper") || simple.endsWith("Repository") || simple.endsWith("Dao"))
            return "Mapper";
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
        return "Other";
    }

    /** 从文件路径推断层（无符号信息时降级）。 */
    static String inferLayerFromPath(String filePath) {
        if (filePath == null) return "Other";
        String base = fileBaseName(filePath);
        return inferLayer(null, base);
    }

    // ── 内部数据类 ─────────────────────────────────────────────────────────────

    /** agent_run_plan.plan_json 中的一个步骤（结构化解析结果）。 */
    static class PlanStepData {
        String title;
        String why;
        String insight;
        List<String> declaredFiles;
    }
}
