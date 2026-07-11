package com.repolens.service.support.context;

import com.repolens.llm.model.LlmMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class Tokenizer {

    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int chineseCount = 0;
        int otherCount = 0;
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) chineseCount++;
            else otherCount++;
        }
        return (int) (chineseCount / 1.7 + otherCount / 4.0) + 1;
    }

    public int estimateMessages(List<LlmMessage> messages) {
        return messages.stream().mapToInt(m -> estimate(m.getContent())).sum();
    }
}
