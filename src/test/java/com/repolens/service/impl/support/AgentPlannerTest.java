package com.repolens.service.impl.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.vo.CodeReferenceVO;
import com.repolens.llm.LlmClient;
import com.repolens.llm.LlmClientException;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentPlannerTest {

    @Mock
    private LlmClient llmClient;

    private AgentPlanner planner;

    @BeforeEach
    void setUp() {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(cfg, "modelName", "mock-code-assistant");
        ReflectionTestUtils.setField(cfg, "timeoutMs", 15000);
        planner = new AgentPlanner(llmClient, cfg, new ObjectMapper());
    }

    @Test
    void plan_returnsTrimmedPlan_whenLlmReturnsPlan() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("  1. searchCodeChunks 定位入口\n2. findMethodCallers 追调用链  ")
                .success(true)
                .build());

        String plan = planner.plan("支付流程在哪里？", sampleEvidence());

        Assertions.assertNotNull(plan);
        // 已 trim：首尾空白被去掉。
        Assertions.assertTrue(plan.startsWith("1. searchCodeChunks"));
        Assertions.assertTrue(plan.endsWith("追调用链"));
    }

    @Test
    void plan_returnsNull_whenLlmReturnsEmpty() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("   ")
                .success(true)
                .build());

        Assertions.assertNull(planner.plan("支付流程在哪里？", sampleEvidence()));
    }

    @Test
    void plan_returnsNull_andDoesNotThrow_whenLlmThrows() {
        when(llmClient.generate(any()))
                .thenThrow(new LlmClientException("LLM_CONFIG_MISSING", "api-key missing"));

        Assertions.assertDoesNotThrow(() -> {
            String plan = planner.plan("支付流程在哪里？", sampleEvidence());
            Assertions.assertNull(plan);
        });
    }

    @Test
    void plan_cappedToMaxChars_whenLlmReturnsLongPlan() {
        String longPlan = "x".repeat(2000);
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content(longPlan)
                .success(true)
                .build());

        String plan = planner.plan("支付流程在哪里？", sampleEvidence());

        Assertions.assertNotNull(plan);
        Assertions.assertEquals(800, plan.length());
    }

    // ---- planStructured tests ----

    @Test
    void planStructured_returnsStructuredPlan_whenLlmReturnsValidJson() {
        String json = "{\"approach\":\"先读控制器再找服务\",\"steps\":["
                + "{\"title\":\"读控制器\",\"why\":\"定位入口\",\"declaredFiles\":[\"PayController.java\"],\"insight\":\"入口关键\"},"
                + "{\"title\":\"找服务\",\"why\":\"追调用\",\"declaredFiles\":[],\"insight\":\"核心逻辑\"}"
                + "]}";
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content(json).success(true).build());

        Optional<AgentPlanner.StructuredPlan> result = planner.planStructured("支付流程在哪里？", sampleEvidence());

        Assertions.assertTrue(result.isPresent());
        AgentPlanner.StructuredPlan plan = result.get();
        Assertions.assertTrue(plan.hasStructure());
        Assertions.assertEquals("先读控制器再找服务", plan.approach());
        Assertions.assertNotNull(plan.steps());
        Assertions.assertEquals(2, plan.steps().size());
        Assertions.assertEquals("读控制器", plan.steps().get(0).title);
        Assertions.assertNotNull(plan.planText());
        Assertions.assertTrue(plan.planText().contains("先读控制器再找服务"));
    }

    @Test
    void planStructured_fallsBackToPlainText_whenJsonParseFails() {
        // LLM returns plain text (not JSON) → parse failure → planText set, approach = null
        String plainText = "1. 搜索支付相关代码\n2. 定位控制器";
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content(plainText).success(true).build());

        Optional<AgentPlanner.StructuredPlan> result = planner.planStructured("支付流程在哪里？", sampleEvidence());

        Assertions.assertTrue(result.isPresent());
        AgentPlanner.StructuredPlan plan = result.get();
        Assertions.assertFalse(plan.hasStructure(), "JSON parse failure should produce no structure");
        Assertions.assertNull(plan.approach());
        Assertions.assertNull(plan.steps());
        Assertions.assertNotNull(plan.planText(), "planText must still be set for prompt injection");
    }

    @Test
    void planStructured_returnsEmpty_whenLlmThrows() {
        when(llmClient.generate(any())).thenThrow(new LlmClientException("LLM_CONFIG_MISSING", "no key"));

        Optional<AgentPlanner.StructuredPlan> result = planner.planStructured("支付流程在哪里？", sampleEvidence());

        Assertions.assertTrue(result.isEmpty(), "LLM failure should return empty Optional");
    }

    @Test
    void planStructured_returnsEmpty_whenLlmReturnsEmpty() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("   ").success(true).build());

        Optional<AgentPlanner.StructuredPlan> result = planner.planStructured("q", sampleEvidence());

        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    void planStructured_stripsMarkdownCodeBlock_beforeJsonParse() {
        String json = "```json\n{\"approach\":\"整体思路\",\"steps\":[]}\n```";
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content(json).success(true).build());

        Optional<AgentPlanner.StructuredPlan> result = planner.planStructured("q", sampleEvidence());

        Assertions.assertTrue(result.isPresent());
        // approach present means JSON parse succeeded despite markdown wrapping
        Assertions.assertTrue(result.get().hasStructure());
        Assertions.assertEquals("整体思路", result.get().approach());
    }

    private List<CodeReferenceVO> sampleEvidence() {
        return List.of(CodeReferenceVO.builder()
                .filePath("src/main/java/com/example/PayService.java")
                .startLine(10)
                .endLine(40)
                .build());
    }
}
