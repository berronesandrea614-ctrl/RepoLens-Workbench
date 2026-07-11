package com.repolens.service;

import com.repolens.domain.vo.ChatMessageVO;
import com.repolens.domain.vo.ChatSessionVO;

import java.util.List;

/**
 * 聊天会话历史读写：列出会话、加载会话内消息线程、删除会话、重命名会话。
 * 所有操作均以 (userId, repoId) 权限为边界，并校验会话归属。
 */
public interface ChatSessionService {

    /** 列出该 (user, repo) 的全部会话，最新在前（按会话 id 倒序）。 */
    List<ChatSessionVO> listSessions(Long userId, Long repoId);

    /** 加载某会话的完整消息线程，按消息 id 升序（时间顺序）。 */
    List<ChatMessageVO> listMessages(Long userId, Long repoId, Long sessionId);

    /** 删除会话及其全部消息。 */
    void deleteSession(Long userId, Long repoId, Long sessionId);

    /** 重命名会话标题（上限 255 字符）。 */
    void renameSession(Long userId, Long repoId, Long sessionId, String title);
}
