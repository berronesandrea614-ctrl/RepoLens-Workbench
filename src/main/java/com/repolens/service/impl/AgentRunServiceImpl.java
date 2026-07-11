package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AgentRunEntity;
import com.repolens.domain.entity.AgentRunPlanEntity;
import com.repolens.domain.entity.AgentRunStepEntity;
import com.repolens.domain.vo.AgentRunStepVO;
import com.repolens.domain.vo.AgentRunTraceVO;
import com.repolens.domain.vo.AgentRunVO;
import com.repolens.domain.vo.AgentStepVO;
import com.repolens.mapper.AgentRunMapper;
import com.repolens.mapper.AgentRunPlanMapper;
import com.repolens.mapper.AgentRunStepMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.AgentRunService;
import com.repolens.service.impl.support.AgentPlanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 执行记录读写实现。
 * 差异化点："把可视化主语从 repo 换成 agent run"——每次 agent 问答的多步 loop
 * 都被持久化为可查询、可回放的 trace，供前端渲染 timeline + 因果 DAG。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunServiceImpl implements AgentRunService {

    /** 写文件工具名：命中该工具的步归为 WRITE 类型。 */
    private static final String WRITE_TOOL = "writeFileContent";

    /** observation 落库上限，避免把工具大结果整段写进审计表。 */
    private static final int OBSERVATION_STORE_CHARS = 8000;

    /** observation 传输上限，前端 trace 只需摘要。 */
    private static final int OBSERVATION_TRANSPORT_CHARS = 1000;

    /** answer_preview 上限。 */
    private static final int ANSWER_PREVIEW_CHARS = 500;

    /** target_files 落库上限（列宽 1000），截断在整条路径边界。 */
    private static final int TARGET_FILES_CHARS = 1000;

    /** list() 单次最多返回条数，排序 + 分页推入 DB，避免全表加载。 */
    private static final int LIST_LIMIT = 50;

    private final AgentRunMapper agentRunMapper;
    private final AgentRunStepMapper agentRunStepMapper;
    private final AgentRunPlanMapper agentRunPlanMapper;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long record(Long repoId, Long sessionId, Long userId, String question, String mode,
                       String answer, Integer iterations, Integer toolCalls, List<AgentStepVO> steps) {
        return record(repoId, sessionId, userId, question, mode, answer, iterations, toolCalls, steps, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long record(Long repoId, Long sessionId, Long userId, String question, String mode,
                       String answer, Integer iterations, Integer toolCalls, List<AgentStepVO> steps,
                       AgentPlanner.StructuredPlan plan) {
        AgentRunEntity run = new AgentRunEntity();
        run.setRepoId(repoId);
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setQuestion(truncate(question, 2000));
        run.setMode(mode);
        run.setAnswerPreview(truncate(answer, ANSWER_PREVIEW_CHARS));
        run.setIterations(iterations);
        run.setToolCalls(toolCalls);
        run.setStatus("DONE");
        run.setCreatedAt(LocalDateTime.now());
        // 解析自报声称字段（失败安全：解析异常不影响主流程）
        try {
            com.repolens.service.impl.support.SelfReportParser.Result sr =
                    com.repolens.service.impl.support.SelfReportParser.parse(run.getAnswerPreview());
            run.setClaimedSuccess(sr.claimedSuccess() ? 1 : 0);
            run.setClaimedVerified(sr.claimedVerified() ? 1 : 0);
            run.setClaimEvidence(sr.claimEvidence());
        } catch (Exception ex) {
            log.warn("SelfReportParser failed, runId will be set after insert, err={}", ex.getMessage());
        }
        agentRunMapper.insert(run);
        Long runId = run.getId();

        if (steps != null) {
            for (AgentStepVO step : steps) {
                if (step == null) {
                    continue;
                }
                AgentRunStepEntity row = new AgentRunStepEntity();
                row.setRunId(runId);
                row.setStepIndex(step.getStepIndex());
                row.setType(deriveType(step.getToolName()));
                row.setToolName(step.getToolName());
                row.setToolArgs(step.getToolArgs());
                row.setThought(step.getThought());
                row.setObservation(truncate(step.getObservation(), OBSERVATION_STORE_CHARS));
                // 在整条路径边界截断，不产生半路径字符串。
                row.setTargetFiles(extractTargetFiles(step));
                row.setStatus("DONE");
                row.setCreatedAt(LocalDateTime.now());
                agentRunStepMapper.insert(row);
            }
        }

        // 结构化计划落库（失败安全：若此处抛出，事务回滚由 @Transactional 处理，
        // 上层 persistAgentRunSafe 统一 catch，不会影响回答）。
        if (plan != null && plan.hasStructure()) {
            persistPlan(runId, plan);
        }

        return runId;
    }

    /**
     * 将结构化计划写入 agent_run_plan 表。
     * plan_json 序列化 steps 数组；approach 截断至 500 字符。
     */
    private void persistPlan(Long runId, AgentPlanner.StructuredPlan plan) {
        AgentRunPlanEntity planEntity = new AgentRunPlanEntity();
        planEntity.setAgentRunId(runId);
        planEntity.setApproach(truncate(plan.approach(), 500));
        // 序列化 steps 数组为 JSON；失败时存 null（整体仍成功落库 approach）。
        String planJson = null;
        if (plan.steps() != null) {
            try {
                planJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plan.steps());
            } catch (Exception ex) {
                log.warn("serialize plan steps failed, store null planJson, runId={}, err={}", runId, ex.getMessage());
            }
        }
        planEntity.setPlanJson(planJson);
        planEntity.setCreatedAt(LocalDateTime.now());
        agentRunPlanMapper.insert(planEntity);
    }

    @Override
    public Long begin(Long userId, Long repoId, Long sessionId, String permissionMode) {
        AgentRunEntity run = new AgentRunEntity();
        run.setUserId(userId);
        run.setRepoId(repoId);
        run.setSessionId(sessionId);
        run.setStatus("RUNNING");
        run.setPermissionMode(permissionMode);
        run.setCreatedAt(LocalDateTime.now());
        agentRunMapper.insert(run);
        return run.getId();
    }

    @Override
    public void finish(Long runId, String finalAnswer, int toolTurns, long wallClockMs) {
        AgentRunEntity run = agentRunMapper.selectById(runId);
        if (run == null) {
            return;
        }
        run.setStatus("DONE");
        run.setAnswerPreview(truncate(finalAnswer, ANSWER_PREVIEW_CHARS));
        run.setToolTurns(toolTurns);
        run.setWallClockMs(wallClockMs);
        agentRunMapper.updateById(run);
    }

    @Override
    public List<AgentRunVO> list(Long userId, Long repoId, Long sessionId) {
        checkPermission(userId, repoId);
        // 排序 + LIMIT 推入 DB，避免全量拉取后内存排序。
        List<AgentRunEntity> runs = agentRunMapper.selectList(
                Wrappers.<AgentRunEntity>lambdaQuery()
                        .eq(AgentRunEntity::getRepoId, repoId)
                        .eq(sessionId != null, AgentRunEntity::getSessionId, sessionId)
                        .orderByDesc(AgentRunEntity::getId)
                        .last("LIMIT " + LIST_LIMIT));
        if (runs.isEmpty()) {
            return Collections.emptyList();
        }
        // 一次 IN+GROUP BY 批量取步数，消除 N+1。
        List<Long> runIds = runs.stream().map(AgentRunEntity::getId).collect(Collectors.toList());
        Map<Long, Long> stepCountMap = batchStepCounts(runIds);
        return runs.stream()
                .map(r -> toRunVO(r, stepCountMap.getOrDefault(r.getId(), 0L).intValue()))
                .collect(Collectors.toList());
    }

    @Override
    public AgentRunTraceVO trace(Long userId, Long repoId, Long runId) {
        checkPermission(userId, repoId);
        AgentRunEntity run = agentRunMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Agent run not found: " + runId);
        }
        if (!repoId.equals(run.getRepoId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "Agent run does not belong to repo " + repoId);
        }
        List<AgentRunStepEntity> steps = agentRunStepMapper.selectList(
                Wrappers.<AgentRunStepEntity>lambdaQuery()
                        .eq(AgentRunStepEntity::getRunId, runId)
                        .orderByAsc(AgentRunStepEntity::getStepIndex)
                        .orderByAsc(AgentRunStepEntity::getId));
        List<AgentRunStepVO> stepVOs = steps.stream().map(this::toStepVO).collect(Collectors.toList());
        return AgentRunTraceVO.builder()
                .run(toRunVO(run, stepVOs.size()))
                .steps(stepVOs)
                .build();
    }

    /** 步类型推断：写工具→WRITE，其他工具→TOOL，无工具→THINK。 */
    private String deriveType(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return "THINK";
        }
        return WRITE_TOOL.equals(toolName) ? "WRITE" : "TOOL";
    }

    /**
     * 尽力从 toolArgs（JSON，含 "filePath"）解析本步触达文件；解析不到返回 null。
     * 覆盖 getFileContent / writeFileContent 等以 filePath 为参的文件类工具。
     * 结果在整条路径边界处截断（不产生截断到路径中间的字符串）。
     */
    private String extractTargetFiles(AgentStepVO step) {
        Set<String> files = new LinkedHashSet<>();
        collectFilePaths(step.getToolArgs(), files);
        return joinFilesWithinLimit(files, TARGET_FILES_CHARS);
    }

    /**
     * 逐路径拼接，在整条路径边界处停截，保证存入 DB 的字符串不会截断路径中间。
     * 超过 maxChars 的首条路径直接丢弃（极端情况），返回 null。
     */
    private String joinFilesWithinLimit(Set<String> files, int maxChars) {
        if (files.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String file : files) {
            if (sb.length() == 0) {
                if (file.length() > maxChars) {
                    // 单条路径已超列宽上限，丢弃并终止
                    break;
                }
                sb.append(file);
            } else {
                String segment = "," + file;
                if (sb.length() + segment.length() > maxChars) {
                    // 加入该路径会超限：在上一条路径后截止
                    break;
                }
                sb.append(segment);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void collectFilePaths(String json, Set<String> out) {
        if (!StringUtils.hasText(json)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            walkFilePaths(node, out);
        } catch (Exception ex) {
            // 非 JSON 或解析失败：target_files 留空，绝不影响落库。
            log.debug("parse target files failed, skip, err={}", ex.getMessage());
        }
    }

    private void walkFilePaths(JsonNode node, Set<String> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                walkFilePaths(item, out);
            }
            return;
        }
        if (node.isObject()) {
            if (node.hasNonNull("filePath")) {
                String path = node.path("filePath").asText();
                if (StringUtils.hasText(path)) {
                    out.add(path);
                }
            }
            node.forEach(child -> walkFilePaths(child, out));
        }
    }

    /**
     * 一次 IN+GROUP BY 批量取各 run 的步数，返回 runId→count 映射。
     * 消除 list() 中的 N+1 selectCount。
     */
    private Map<Long, Long> batchStepCounts(List<Long> runIds) {
        List<Map<String, Object>> rows = agentRunStepMapper.selectMaps(
                new QueryWrapper<AgentRunStepEntity>()
                        .select("run_id", "COUNT(*) AS cnt")
                        .in("run_id", runIds)
                        .groupBy("run_id"));
        Map<Long, Long> result = new HashMap<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object idVal = row.get("run_id");
            Object cntVal = row.get("cnt");
            if (idVal != null && cntVal != null) {
                result.put(((Number) idVal).longValue(), ((Number) cntVal).longValue());
            }
        }
        return result;
    }

    private void checkPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
    }

    private AgentRunVO toRunVO(AgentRunEntity r, int stepCount) {
        return AgentRunVO.builder()
                .id(r.getId())
                .question(r.getQuestion())
                .mode(r.getMode())
                .iterations(r.getIterations())
                .toolCalls(r.getToolCalls())
                .status(r.getStatus())
                .createdAt(r.getCreatedAt())
                .stepCount(stepCount)
                .sessionId(r.getSessionId())
                .build();
    }

    private AgentRunStepVO toStepVO(AgentRunStepEntity s) {
        return AgentRunStepVO.builder()
                .id(s.getId())
                .stepIndex(s.getStepIndex())
                .type(s.getType())
                .toolName(s.getToolName())
                .toolArgs(s.getToolArgs())
                .thought(s.getThought())
                .observationSummary(truncate(s.getObservation(), OBSERVATION_TRANSPORT_CHARS))
                .targetFiles(splitFiles(s.getTargetFiles()))
                .status(s.getStatus())
                .build();
    }

    private List<String> splitFiles(String targetFiles) {
        if (!StringUtils.hasText(targetFiles)) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String p : targetFiles.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
