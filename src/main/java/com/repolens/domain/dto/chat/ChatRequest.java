package com.repolens.domain.dto.chat;

import com.repolens.domain.enums.IntentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChatRequest {

    @NotNull
    private Long repoId;

    private Long sessionId;

    @NotBlank
    private String question;

    @NotNull
    private IntentType intentType;
}
