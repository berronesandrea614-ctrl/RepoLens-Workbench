package com.repolens.service.support.context;

import com.repolens.domain.vo.ContextBudgetVO;
import com.repolens.llm.model.LlmMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ContextBudgetReporter {

    private final Tokenizer tokenizer;

    public ContextBudgetVO report(List<LlmMessage> messages, int windowTokens, List<String> actions) {
        int used = tokenizer.estimateMessages(messages);
        List<ContextBudgetVO.BlockInfo> blocks = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            LlmMessage m = messages.get(i);
            String kind = classifyKind(m);
            int tokens = tokenizer.estimate(m.getContent());
            String state = determineState(m);
            blocks.add(ContextBudgetVO.BlockInfo.builder()
                    .id("block-" + i)
                    .kind(kind)
                    .tokens(tokens)
                    .state(state)
                    .pinned(false)
                    .preview(preview(m))
                    .build());
        }
        return ContextBudgetVO.builder()
                .windowTokens(windowTokens)
                .usedTokens(used)
                .usedPercent((double) used / windowTokens * 100)
                .actions(actions)
                .blocks(blocks)
                .build();
    }

    private String classifyKind(LlmMessage m) {
        return switch (m.getRole()) {
            case "system" -> "SYSTEM";
            case "user" -> "USER_INPUT";
            case "assistant" -> "ASSISTANT";
            case "tool" -> "TOOL_RESULT";
            default -> "OTHER";
        };
    }

    private String determineState(LlmMessage m) {
        String c = m.getContent();
        if (c == null) return "kept";
        if (c.contains("已压缩")) return "compressed";
        if (c.contains("context-blob:")) return "on-disk";
        return "kept";
    }

    private String preview(LlmMessage m) {
        String c = m.getContent();
        if (c == null) return "";
        return c.substring(0, Math.min(100, c.length()));
    }
}
