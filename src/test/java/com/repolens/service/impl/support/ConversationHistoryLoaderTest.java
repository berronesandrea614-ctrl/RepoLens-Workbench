package com.repolens.service.impl.support;

import com.repolens.domain.entity.ChatMessageEntity;
import com.repolens.llm.model.LlmMessage;
import com.repolens.mapper.ChatMessageMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 短期对话记忆加载器单测。验证：
 * (a) 有历史消息时按 旧→新 顺序映射为 LlmMessage 且 role 正确；
 * (b) 空会话返回空 list；
 * (c) maxChars 很小时从最旧丢弃、保留最新。
 */
@ExtendWith(MockitoExtension.class)
class ConversationHistoryLoaderTest {

    @Mock
    private ChatMessageMapper chatMessageMapper;

    private ConversationHistoryLoader newLoader() {
        return new ConversationHistoryLoader(chatMessageMapper);
    }

    private ChatMessageEntity msg(long id, String role, String content) {
        ChatMessageEntity e = new ChatMessageEntity();
        e.setId(id);
        e.setSessionId(7L);
        e.setRole(role);
        e.setContent(content);
        return e;
    }

    @Test
    void load_shouldMapMessagesOldestToNewestWithRoleMapping() {
        List<ChatMessageEntity> stored = List.of(
                msg(1, "USER", "q1"),
                msg(2, "ASSISTANT", "a1"),
                msg(3, "USER", "q2"),
                msg(4, "ASSISTANT", "a2")
        );
        when(chatMessageMapper.selectList(any())).thenReturn(new ArrayList<>(stored));

        List<LlmMessage> result = newLoader().load(7L, 3, 4000);

        Assertions.assertEquals(4, result.size());
        Assertions.assertEquals("user", result.get(0).getRole());
        Assertions.assertEquals("q1", result.get(0).getContent());
        Assertions.assertEquals("assistant", result.get(1).getRole());
        Assertions.assertEquals("a1", result.get(1).getContent());
        Assertions.assertEquals("user", result.get(2).getRole());
        Assertions.assertEquals("q2", result.get(2).getContent());
        Assertions.assertEquals("assistant", result.get(3).getRole());
        Assertions.assertEquals("a2", result.get(3).getContent());
    }

    @Test
    void load_shouldReturnEmptyForNullSession() {
        Assertions.assertTrue(newLoader().load(null, 3, 4000).isEmpty());
    }

    @Test
    void load_shouldReturnEmptyForEmptySession() {
        when(chatMessageMapper.selectList(any())).thenReturn(new ArrayList<>());
        Assertions.assertTrue(newLoader().load(7L, 3, 4000).isEmpty());
    }

    @Test
    void load_shouldDropOldestWhenExceedingMaxChars() {
        // 每条 5 字符，4 条共 20；maxChars=8 → 只能容纳最新 1 条（保留最新，从最旧丢弃）。
        List<ChatMessageEntity> stored = List.of(
                msg(1, "USER", "aaaaa"),
                msg(2, "ASSISTANT", "bbbbb"),
                msg(3, "USER", "ccccc"),
                msg(4, "ASSISTANT", "ddddd")
        );
        when(chatMessageMapper.selectList(any())).thenReturn(new ArrayList<>(stored));

        List<LlmMessage> result = newLoader().load(7L, 3, 8);

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("ddddd", result.get(0).getContent());
        Assertions.assertEquals("assistant", result.get(0).getRole());
    }

    @Test
    void load_shouldTakeMostRecentTurnsWhenExceedingMaxTurns() {
        // 6 条消息，maxTurns=2 → 只取最近 4 条（旧→新）。
        List<ChatMessageEntity> stored = List.of(
                msg(1, "USER", "q1"),
                msg(2, "ASSISTANT", "a1"),
                msg(3, "USER", "q2"),
                msg(4, "ASSISTANT", "a2"),
                msg(5, "USER", "q3"),
                msg(6, "ASSISTANT", "a3")
        );
        when(chatMessageMapper.selectList(any())).thenReturn(new ArrayList<>(stored));

        List<LlmMessage> result = newLoader().load(7L, 2, 4000);

        Assertions.assertEquals(4, result.size());
        Assertions.assertEquals("q2", result.get(0).getContent());
        Assertions.assertEquals("a3", result.get(3).getContent());
    }
}
