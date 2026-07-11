package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.ChatMessageEntity;
import com.repolens.domain.entity.ChatSessionEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.LlmCallLogEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.domain.vo.ChatMessageVO;
import com.repolens.domain.vo.ChatSessionVO;
import com.repolens.domain.vo.CodeReferenceVO;
import com.repolens.mapper.ChatMessageMapper;
import com.repolens.mapper.ChatSessionMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.LlmCallLogMapper;
import com.repolens.mapper.ToolCallLogMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    /** 最新消息预览的最大长度。 */
    private static final int PREVIEW_MAX = 60;

    /** 会话标题最大长度。 */
    private static final int TITLE_MAX = 255;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;
    private final LlmCallLogMapper llmCallLogMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    private final FileChangeLogMapper fileChangeLogMapper;

    @Override
    public List<ChatSessionVO> listSessions(Long userId, Long repoId) {
        checkPermission(userId, repoId);
        List<ChatSessionEntity> sessions = chatSessionMapper.selectList(
                Wrappers.<ChatSessionEntity>lambdaQuery()
                        .eq(ChatSessionEntity::getUserId, userId)
                        .eq(ChatSessionEntity::getRepoId, repoId));
        return sessions.stream()
                .sorted(Comparator.comparing(ChatSessionEntity::getId,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .map(this::toSessionVO)
                .collect(Collectors.toList());
    }

    @Override
    public List<ChatMessageVO> listMessages(Long userId, Long repoId, Long sessionId) {
        checkPermission(userId, repoId);
        loadOwnedSession(userId, repoId, sessionId);
        List<ChatMessageEntity> messages = chatMessageMapper.selectList(
                Wrappers.<ChatMessageEntity>lambdaQuery()
                        .eq(ChatMessageEntity::getSessionId, sessionId)
                        .orderByAsc(ChatMessageEntity::getId));
        return messages.stream().map(this::toMessageVO).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(Long userId, Long repoId, Long sessionId) {
        checkPermission(userId, repoId);
        ChatSessionEntity session = loadOwnedSession(userId, repoId, sessionId);
        // 先删掉所有以 sessionId 为外键的审计/日志行，避免会话删除后留下悬挂记录。
        llmCallLogMapper.delete(Wrappers.<LlmCallLogEntity>lambdaQuery()
                .eq(LlmCallLogEntity::getSessionId, sessionId));
        toolCallLogMapper.delete(Wrappers.<ToolCallLogEntity>lambdaQuery()
                .eq(ToolCallLogEntity::getSessionId, sessionId));
        fileChangeLogMapper.delete(Wrappers.<FileChangeLogEntity>lambdaQuery()
                .eq(FileChangeLogEntity::getSessionId, sessionId));
        chatMessageMapper.delete(Wrappers.<ChatMessageEntity>lambdaQuery()
                .eq(ChatMessageEntity::getSessionId, sessionId));
        chatSessionMapper.deleteById(session.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void renameSession(Long userId, Long repoId, Long sessionId, String title) {
        checkPermission(userId, repoId);
        ChatSessionEntity session = loadOwnedSession(userId, repoId, sessionId);
        String normalized = title == null ? "" : title.strip();
        if (normalized.length() > TITLE_MAX) {
            normalized = normalized.substring(0, TITLE_MAX);
        }
        session.setTitle(normalized);
        chatSessionMapper.updateById(session);
    }

    /** 会话 VO：messageCount = 该会话消息数；lastMessagePreview = 最新消息内容截断。 */
    private ChatSessionVO toSessionVO(ChatSessionEntity session) {
        List<ChatMessageEntity> messages = chatMessageMapper.selectList(
                Wrappers.<ChatMessageEntity>lambdaQuery()
                        .eq(ChatMessageEntity::getSessionId, session.getId())
                        .orderByDesc(ChatMessageEntity::getId));
        String preview = "";
        if (messages != null && !messages.isEmpty()) {
            preview = truncate(messages.get(0).getContent());
        }
        return ChatSessionVO.builder()
                .id(session.getId())
                .title(session.getTitle())
                .messageCount(messages == null ? 0 : messages.size())
                .lastMessagePreview(preview)
                .createdAt(session.getCreatedAt())
                .build();
    }

    private ChatMessageVO toMessageVO(ChatMessageEntity message) {
        return ChatMessageVO.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(message.getContent())
                .references(parseReferences(message.getReferencesJson()))
                .build();
    }

    /** 失败安全地解析 referencesJson；空/解析失败均回落为空列表。 */
    private List<CodeReferenceVO> parseReferences(String referencesJson) {
        if (!StringUtils.hasText(referencesJson)) {
            return new ArrayList<>();
        }
        try {
            List<CodeReferenceVO> parsed = objectMapper.readValue(
                    referencesJson, new TypeReference<List<CodeReferenceVO>>() {});
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (Exception ex) {
            log.warn("failed to parse referencesJson, fallback to empty, err={}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        String c = content.strip();
        return c.length() <= PREVIEW_MAX ? c : c.substring(0, PREVIEW_MAX);
    }

    private void checkPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
    }

    /** 加载会话并校验归属；不存在 NOT_FOUND，不属于该 (user, repo) FORBIDDEN。 */
    private ChatSessionEntity loadOwnedSession(Long userId, Long repoId, Long sessionId) {
        ChatSessionEntity session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Session not found: " + sessionId);
        }
        if (!userId.equals(session.getUserId()) || !repoId.equals(session.getRepoId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "Session does not belong to repo " + repoId);
        }
        return session;
    }
}
