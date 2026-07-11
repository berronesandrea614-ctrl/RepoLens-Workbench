package com.repolens.service.impl.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.domain.entity.ChatMessageEntity;
import com.repolens.llm.model.LlmMessage;
import com.repolens.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 短期对话记忆加载器：把某会话最近若干轮的历史消息回喂给 LLM。
 *
 * 策略：
 * - 按 id 升序取该 session 全部消息（demo 规模够），保留最近 maxTurns*2 条（旧→新）；
 * - DB role "USER"→"user"、"ASSISTANT"→"assistant"，未知 role 跳过；
 * - 若历史总字符超 maxChars，从最旧开始丢弃直到不超预算（至少保留最新一条）；
 * - 空/null 会话返回空 list。
 *
 * 注意：调用方须在写入“当前这轮 USER 消息”之前调用本方法，
 * 否则历史会把当前问题也带进去。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationHistoryLoader {

    private final ChatMessageMapper chatMessageMapper;

    public List<LlmMessage> load(Long sessionId, int maxTurns, int maxChars) {
        if (sessionId == null || maxTurns <= 0) {
            return List.of();
        }

        List<ChatMessageEntity> stored = chatMessageMapper.selectList(
                Wrappers.<ChatMessageEntity>lambdaQuery()
                        .eq(ChatMessageEntity::getSessionId, sessionId)
                        .orderByAsc(ChatMessageEntity::getId));
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }

        int limit = maxTurns * 2;
        List<ChatMessageEntity> recent = stored.size() > limit
                ? stored.subList(stored.size() - limit, stored.size())
                : stored;

        List<LlmMessage> messages = new ArrayList<>(recent.size());
        for (ChatMessageEntity entity : recent) {
            String role = mapRole(entity.getRole());
            if (role == null) {
                continue;
            }
            messages.add(LlmMessage.builder()
                    .role(role)
                    .content(entity.getContent())
                    .build());
        }

        // 字符预算：从最旧开始丢弃，直到不超预算；至少保留最新一条，避免历史被清空。
        int total = messages.stream()
                .mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length())
                .sum();
        while (total > maxChars && messages.size() > 1) {
            LlmMessage removed = messages.remove(0);
            total -= removed.getContent() == null ? 0 : removed.getContent().length();
        }

        return messages;
    }

    private String mapRole(String dbRole) {
        if (!StringUtils.hasText(dbRole)) {
            return null;
        }
        if ("USER".equalsIgnoreCase(dbRole)) {
            return "user";
        }
        if ("ASSISTANT".equalsIgnoreCase(dbRole)) {
            return "assistant";
        }
        return null;
    }
}
