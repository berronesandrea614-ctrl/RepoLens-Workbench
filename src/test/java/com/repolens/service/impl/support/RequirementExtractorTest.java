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

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 需求归纳器单测（mock LlmClient，无真实 LLM）。验证：
 * (a) LLM 返回规范的 "标题:...|摘要:..." 行 -> 解析出 title + summary；
 * (b) LLM 返回 NONE -> empty；
 * (c) LLM 抛异常 -> empty（异常不冒泡）。
 */
@ExtendWith(MockitoExtension.class)
class RequirementExtractorTest {

    @Mock
    private LlmClient llmClient;

    private RequirementExtractor newExtractor() {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(cfg, "modelName", "mock-code-assistant");
        ReflectionTestUtils.setField(cfg, "timeoutMs", 15000);
        return new RequirementExtractor(llmClient, cfg);
    }

    @Test
    void extract_shouldParseTitleAndSummaryFromWellFormedLine() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("标题: 支持用户注销账号 | 摘要: 在 UserService 中新增注销流程并清理关联数据")
                .success(true)
                .build());

        Optional<RequirementExtractor.ReqNote> note =
                newExtractor().extract("怎么加一个注销账号的功能", "可以在 UserService 里加一个 deactivate 方法。");

        Assertions.assertTrue(note.isPresent());
        Assertions.assertEquals("支持用户注销账号", note.get().title());
        Assertions.assertEquals("在 UserService 中新增注销流程并清理关联数据", note.get().summary());
    }

    @Test
    void extract_shouldReturnEmptyWhenNone() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("NONE")
                .success(true)
                .build());

        Optional<RequirementExtractor.ReqNote> note =
                newExtractor().extract("今天天气怎么样", "我无法回答天气问题。");

        Assertions.assertTrue(note.isEmpty());
    }

    @Test
    void extract_shouldReturnEmptyWhenLlmThrows() {
        when(llmClient.generate(any())).thenThrow(new RuntimeException("llm boom"));

        Optional<RequirementExtractor.ReqNote> note =
                newExtractor().extract("怎么加一个注销账号的功能", "可以在 UserService 里加。");

        // 异常必须被吞掉，绝不冒泡影响主回答流程。
        Assertions.assertTrue(note.isEmpty());
    }

    @Test
    void extract_shouldParseApproachWhenPresentInOutput() {
        // 新格式：标题|摘要|思路 三段
        when(llmClient.generate(any())).thenReturn(com.repolens.llm.model.LlmResponse.builder()
                .content("标题: 加注销功能 | 摘要: 在 UserService 加 deactivate | 思路: 先读控制器再找服务")
                .success(true)
                .build());

        Optional<RequirementExtractor.ReqNote> note =
                newExtractor().extract("怎么加注销功能", "可以加一个 deactivate 方法。");

        Assertions.assertTrue(note.isPresent());
        Assertions.assertEquals("加注销功能", note.get().title());
        Assertions.assertEquals("在 UserService 加 deactivate", note.get().summary());
        Assertions.assertEquals("先读控制器再找服务", note.get().approach());
    }

    @Test
    void extract_approachShouldBeNull_whenOldTwoSegmentFormat() {
        // 旧格式（无思路段）：向后兼容，approach 应为 null
        when(llmClient.generate(any())).thenReturn(com.repolens.llm.model.LlmResponse.builder()
                .content("标题: 加注销功能 | 摘要: 在 UserService 加 deactivate")
                .success(true)
                .build());

        Optional<RequirementExtractor.ReqNote> note =
                newExtractor().extract("怎么加注销功能", "可以加一个 deactivate 方法。");

        Assertions.assertTrue(note.isPresent());
        Assertions.assertNull(note.get().approach(), "旧格式不含思路段时 approach 应为 null");
    }
}
