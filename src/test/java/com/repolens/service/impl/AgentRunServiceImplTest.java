package com.repolens.service.impl;

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
import com.repolens.service.impl.support.AgentPlanner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 执行记录读写层单测（mock mapper + PermissionService，无真实 DB）。验证：
 * (a) record 为每个 AgentStepVO 落一条 step，并正确推断 type（write→WRITE / 其他→TOOL / 无工具→THINK），
 *     且从 toolArgs 的 filePath 解析 target_files；
 * (b) list 按最新在前返回 VO，附带 stepCount（批量 selectMaps，无 N+1）；
 * (c) trace 返回 run + steps，observationSummary 截断；
 * (d) 权限不通过抛 FORBIDDEN，run 不存在抛 NOT_FOUND，run 不属于该 repo 抛 FORBIDDEN；
 * (e) target_files 在整条路径边界截断（不产生半路径字符串）。
 */
@ExtendWith(MockitoExtension.class)
class AgentRunServiceImplTest {

    private static final Long USER = 1L;
    private static final Long REPO = 2L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 3, 12, 0, 0);

    @Mock
    private AgentRunMapper agentRunMapper;
    @Mock
    private AgentRunStepMapper agentRunStepMapper;
    @Mock
    private AgentRunPlanMapper agentRunPlanMapper;
    @Mock
    private PermissionService permissionService;

    private AgentRunServiceImpl newService() {
        return new AgentRunServiceImpl(agentRunMapper, agentRunStepMapper, agentRunPlanMapper,
                permissionService, new ObjectMapper());
    }

    private AgentRunEntity run(long id, Long repoId, LocalDateTime createdAt) {
        AgentRunEntity r = new AgentRunEntity();
        r.setId(id);
        r.setRepoId(repoId);
        r.setSessionId(9L);
        r.setUserId(USER);
        r.setQuestion("q-" + id);
        r.setMode("ask");
        r.setIterations(3);
        r.setToolCalls(2);
        r.setStatus("DONE");
        r.setCreatedAt(createdAt);
        return r;
    }

    @Test
    void record_shouldInsertRunAndStepsWithCorrectTypeAndTargetFiles() {
        // run.insert 回填自增 id，供 step 关联。
        when(agentRunMapper.insert(any(AgentRunEntity.class))).thenAnswer(inv -> {
            Object entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 100L);
            return 1;
        });

        List<AgentStepVO> steps = List.of(
                // TOOL：非写工具，带 filePath -> target_files 解析
                AgentStepVO.builder().stepIndex(1).toolName("getFileContent")
                        .toolArgs("{\"filePath\":\"src/A.java\"}").thought("read A")
                        .observation("content...").discoveredCount(1).build(),
                // TOOL：搜索工具，无 filePath -> target_files 空
                AgentStepVO.builder().stepIndex(2).toolName("searchCodeChunks")
                        .toolArgs("{\"query\":\"pay\"}").thought("search").observation("hits").build(),
                // WRITE：写工具
                AgentStepVO.builder().stepIndex(3).toolName("writeFileContent")
                        .toolArgs("{\"filePath\":\"src/B.java\",\"content\":\"x\"}").thought("write B")
                        .observation("ok").build(),
                // THINK：无工具
                AgentStepVO.builder().stepIndex(4).toolName(null).thought("just thinking").build());

        Long runId = newService().record(REPO, 9L, USER, "how to pay?", "code", "the answer text", 4, 3, steps);

        Assertions.assertEquals(100L, runId);
        ArgumentCaptor<AgentRunEntity> runCaptor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(agentRunMapper, times(1)).insert(runCaptor.capture());
        Assertions.assertEquals("DONE", runCaptor.getValue().getStatus());
        Assertions.assertEquals("code", runCaptor.getValue().getMode());
        Assertions.assertEquals("the answer text", runCaptor.getValue().getAnswerPreview());

        ArgumentCaptor<AgentRunStepEntity> stepCaptor = ArgumentCaptor.forClass(AgentRunStepEntity.class);
        verify(agentRunStepMapper, times(4)).insert(stepCaptor.capture());
        List<AgentRunStepEntity> inserted = stepCaptor.getAllValues();

        Assertions.assertEquals("TOOL", inserted.get(0).getType());
        Assertions.assertEquals("src/A.java", inserted.get(0).getTargetFiles());
        Assertions.assertEquals(100L, inserted.get(0).getRunId());

        Assertions.assertEquals("TOOL", inserted.get(1).getType());
        Assertions.assertNull(inserted.get(1).getTargetFiles());

        Assertions.assertEquals("WRITE", inserted.get(2).getType());
        Assertions.assertEquals("src/B.java", inserted.get(2).getTargetFiles());

        Assertions.assertEquals("THINK", inserted.get(3).getType());
        Assertions.assertNull(inserted.get(3).getTargetFiles());
    }

    @Test
    void list_shouldReturnNewestFirstWithStepCount() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        AgentRunEntity r10 = run(10, REPO, NOW.minusDays(2));
        AgentRunEntity r11 = run(11, REPO, NOW.minusDays(1));
        // DB 已按 id DESC 排序，最新在前。
        when(agentRunMapper.selectList(any())).thenReturn(List.of(r11, r10));
        // 批量步数：selectMaps 返回 run_id→cnt 行（替代 N+1 selectCount）。
        when(agentRunStepMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("run_id", 11L, "cnt", 2L),
                Map.of("run_id", 10L, "cnt", 5L)));

        List<AgentRunVO> result = newService().list(USER, REPO, null);

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals(11L, result.get(0).getId());
        Assertions.assertEquals(2, result.get(0).getStepCount());
        Assertions.assertEquals(10L, result.get(1).getId());
        Assertions.assertEquals(5, result.get(1).getStepCount());
    }

    @Test
    void list_shouldThrowForbiddenWhenNoPermission() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(false);
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> newService().list(USER, REPO, null));
        Assertions.assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void trace_shouldReturnRunAndSteps() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(agentRunMapper.selectById(100L)).thenReturn(run(100, REPO, NOW));

        AgentRunStepEntity s1 = new AgentRunStepEntity();
        s1.setId(1L);
        s1.setRunId(100L);
        s1.setStepIndex(1);
        s1.setType("TOOL");
        s1.setToolName("getFileContent");
        s1.setObservation("x".repeat(5000));
        s1.setTargetFiles("src/A.java,src/B.java");
        s1.setStatus("DONE");
        when(agentRunStepMapper.selectList(any())).thenReturn(List.of(s1));

        AgentRunTraceVO trace = newService().trace(USER, REPO, 100L);

        Assertions.assertEquals(100L, trace.getRun().getId());
        Assertions.assertEquals(1, trace.getRun().getStepCount());
        Assertions.assertEquals(1, trace.getSteps().size());
        AgentRunStepVO step = trace.getSteps().get(0);
        Assertions.assertEquals("TOOL", step.getType());
        Assertions.assertEquals(1000, step.getObservationSummary().length());
        Assertions.assertEquals(List.of("src/A.java", "src/B.java"), step.getTargetFiles());
    }

    @Test
    void trace_shouldThrowNotFoundWhenMissing() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(agentRunMapper.selectById(100L)).thenReturn(null);
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> newService().trace(USER, REPO, 100L));
        Assertions.assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void trace_shouldThrowForbiddenWhenRunBelongsToAnotherRepo() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(agentRunMapper.selectById(100L)).thenReturn(run(100, 99L, NOW));
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> newService().trace(USER, REPO, 100L));
        Assertions.assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    /**
     * target_files 截断边界测试：当多条路径合计超过 1000 字符时，
     * 必须在整条路径边界截断（不允许截断到路径字符串中间）。
     */
    @Test
    void record_targetFiles_shouldTruncateOnEntryBoundary() {
        when(agentRunMapper.insert(any(AgentRunEntity.class))).thenAnswer(inv -> {
            Object entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 200L);
            return 1;
        });

        // 构造路径：p1=990 字节、p2=20 字节；拼接后 p1+","+p2 = 1011 字节 > 1000。
        // 期望：存入 p1（990 字节），p2 被丢弃，不产生截断的半路径。
        String p1 = "a".repeat(990);
        String p2 = "b".repeat(20);
        // toolArgs 含两个 filePath（数组形式）
        String toolArgs = String.format(
                "[{\"filePath\":\"%s\"},{\"filePath\":\"%s\"}]", p1, p2);
        List<AgentStepVO> steps = List.of(
                AgentStepVO.builder().stepIndex(1).toolName("getFileContent")
                        .toolArgs(toolArgs).thought("multi-file").build());

        newService().record(REPO, 9L, USER, "q", "ask", "a", 1, 1, steps);

        ArgumentCaptor<AgentRunStepEntity> cap = ArgumentCaptor.forClass(AgentRunStepEntity.class);
        verify(agentRunStepMapper, times(1)).insert(cap.capture());
        String stored = cap.getValue().getTargetFiles();

        // 必须恰好等于 p1，不包含 p2，且不超过上限。
        Assertions.assertNotNull(stored);
        Assertions.assertEquals(p1, stored, "target_files 应在路径边界截断，保留完整的首条路径");
        Assertions.assertFalse(stored.contains(","), "超限路径不应追加到结果中");
        Assertions.assertTrue(stored.length() <= 1000, "结果不得超过 1000 字符上限");
    }

    /**
     * record with structured plan: agent_run_plan 表应该在结构化计划存在时被插入一条记录。
     * (a) hasStructure()=true → agentRunPlanMapper.insert 被调用一次，approach 和 planJson 非空；
     * (b) hasStructure()=false（降级到纯文本）→ 不插入 agent_run_plan。
     */
    @Test
    void record_withStructuredPlan_shouldInsertAgentRunPlan() {
        when(agentRunMapper.insert(any(AgentRunEntity.class))).thenAnswer(inv -> {
            Object entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 300L);
            return 1;
        });

        AgentPlanner.StructuredPlan plan = new AgentPlanner.StructuredPlan(
                "整体思路文本",
                "先读后改的整体思路",
                List.of());

        newService().record(REPO, 9L, USER, "q", "code", "answer", 2, 1, List.of(), plan);

        ArgumentCaptor<AgentRunPlanEntity> planCaptor = ArgumentCaptor.forClass(AgentRunPlanEntity.class);
        verify(agentRunPlanMapper, times(1)).insert(planCaptor.capture());
        AgentRunPlanEntity saved = planCaptor.getValue();
        Assertions.assertEquals(300L, saved.getAgentRunId());
        Assertions.assertEquals("先读后改的整体思路", saved.getApproach());
        Assertions.assertNotNull(saved.getPlanJson(), "steps JSON should be serialized");
    }

    @Test
    void record_withNullPlan_shouldNotInsertAgentRunPlan() {
        when(agentRunMapper.insert(any(AgentRunEntity.class))).thenAnswer(inv -> {
            Object entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 301L);
            return 1;
        });

        newService().record(REPO, 9L, USER, "q", "ask", "answer", 2, 1, List.of(), null);

        verify(agentRunPlanMapper, org.mockito.Mockito.never()).insert(any(AgentRunPlanEntity.class));
    }

    @Test
    void record_withFallbackPlan_noStructure_shouldNotInsertAgentRunPlan() {
        when(agentRunMapper.insert(any(AgentRunEntity.class))).thenAnswer(inv -> {
            Object entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 302L);
            return 1;
        });

        // plan 存在但 approach = null → hasStructure() = false → 不落库
        AgentPlanner.StructuredPlan fallback = new AgentPlanner.StructuredPlan("plaintext plan", null, null);
        newService().record(REPO, 9L, USER, "q", "code", "answer", 2, 1, List.of(), fallback);

        verify(agentRunPlanMapper, org.mockito.Mockito.never()).insert(any(AgentRunPlanEntity.class));
    }
}
