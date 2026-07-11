package com.repolens.service;

import com.repolens.domain.dto.chat.ChatRequest;
import com.repolens.domain.vo.ChatResponseVO;

public interface ChatService {

    ChatResponseVO ask(Long userId, ChatRequest request);
}
