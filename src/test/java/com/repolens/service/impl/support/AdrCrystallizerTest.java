package com.repolens.service.impl.support;

import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AdrCrystallizer 单测（mock LlmClient，无真实 LLM）。验证：
 * (a) LLM 返回规范的 MADR 格式行 -> 解析出全部字段，degraded=false；
 * (b) LLM 返回 null -> 模板降级，degraded=true；
 * (c) LLM 返回无法解析的内容（缺 DECISION）-> 模板降级，degraded=true；
 * (d) LLM 抛异常 -> 模板降级，degraded=true，异常不冒泡；
 * (e) 空输入 -> 占位符文本，degraded=true；
 * (f) 缺 OPTIONS 行但其余有效 -> options 为空列表，degraded=false。
 */
@ExtendWith(MockitoExtension.class)
class AdrCrystallizerTest {

    @Mock
    private LlmClient llmClient;

    private AdrCrystallizer newCrystallizer() {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(cfg, "modelName", "mock-code-assistant");
        ReflectionTestUtils.setField(cfg, "timeoutMs", 15000);
        return new AdrCrystallizer(llmClient, cfg);
    }

    /** Test 1: 规范 LLM 输出 → 解析出全部字段，degraded=false，drivers/options 按 | 分割 */
    @Test
    void crystallize_wellFormedOutput_parsedAllSectionsDegradedFalse() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("""
                        TITLE: Use Repository Pattern for ADR storage
                        CONTEXT: The team needs a consistent way to store ADR entries persistently.
                        DECISION: Adopt the Repository pattern backed by JPA entities.
                        CONSEQUENCES: Easier testing; adds JPA dependency.
                        DRIVERS: testability | persistence | abstraction
                        OPTIONS: JPA Repository | plain JDBC | file-based
                        """)
                .success(true)
                .build());

        AdrCrystallizer.CrystallizeInput input = new AdrCrystallizer.CrystallizeInput(
                "Use Repository Pattern for ADR storage",
                List.of(new AdrCrystallizer.StepNote("Define entity", "need JPA entity", null)),
                List.of("src/main/java/com/repolens/domain/entity/AdrEntity.java")
        );

        AdrCrystallizer.AdrDraft draft = newCrystallizer().crystallize(input);

        Assertions.assertFalse(draft.degraded());
        Assertions.assertEquals("Use Repository Pattern for ADR storage", draft.title());
        Assertions.assertEquals("The team needs a consistent way to store ADR entries persistently.", draft.context());
        Assertions.assertEquals("Adopt the Repository pattern backed by JPA entities.", draft.decision());
        Assertions.assertEquals(List.of("testability", "persistence", "abstraction"), draft.drivers());
        Assertions.assertEquals(List.of("JPA Repository", "plain JDBC", "file-based"), draft.options());
    }

    /** Test 2: LLM 返回 null → 模板降级，degraded=true，context 包含 approach 文本 */
    @Test
    void crystallize_llmReturnsNull_templateFallbackDegradedTrue() {
        when(llmClient.generate(any())).thenReturn(null);

        AdrCrystallizer.CrystallizeInput input = new AdrCrystallizer.CrystallizeInput(
                "Use event sourcing for audit trail",
                List.of(),
                List.of()
        );

        AdrCrystallizer.AdrDraft draft = newCrystallizer().crystallize(input);

        Assertions.assertTrue(draft.degraded());
        Assertions.assertTrue(draft.context().contains("Use event sourcing for audit trail"),
                "context should contain approach text when LLM returns null");
    }

    /** Test 3: LLM 返回无法解析内容（无 DECISION 标签）→ 模板降级，degraded=true */
    @Test
    void crystallize_llmReturnsGarbage_templateFallbackDegradedTrue() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("This is not a valid ADR format at all — missing required labels.")
                .success(true)
                .build());

        AdrCrystallizer.CrystallizeInput input = new AdrCrystallizer.CrystallizeInput(
                "Introduce caching layer",
                List.of(),
                List.of()
        );

        AdrCrystallizer.AdrDraft draft = newCrystallizer().crystallize(input);

        Assertions.assertTrue(draft.degraded());
    }

    /** Test 4: LLM 抛异常 → 模板降级，degraded=true，异常不冒泡 */
    @Test
    void crystallize_llmThrows_templateFallbackDegradedTrue_noExceptionPropagates() {
        when(llmClient.generate(any())).thenThrow(new RuntimeException("LLM service unavailable"));

        AdrCrystallizer.CrystallizeInput input = new AdrCrystallizer.CrystallizeInput(
                "Migrate to async messaging",
                List.of(),
                List.of()
        );

        // must not throw
        AdrCrystallizer.AdrDraft draft = Assertions.assertDoesNotThrow(
                () -> newCrystallizer().crystallize(input)
        );

        Assertions.assertTrue(draft.degraded());
    }

    /** Test 5: 空输入（null approach、空 steps/files）→ 非空草稿，占位符文本，degraded=true */
    @Test
    void crystallize_emptyInput_placeholderTextDegradedTrue() {
        when(llmClient.generate(any())).thenReturn(null);

        AdrCrystallizer.CrystallizeInput input = new AdrCrystallizer.CrystallizeInput(
                null,
                List.of(),
                List.of()
        );

        AdrCrystallizer.AdrDraft draft = newCrystallizer().crystallize(input);

        Assertions.assertNotNull(draft);
        Assertions.assertTrue(draft.degraded());
        Assertions.assertEquals("Architecture Decision", draft.title());
        Assertions.assertEquals("(no captured intent)", draft.context());
        Assertions.assertEquals("(decision not captured)", draft.decision());
        Assertions.assertNotNull(draft.consequences());
    }

    /** Test 6: LLM 输出缺 OPTIONS 行但其余有效 → options 为空列表，其他字段已解析，degraded=false */
    @Test
    void crystallize_missingOptionsLine_optionsEmptyDegradedFalse() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("""
                        TITLE: Adopt hexagonal architecture
                        CONTEXT: Need to decouple business logic from infrastructure.
                        DECISION: Use ports-and-adapters to isolate the domain.
                        CONSEQUENCES: Better testability; higher initial complexity.
                        DRIVERS: testability | maintainability
                        """)
                .success(true)
                .build());

        AdrCrystallizer.CrystallizeInput input = new AdrCrystallizer.CrystallizeInput(
                "Adopt hexagonal architecture",
                List.of(),
                List.of()
        );

        AdrCrystallizer.AdrDraft draft = newCrystallizer().crystallize(input);

        Assertions.assertFalse(draft.degraded());
        Assertions.assertEquals(List.of(), draft.options());
        Assertions.assertEquals(List.of("testability", "maintainability"), draft.drivers());
        Assertions.assertEquals("Adopt hexagonal architecture", draft.title());
        Assertions.assertEquals("Use ports-and-adapters to isolate the domain.", draft.decision());
    }
}
