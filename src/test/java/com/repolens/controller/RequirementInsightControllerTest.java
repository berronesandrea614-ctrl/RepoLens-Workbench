package com.repolens.controller;

import com.repolens.domain.vo.FlowEdgeVO;
import com.repolens.domain.vo.FlowNodeVO;
import com.repolens.domain.vo.RequirementInsightVO;
import com.repolens.service.ReconciliationService;
import com.repolens.service.RequirementInsightService;
import com.repolens.service.RequirementService;
import com.repolens.service.TraceabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repolens.controller.TestAuthUtils;

/**
 * 需求意图可视化接口控制层契约测试（standaloneSetup + mock，不加载 Spring 上下文）。
 *
 * <p>4 种形态（对应设计规格 §7 降级策略）：
 * <ol>
 *   <li>有计划 + 有偏差（完整视图）</li>
 *   <li>有计划 + 无偏差（完整视图，deviation=null）</li>
 *   <li>无计划 + 有改动（改动概览，planned=false）</li>
 *   <li>纯问答（无改动，hasChanges=false）</li>
 * </ol>
 */
class RequirementInsightControllerTest {

    private RequirementService requirementService;
    private RequirementInsightService requirementInsightService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        requirementService = mock(RequirementService.class);
        requirementInsightService = mock(RequirementInsightService.class);
        ReconciliationService reconciliationService = mock(ReconciliationService.class);
        TraceabilityService traceabilityService = mock(TraceabilityService.class);
        RequirementController controller =
                new RequirementController(requirementService, requirementInsightService, reconciliationService, traceabilityService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    // ── 形态 1：有计划 + 有偏差 ────────────────────────────────────────────────────

    @Test
    void insight_plannedWithDeviation_returnsFullVO() throws Exception {
        FlowNodeVO changedNode = FlowNodeVO.builder()
                .nodeType("node")
                .role("Service")
                .name("CaptchaService.generate")
                .cls("new")
                .filePath("src/main/java/CaptchaService.java")
                .startLine(10)
                .changeId(1L)
                .symbolId(100L)
                .delta("+46")
                .build();

        FlowNodeVO offPlanNode = FlowNodeVO.builder()
                .nodeType("node")
                .role("Service")
                .name("SecurityConfig")
                .cls("offp")
                .filePath("src/main/java/SecurityConfig.java")
                .changeId(2L)
                .delta("~3")
                .build();

        RequirementInsightVO.InsightStep planStep = RequirementInsightVO.InsightStep.builder()
                .index(0)
                .title("生成验证码")
                .why("先做生成端，新建 CaptchaService")
                .kind("in")
                .toolReads(List.of("src/main/java/LoginController.java"))
                .insight("把生成逻辑抽离到独立 Service，便于测试")
                .flow(List.of(changedNode))
                .build();

        RequirementInsightVO.InsightStep offPlanStep = RequirementInsightVO.InsightStep.builder()
                .index(1)
                .title("🚩 计划外改动")
                .kind("off")
                .toolReads(List.of())
                .insight("AI 在计划声明范围外额外改动了 1 个文件，请确认是否合理")
                .flow(List.of(offPlanNode))
                .build();

        RequirementInsightVO vo = RequirementInsightVO.builder()
                .intent("给登录接口加图形验证码")
                .approach("过滤器拦一道→验证码抽成服务→Redis 存 5min")
                .planned(true)
                .hasChanges(true)
                .chips(RequirementInsightVO.Chips.builder()
                        .filesChanged(2).added(1).modified(1)
                        .plannedStepsDone(1).plannedStepsTotal(1)
                        .offPlanCount(1)
                        .build())
                .deviation(RequirementInsightVO.Deviation.builder()
                        .files(List.of("src/main/java/SecurityConfig.java"))
                        .note("AI 声明只改 1 处，实际多动了：src/main/java/SecurityConfig.java")
                        .build())
                .steps(List.of(planStep, offPlanStep))
                .footer(RequirementInsightVO.InsightFooter.builder()
                        .plannedDone("1/1")
                        .offPlanPending(1)
                        .impactNote("共改动 2 个文件，影响层次见全景图")
                        .build())
                .panorama(RequirementInsightVO.Panorama.builder()
                        .layers(List.of(
                                RequirementInsightVO.PanoramaLayer.builder()
                                        .label("Service")
                                        .flow(List.of(changedNode))
                                        .build()))
                        .build())
                .build();

        when(requirementInsightService.insight(eq(1L), eq(7L), eq(10L))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/requirements/10/insight")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // 意图 + approach
                .andExpect(jsonPath("$.data.intent").value("给登录接口加图形验证码"))
                .andExpect(jsonPath("$.data.approach").value("过滤器拦一道→验证码抽成服务→Redis 存 5min"))
                .andExpect(jsonPath("$.data.planned").value(true))
                .andExpect(jsonPath("$.data.hasChanges").value(true))
                // chips
                .andExpect(jsonPath("$.data.chips.filesChanged").value(2))
                .andExpect(jsonPath("$.data.chips.offPlanCount").value(1))
                .andExpect(jsonPath("$.data.chips.plannedStepsDone").value(1))
                .andExpect(jsonPath("$.data.chips.plannedStepsTotal").value(1))
                // deviation 非 null
                .andExpect(jsonPath("$.data.deviation").exists())
                .andExpect(jsonPath("$.data.deviation.files[0]").value("src/main/java/SecurityConfig.java"))
                // steps
                .andExpect(jsonPath("$.data.steps").isArray())
                .andExpect(jsonPath("$.data.steps[0].kind").value("in"))
                .andExpect(jsonPath("$.data.steps[0].title").value("生成验证码"))
                .andExpect(jsonPath("$.data.steps[0].toolReads[0]").value("src/main/java/LoginController.java"))
                .andExpect(jsonPath("$.data.steps[1].kind").value("off"))
                .andExpect(jsonPath("$.data.steps[1].title").value("🚩 计划外改动"))
                // flow 节点定位字段断言
                .andExpect(jsonPath("$.data.steps[0].flow[0].nodeType").value("node"))
                .andExpect(jsonPath("$.data.steps[0].flow[0].filePath").value("src/main/java/CaptchaService.java"))
                .andExpect(jsonPath("$.data.steps[0].flow[0].startLine").value(10))
                .andExpect(jsonPath("$.data.steps[0].flow[0].changeId").value(1))
                .andExpect(jsonPath("$.data.steps[0].flow[0].symbolId").value(100))
                .andExpect(jsonPath("$.data.steps[0].flow[0].cls").value("new"))
                // footer
                .andExpect(jsonPath("$.data.footer.plannedDone").value("1/1"))
                .andExpect(jsonPath("$.data.footer.offPlanPending").value(1))
                // panorama 非 null（有计划且有改动时存在）
                .andExpect(jsonPath("$.data.panorama").exists())
                .andExpect(jsonPath("$.data.panorama.layers[0].label").value("Service"));
    }

    // ── 形态 2：有计划 + 无偏差 ───────────────────────────────────────────────────

    @Test
    void insight_plannedClean_deviationNull() throws Exception {
        FlowNodeVO node = FlowNodeVO.builder()
                .nodeType("node")
                .role("Service")
                .name("UserService.login")
                .cls("mod")
                .filePath("src/main/java/UserService.java")
                .changeId(10L)
                .symbolId(200L)
                .startLine(30)
                .endLine(50)
                .delta("~5")
                .build();

        FlowEdgeVO edge = FlowEdgeVO.builder()
                .nodeType("edge")
                .data("调用")
                .mut(false)
                .build();

        FlowNodeVO node2 = FlowNodeVO.builder()
                .nodeType("node")
                .role("Mapper")
                .name("UserMapper.findByName")
                .cls("mod")
                .filePath("src/main/java/UserMapper.java")
                .changeId(11L)
                .symbolId(201L)
                .delta("~2")
                .build();

        RequirementInsightVO.InsightStep step = RequirementInsightVO.InsightStep.builder()
                .index(0)
                .title("更新登录逻辑")
                .why("登录时加验证码校验")
                .kind("in")
                .toolReads(List.of("src/main/java/UserService.java"))
                .insight("在 login 方法中前置校验，失败早返回，DB 零冲击")
                .flow(List.of(node, edge, node2))
                .build();

        RequirementInsightVO vo = RequirementInsightVO.builder()
                .intent("登录加验证码校验")
                .approach("在 login 方法前置校验")
                .planned(true)
                .hasChanges(true)
                .chips(RequirementInsightVO.Chips.builder()
                        .filesChanged(2).added(0).modified(2)
                        .plannedStepsDone(1).plannedStepsTotal(1)
                        .offPlanCount(0)
                        .build())
                .deviation(null)
                .steps(List.of(step))
                .footer(RequirementInsightVO.InsightFooter.builder()
                        .plannedDone("1/1")
                        .offPlanPending(0)
                        .impactNote("共改动 2 个文件，影响层次见全景图")
                        .build())
                .panorama(RequirementInsightVO.Panorama.builder()
                        .layers(List.of(
                                RequirementInsightVO.PanoramaLayer.builder()
                                        .label("Service")
                                        .flow(List.of(node))
                                        .build()))
                        .build())
                .build();

        when(requirementInsightService.insight(eq(1L), eq(7L), eq(20L))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/requirements/20/insight")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.planned").value(true))
                .andExpect(jsonPath("$.data.hasChanges").value(true))
                // deviation 为 null
                .andExpect(jsonPath("$.data.deviation").doesNotExist())
                .andExpect(jsonPath("$.data.chips.offPlanCount").value(0))
                // steps: 只有一个计划内步骤
                .andExpect(jsonPath("$.data.steps").isArray())
                .andExpect(jsonPath("$.data.steps[0].kind").value("in"))
                .andExpect(jsonPath("$.data.steps[0].title").value("更新登录逻辑"))
                // flow 包含节点和边（边 nodeType=edge）
                .andExpect(jsonPath("$.data.steps[0].flow[0].nodeType").value("node"))
                .andExpect(jsonPath("$.data.steps[0].flow[1].nodeType").value("edge"))
                .andExpect(jsonPath("$.data.steps[0].flow[2].nodeType").value("node"))
                // 节点定位字段
                .andExpect(jsonPath("$.data.steps[0].flow[0].filePath").value("src/main/java/UserService.java"))
                .andExpect(jsonPath("$.data.steps[0].flow[0].symbolId").value(200))
                .andExpect(jsonPath("$.data.steps[0].flow[0].startLine").value(30))
                .andExpect(jsonPath("$.data.steps[0].flow[0].endLine").value(50))
                .andExpect(jsonPath("$.data.steps[0].flow[0].changeId").value(10))
                // footer plannedDone
                .andExpect(jsonPath("$.data.footer.plannedDone").value("1/1"))
                .andExpect(jsonPath("$.data.footer.offPlanPending").value(0));
    }

    // ── 形态 3：无计划 + 有改动 ───────────────────────────────────────────────────

    @Test
    void insight_unplannedWithChanges_plannedFalse() throws Exception {
        FlowNodeVO node = FlowNodeVO.builder()
                .nodeType("node")
                .role("Controller")
                .name("UserController.getById")
                .cls("mod")
                .filePath("src/main/java/UserController.java")
                .changeId(5L)
                .delta("~3")
                .build();

        RequirementInsightVO.InsightStep overviewStep = RequirementInsightVO.InsightStep.builder()
                .index(0)
                .title("改动概览")
                .kind("in")
                .toolReads(List.of())
                .flow(List.of(node))
                .build();

        RequirementInsightVO vo = RequirementInsightVO.builder()
                .intent("为 getById 加注释")
                .approach(null)
                .planned(false)
                .hasChanges(true)
                .chips(RequirementInsightVO.Chips.builder()
                        .filesChanged(1).added(0).modified(1)
                        .plannedStepsDone(0).plannedStepsTotal(0)
                        .offPlanCount(0)
                        .build())
                .deviation(null)
                .steps(List.of(overviewStep))
                .footer(RequirementInsightVO.InsightFooter.builder()
                        .plannedDone(null)
                        .offPlanPending(0)
                        .impactNote("共改动 1 个文件，无结构化计划")
                        .build())
                .panorama(RequirementInsightVO.Panorama.builder()
                        .layers(List.of(
                                RequirementInsightVO.PanoramaLayer.builder()
                                        .label("Controller")
                                        .flow(List.of(node))
                                        .build()))
                        .build())
                .build();

        when(requirementInsightService.insight(eq(1L), eq(7L), eq(30L))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/requirements/30/insight")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.planned").value(false))
                .andExpect(jsonPath("$.data.hasChanges").value(true))
                // 单步「改动概览」
                .andExpect(jsonPath("$.data.steps").isArray())
                .andExpect(jsonPath("$.data.steps.length()").value(1))
                .andExpect(jsonPath("$.data.steps[0].title").value("改动概览"))
                .andExpect(jsonPath("$.data.steps[0].kind").value("in"))
                .andExpect(jsonPath("$.data.steps[0].why").doesNotExist())
                // deviation 为 null（无计划无从比较）
                .andExpect(jsonPath("$.data.deviation").doesNotExist())
                // plannedDone 为 null
                .andExpect(jsonPath("$.data.footer.plannedDone").doesNotExist())
                // flow 节点
                .andExpect(jsonPath("$.data.steps[0].flow[0].nodeType").value("node"))
                .andExpect(jsonPath("$.data.steps[0].flow[0].cls").value("mod"))
                .andExpect(jsonPath("$.data.steps[0].flow[0].changeId").value(5));
    }

