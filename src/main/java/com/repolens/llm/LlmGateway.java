package com.repolens.llm;

import com.repolens.domain.enums.IntentType;

public interface LlmGateway {

    String chat(IntentType intentType, String prompt);
}
