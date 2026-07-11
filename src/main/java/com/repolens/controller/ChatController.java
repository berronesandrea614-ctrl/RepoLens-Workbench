package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.dto.chat.ChatRequest;
import com.repolens.domain.vo.ChatResponseVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public Result<ChatResponseVO> chat(@AuthUserId Long userId,
                                       @Valid @RequestBody ChatRequest request) {
        return Result.success(chatService.ask(userId, request));
    }
}
