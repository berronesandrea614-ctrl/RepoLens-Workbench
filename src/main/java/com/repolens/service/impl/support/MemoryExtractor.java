package com.repolens.service.impl.support;

import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

/**
 * 记忆抽取器：从一轮问答中提炼【一条】值得跨会话长期记住的事实/用户关注点。
 *
 * 设计要点：
 * 1. 轻量单发 LLM 调用（无 tools / 无 messages），temperature=0 追求稳定输出；
 * 2. 严格的输出契约（NONE 或 "记忆: ... | 关键词: ..."），便于机械解析；
 * 3. 失败安全——NONE / 解析失败 / 任何异常都返回 empty，绝不抛出，不拖垮主回答流程。
 */
@Slf4j
@Component
public class MemoryExtractor {

    /** 记忆内容长度上限，避免把整段答案塞进长期记忆。 */
    private static final int MAX_CONTENT_CHARS = 300;

    private static final String SYSTEM_PROMPT = """
            你是记忆抽取器。请从这轮问答中提炼【一条】值得跨会话长期记住的、
            关于该代码仓库事实或用户持续关注点的简短中文陈述。
            若没有值得长期记住的内容，只回复 NONE。
            类型(TYPE)必须是以下之一：FACT（事实）、PREFERENCE（用户偏好）、DECISION（决策）、CONSTRAINT（约束）。
            重要性(IMPORTANCE)必须是 1-5 的整数（5 最重要）。
            输出必须严格为下面两种形式之一，不要输出任何多余内容：
            NONE
            或
            记忆: <一句话中文陈述> | 关键词: <关键词1,关键词2,...> | 类型: <TYPE> | 重要性: <1-5>
            """;

    private final LlmClient llmClient;
    private final LlmRuntimeConfig llmRuntimeConfig;

    public MemoryExtractor(LlmClient llmClient, LlmRuntimeConfig llmRuntimeConfig) {
        this.llmClient = llmClient;
        this.llmRuntimeConfig = llmRuntimeConfig;
    }

    /**
     * 抽取一条长期记忆。无值得记住的内容、解析失败或任何异常均返回 empty（不抛出）。
     */
    public Optional<MemoryNote> extract(String question, String answer) {
        if (!StringUtils.hasText(question) || !StringUtils.hasText(answer)) {
            return Optional.empty();
        }
        try {
            String userPrompt = "问题:\n" + question + "\n\n回答:\n" + answer;
            LlmResponse response = llmClient.generate(LlmRequest.builder()
                    .modelName(llmRuntimeConfig.getModelName())
                    .systemPrompt(SYSTEM_PROMPT)
                    .userPrompt(userPrompt)
                    .temperature(0.0d)
                    .timeoutMs(llmRuntimeConfig.getTimeoutMs())
                    .build());
            if (response == null) {
                return Optional.empty();
            }
            return parse(response.getContent());
        } catch (Exception ex) {
            // 记忆抽取绝不影响已生成的回答：任何异常都吞掉并跳过。
            log.warn("memory extraction failed, skip. err={}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析 LLM 输出。契约：NONE 或
     * 记忆: content | 关键词: kw1,kw2,... | 类型: TYPE | 重要性: N
     * 缺少记忆标记、内容为空视为解析失败，返回 empty。
     * 类型/重要性解析失败时回退为默认值（FACT / 3），不影响整体抽取。
     */
    private Optional<MemoryNote> parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Optional.empty();
        }
        String text = raw.trim();
        if ("NONE".equalsIgnoreCase(text)) {
            return Optional.empty();
        }
        if (!text.contains("记忆")) {
            return Optional.empty();
        }

        // 用竖线切成各段（记忆 | 关键词 | 类型 | 重要性）。
        String[] parts = text.split("\\|");
        String content = parts[0].replaceFirst("^.*?记忆\\s*[:：]?\\s*", "").trim();
        // 只取首行，避免模型多输出换行内容混入。
        int nl = content.indexOf('\n');
        if (nl >= 0) {
            content = content.substring(0, nl).trim();
        }
        if (content.isEmpty()) {
            return Optional.empty();
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            content = content.substring(0, MAX_CONTENT_CHARS);
        }

        String keywords = "";
        String memoryType = "FACT";
        int importance = 3;

        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i].trim();
            if (seg.contains("关键词")) {
                keywords = seg.replaceFirst("^.*?关键词\\s*[:：]?\\s*", "").trim();
                int knl = keywords.indexOf('\n');
                if (knl >= 0) {
                    keywords = keywords.substring(0, knl).trim();
                }
            } else if (seg.contains("类型")) {
                String typeStr = seg.replaceFirst("^.*?类型\\s*[:：]?\\s*", "").trim();
                int tnl = typeStr.indexOf('\n');
                if (tnl >= 0) {
                    typeStr = typeStr.substring(0, tnl).trim();
                }
                String upperType = typeStr.toUpperCase(Locale.ROOT);
                if (upperType.equals("FACT") || upperType.equals("PREFERENCE")
                        || upperType.equals("DECISION") || upperType.equals("CONSTRAINT")) {
                    memoryType = upperType;
                }
            } else if (seg.contains("重要性")) {
                String impStr = seg.replaceFirst("^.*?重要性\\s*[:：]?\\s*", "").trim();
                int inl = impStr.indexOf('\n');
                if (inl >= 0) {
                    impStr = impStr.substring(0, inl).trim();
                }
                try {
                    int parsed = Integer.parseInt(impStr.replaceAll("[^0-9]", ""));
                    if (parsed >= 1 && parsed <= 5) {
                        importance = parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // 回退为默认值 3
                }
            }
        }
        return Optional.of(new MemoryNote(content, keywords, memoryType, importance));
    }

    /**
     * 一条待落库的长期记忆：content 是可复用的中文陈述，keywords 是逗号分隔的检索关键词，
     * memoryType 是类型（FACT/PREFERENCE/DECISION/CONSTRAINT），importance 是重要程度 1-5。
     */
    public record MemoryNote(String content, String keywords, String memoryType, int importance) {
    }
}
