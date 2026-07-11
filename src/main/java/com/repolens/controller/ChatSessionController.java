package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.dto.chat.RenameSessionRequest;
import com.repolens.domain.vo.ChatMessageVO;
import com.repolens.domain.vo.ChatSessionVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 聊天会话历史接口：列出会话、加载会话消息线程、删除会话、重命名会话。
 * 均以 X-User-Id（默认 1）标识调用者，权限与归属校验下沉在 service 层。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    /** 列出该 (user, repo) 的全部会话，最新在前。 */
    @GetMapping("/{repoId}/sessions")
    public Result<List<ChatSessionVO>> listSessions(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId) {
        return Result.success(chatSessionService.listSessions(userId, repoId));
    }

    /** 加载某会话的完整消息线程（时间升序）。 */
    @GetMapping("/{repoId}/sessions/{sessionId}/messages")
    public Result<List<ChatMessageVO>> listMessages(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("sessionId") Long sessionId) {
        return Result.success(chatSessionService.listMessages(userId, repoId, sessionId));
    }

    /** 删除会话及其全部消息。 */
    @DeleteMapping("/{repoId}/sessions/{sessionId}")
    public Result<Void> deleteSession(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("sessionId") Long sessionId) {
        chatSessionService.deleteSession(userId, repoId, sessionId);
        return Result.success(null);
    }

    /** 重命名会话标题。 */
    @PutMapping("/{repoId}/sessions/{sessionId}/title")
    public Result<Void> renameSession(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("sessionId") Long sessionId,
            @RequestBody RenameSessionRequest request) {
        chatSessionService.renameSession(userId, repoId, sessionId,
                request == null ? null : request.getTitle());
        return Result.success(null);
    }
}