    // ── 形态 4：纯问答（无改动）──────────────────────────────────────────────────

    @Test
    void insight_pureAsk_hasChangesFalse() throws Exception {
        RequirementInsightVO.InsightStep evidenceStep = RequirementInsightVO.InsightStep.builder()
                .index(0)
                .title("AI 的回答依据")
                .kind("in")
                .toolReads(List.of(
                        "src/main/java/UserService.java",
                        "src/main/java/UserRepository.java"))
                .insight(null)
                .flow(List.of())
                .build();

        RequirementInsightVO vo = RequirementInsightVO.builder()
                .intent("getUserById 返回什么类型")
                .approach(null)
                .planned(false)
                .hasChanges(false)
                .chips(RequirementInsightVO.Chips.builder()
                        .filesChanged(0).added(0).modified(0)
                        .plannedStepsDone(0).plannedStepsTotal(0)
                        .offPlanCount(0)
                        .build())
                .deviation(null)
                .steps(List.of(evidenceStep))
                .footer(RequirementInsightVO.InsightFooter.builder()
                        .plannedDone(null)
                        .offPlanPending(0)
                        .impactNote("纯问答需求，无代码改动")
                        .build())
                .panorama(null)
                .build();

        when(requirementInsightService.insight(eq(1L), eq(7L), eq(40L))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/requirements/40/insight")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hasChanges").value(false))
                .andExpect(jsonPath("$.data.planned").value(false))
                // 单步「AI 的回答依据」
                .andExpect(jsonPath("$.data.steps.length()").value(1))
                .andExpect(jsonPath("$.data.steps[0].title").value("AI 的回答依据"))
                .andExpect(jsonPath("$.data.steps[0].kind").value("in"))
                .andExpect(jsonPath("$.data.steps[0].toolReads[0]").value("src/main/java/UserService.java"))
                .andExpect(jsonPath("$.data.steps[0].toolReads[1]").value("src/main/java/UserRepository.java"))
                // no deviation, no panorama
                .andExpect(jsonPath("$.data.deviation").doesNotExist())
                .andExpect(jsonPath("$.data.panorama").doesNotExist())
                // chips all zero
                .andExpect(jsonPath("$.data.chips.filesChanged").value(0))
                .andExpect(jsonPath("$.data.chips.offPlanCount").value(0))
                // impactNote
                .andExpect(jsonPath("$.data.footer.impactNote").value("纯问答需求，无代码改动"));
    }
}
