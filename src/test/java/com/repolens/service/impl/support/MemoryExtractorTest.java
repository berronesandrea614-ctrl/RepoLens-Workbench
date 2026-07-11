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
 * 记忆抽取器单测（mock LlmClient，无真实 LLM）。验证：
 * (a) LLM 返回规范的记忆行 -> 解析出 content + keywords；
 * (b) LLM 返回 NONE -> empty；
 * (c) LLM 抛异常 -> empty（异常不冒泡）。
 */
@ExtendWith(MockitoExtension.class)
class MemoryExtractorTest {

    @Mock
    private LlmClient llmClient;

    private MemoryExtractor newExtractor() {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(cfg, "modelName", "mock-code-assistant");
        ReflectionTestUtils.setField(cfg, "timeoutMs", 15000);
        return new MemoryExtractor(llmClient, cfg);
    }

    @Test
    void extract_shouldParseContentAndKeywordsFromWellFormedLine() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("记忆: 用户服务在 UserService 类中实现 | 关键词: 用户服务,UserService,实现 | 类型: FACT | 重要性: 4")
                .success(true)
                .build());

        Optional<MemoryExtractor.MemoryNote> note =
                newExtractor().extract("用户服务怎么实现", "在 UserService 中实现。");

        Assertions.assertTrue(note.isPresent());
        Assertions.assertEquals("用户服务在 UserService 类中实现", note.get().content());
        Assertions.assertEquals("用户服务,UserService,实现", note.get().keywords());
        Assertions.assertEquals("FACT", note.get().memoryType());
        Assertions.assertEquals(4, note.get().importance());
    }

    @Test
    void extract_shouldParsePreferenceTypeAndImportance() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("记忆: 用户偏好中文回答 | 关键词: 偏好,中文 | 类型: PREFERENCE | 重要性: 5")
                .success(true)
                .build());

        Optional<MemoryExtractor.MemoryNote> note =
                newExtractor().extract("请用中文回答", "好的，我会用中文。");

        Assertions.assertTrue(note.isPresent());
        Assertions.assertEquals("PREFERENCE", note.get().memoryType());
        Assertions.assertEquals(5, note.get().importance());
    }

    @Test
    void extract_shouldDefaultToFactAndImportance3WhenTypeOrImportanceMalformed() {
        // 类型和重要性缺失时，回退为默认值：FACT / 3
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("记忆: 缓存采用 Redis | 关键词: 缓存,Redis")
                .success(true)
                .build());

        Optional<MemoryExtractor.MemoryNote> note =
                newExtractor().extract("缓存怎么实现", "用 Redis。");

        Assertions.assertTrue(note.isPresent());
        Assertions.assertEquals("缓存采用 Redis", note.get().content());
        Assertions.assertEquals("FACT", note.get().memoryType());
        Assertions.assertEquals(3, note.get().importance());
    }

    @Test
    void extract_shouldReturnEmptyWhenNone() {
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("NONE")
                .success(true)
                .build());

        Optional<MemoryExtractor.MemoryNote> note =
                newExtractor().extract("今天天气怎么样", "我无法回答。");

        Assertions.assertTrue(note.isEmpty());
    }

    @Test
    void extract_shouldReturnEmptyWhenLlmThrows() {
        when(llmClient.generate(any())).thenThrow(new RuntimeException("llm boom"));

        Optional<MemoryExtractor.MemoryNote> note =
                newExtractor().extract("用户服务怎么实现", "在 UserService 中实现。");

        // 异常必须被吞掉，绝不冒泡影响主回答流程。
        Assertions.assertTrue(note.isEmpty());
    }
}
