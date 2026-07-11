package com.repolens.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.ChatMessageEntity;
import com.repolens.domain.entity.ChatSessionEntity;
import com.repolens.domain.vo.ChatMessageVO;
import com.repolens.domain.vo.ChatSessionVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊天会话历史单测（mock mapper + PermissionService，无真实 DB）。验证：
 * (a) listSessions 返回 VO，含 messageCount 与最新消息预览，最新在前；
 * (b) listMessages 按 id 升序返回并解析 referencesJson；
 * (c) referencesJson 解析失败回落空列表；
 * (d) delete 校验归属后删除消息与会话；归属/存在性异常。
 */
@ExtendWith(MockitoExtension.class)
class ChatSessionServiceImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 3, 12, 0, 0);
    private static final Long USER = 1L;
    private static final Long REPO = 2L;

    @Mock
    private com.repolens.mapper.ChatSessionMapper chatSessionMapper;
    @Mock
    private com.repolens.mapper.ChatMessageMapper chatMessageMapper;
    @Mock
    private com.repolens.security.PermissionService permissionService;
    @Mock
    private com.repolens.mapper.LlmCallLogMapper llmCallLogMapper;
    @Mock
    private com.repolens.mapper.ToolCallLogMapper toolCallLogMapper;
    @Mock
    private com.repolens.mapper.FileChangeLogMapper fileChangeLogMapper;

    // 用真实的 ObjectMapper 做引用解析。
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatSessionServiceImpl newService() {
        return new ChatSessionServiceImpl(chatSessionMapper, chatMessageMapper, permissionService, objectMapper,
                llmCallLogMapper, toolCallLogMapper, fileChangeLogMapper);
    }

    private ChatSessionEntity session(long id, Long userId, Long repoId) {
        ChatSessionEntity s = new ChatSessionEntity();
        s.setId(id);
        s.setUserId(userId);
        s.setRepoId(repoId);
        s.setTitle("title-" + id);
        s.setCreatedAt(NOW);
        return s;
    }

    private ChatMessageEntity message(long id, long sessionId, String role, String content, String refsJson) {
        ChatMessageEntity m = new ChatMessageEntity();
        m.setId(id);
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setReferencesJson(refsJson);
        return m;
    }

    @Test
    void listSessions_shouldReturnVOsWithMessageCountAndPreviewNewestFirst() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(chatSessionMapper.selectList(any())).thenReturn(List.of(
                session(10, USER, REPO), session(11, USER, REPO)));
        // 排序后最新在前：先处理 s11（最新消息 id=21 -> preview "hello 11"），再 s10（消息 id=20 -> "hi 10"）。
        when(chatMessageMapper.selectList(any())).thenReturn(
                List.of(message(21, 11, "ASSISTANT", "hello 11", null),
                        message(20, 11, "USER", "q 11", null)),
                List.of(message(20, 10, "USER", "hi 10", null)));

        List<ChatSessionVO> result = newService().listSessions(USER, REPO);

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals(11L, result.get(0).getId());
        Assertions.assertEquals(2, result.get(0).getMessageCount());
        Assertions.assertEquals("hello 11", result.get(0).getLastMessagePreview());
        Assertions.assertEquals(10L, result.get(1).getId());
        Assertions.assertEquals(1, result.get(1).getMessageCount());
        Assertions.assertEquals("hi 10", result.get(1).getLastMessagePreview());
    }

    @Test
    void listSessions_shouldReturnEmptyPreviewWhenNoMessages() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(chatSessionMapper.selectList(any())).thenReturn(List.of(session(10, USER, REPO)));
        when(chatMessageMapper.selectList(any())).thenReturn(List.of());

        List<ChatSessionVO> result = newService().listSessions(USER, REPO);

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals(0, result.get(0).getMessageCount());
        Assertions.assertEquals("", result.get(0).getLastMessagePreview());
    }

    @Test
    void listMessages_shouldReturnParsedMessagesInOrder() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(chatSessionMapper.selectById(5L)).thenReturn(session(5, USER, REPO));
        String refsJson = "[{\"filePath\":\"A.java\",\"startLine\":10,\"endLine\":20,\"className\":\"A\"}]";
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(
                message(1, 5, "USER", "question", null),
                message(2, 5, "ASSISTANT", "answer", refsJson)));

        List<ChatMessageVO> result = newService().listMessages(USER, REPO, 5L);

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("USER", result.get(0).getRole());
        Assertions.assertTrue(result.get(0).getReferences().isEmpty());
        Assertions.assertEquals("ASSISTANT", result.get(1).getRole());
        Assertions.assertEquals(1, result.get(1).getReferences().size());
        Assertions.assertEquals("A.java", result.get(1).getReferences().get(0).getFilePath());
        Assertions.assertEquals(10, result.get(1).getReferences().get(0).getStartLine());
    }

    @Test
    void listMessages_shouldReturnEmptyReferencesWhenParseFails() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(chatSessionMapper.selectById(5L)).thenReturn(session(5, USER, REPO));
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(
                message(1, 5, "ASSISTANT", "answer", "{not valid json")));

        List<ChatMessageVO> result = newService().listMessages(USER, REPO, 5L);

        Assertions.assertEquals(1, result.size());
        Assertions.assertNotNull(result.get(0).getReferences());
        Assertions.assertTrue(result.get(0).getReferences().isEmpty());
    }

    @Test
    void listMessages_shouldThrowForbiddenWhenSessionNotOwned() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(chatSessionMapper.selectById(5L)).thenReturn(session(5, USER, 99L));

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> newService().listMessages(USER, REPO, 5L));
        Assertions.assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void listMessages_shouldThrowNotFoundWhenSessionMissing() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(chatSessionMapper.selectById(5L)).thenReturn(null);

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> newService().listMessages(USER, REPO, 5L));
        Assertions.assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void deleteSession_shouldRemoveMessagesAndSessionWhenOwned() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(chatSessionMapper.selectById(5L)).thenReturn(session(5, USER, REPO));

        newService().deleteSession(USER, REPO, 5L);

        verify(chatMessageMapper, times(1)).delete(any());
        verify(chatSessionMapper, times(1)).deleteById(5L);
    }

    @Test
    void deleteSession_shouldAlsoCascadeDeleteLogRowsForSession() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(chatSessionMapper.selectById(5L)).thenReturn(session(5, USER, REPO));

        newService().deleteSession(USER, REPO, 5L);

        // 消息 + 会话 + 三类日志（llm/tool/file_change）都应被删除，避免会话删除后留下悬挂行。
        verify(llmCallLogMapper, times(1)).delete(any());
        verify(toolCallLogMapper, times(1)).delete(any());
        verify(fileChangeLogMapper, times(1)).delete(any());
        verify(chatMessageMapper, times(1)).delete(any());
        verify(chatSessionMapper, times(1)).deleteById(5L);
    }

    @Test
    void deleteSession_shouldThrowForbiddenWhenNoRepoPermission() {
        lenient().when(chatSessionMapper.selectById(5L)).thenReturn(session(5, USER, REPO));
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(false);

        Assertions.assertThrows(BizException.class, () -> newService().deleteSession(USER, REPO, 5L));
        verify(chatSessionMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void renameSession_shouldCapTitleAt255AndUpdate() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(chatSessionMapper.selectById(5L)).thenReturn(session(5, USER, REPO));
        String longTitle = "x".repeat(300);

        newService().renameSession(USER, REPO, 5L, longTitle);

        ArgumentCaptor<ChatSessionEntity> captor = ArgumentCaptor.forClass(ChatSessionEntity.class);
        verify(chatSessionMapper, times(1)).updateById(captor.capture());
        Assertions.assertEquals(255, captor.getValue().getTitle().length());
    }
}
