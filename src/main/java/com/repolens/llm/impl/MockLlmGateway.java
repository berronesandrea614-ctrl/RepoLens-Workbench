package com.repolens.llm.impl;

import com.repolens.domain.enums.IntentType;
import com.repolens.llm.LlmGateway;
import org.springframework.stereotype.Component;

@Component
public class MockLlmGateway implements LlmGateway {

    @Override
    public String chat(IntentType intentType, String prompt) {
        return "【Mock LLM】当前为第一阶段骨架实现，已接收意图 " + intentType
                + "。后续将接入真实模型。";
    }
}
