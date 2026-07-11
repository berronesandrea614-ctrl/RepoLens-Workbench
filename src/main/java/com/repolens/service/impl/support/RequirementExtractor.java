package com.repolens.service.impl.support;

import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 需求归纳器：判断一轮问答是否体现【一个有实质代码意图】的需求，是则提炼出一条
 * 「标题 + 一句话摘要 + 整体思路」，供上层沉淀为 requirement 条目。
 *
 * <p>设计要点（对齐 {@link MemoryExtractor}）：
 * 1. 轻量单发 LLM 调用（无 tools / 无 messages），temperature=0 追求稳定输出；
 * 2. 严格的输出契约（NONE 或 "标题: ... | 摘要: ... | 思路: ..."），便于机械解析；
 *    思路（approach）字段可选，解析失败或缺失时 ReqNote.approach 为 null；
 * 3. 失败安全——NONE / 解析失败 / 任何异常都返回 empty，绝不抛出，不拖垮主回答流程。
 */
@Slf4j
@Component
public class RequirementExtractor {

    /** 标题长度上限，与 requirement.title 列约束对齐。 */
    private static final int MAX_TITLE_CHARS = 255;

    /** 摘要长度上限，与 requirement.summary 列约束对齐。 */
    private static final int MAX_SUMMARY_CHARS = 1000;

    /** 思路长度上限，与 requirement.approach 列约束对齐。 */
    private static final int MAX_APPROACH_CHARS = 500;

    private static final String SYSTEM_PROMPT = """
            你是需求归纳器。判断这轮问答是否体现一个有实质代码意图的需求（理解/新增/修改某功能、排查某流程），\
            是则用简短中文输出一行 标题: <标题> | 摘要: <一句话摘要> | 思路: <AI整体思路一句话>；只是闲聊/无代码意图则只回 NONE。""";

    private static final String EXTERNAL_SYSTEM_PROMPT = """
            你是需求归纳器。分析这批被 Claude Code 修改的文件，归纳出一条代码变更意图（做了什么、为什么）。\
            输出格式：标题: <标题> | 摘要: <一句话摘要> | 思路: <改动思路一句话>；\
            无法判断意图则只回 NONE。""";

    private final LlmClient llmClient;
    private final LlmRuntimeConfig llmRuntimeConfig;

    public RequirementExtractor(LlmClient llmClient, LlmRuntimeConfig llmRuntimeConfig) {
        this.llmClient = llmClient;
        this.llmRuntimeConfig = llmRuntimeConfig;
    }

    /**
     * 归纳一条需求。无实质代码意图、解析失败或任何异常均返回 empty（不抛出）。
     */
    public Optional<ReqNote> extract(String question, String answer) {
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
            // 需求归纳绝不影响已生成的回答：任何异常都吞掉并跳过。
            log.warn("requirement extraction failed, skip. err={}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 归纳外部改动（Claude Code 修改的文件）为一条需求。
     *
     * <p>与 {@link #extract} 不同，输入不是问答对，而是文件路径列表与合并的文件内容摘要。
     * 失败安全——任何异常或 NONE 响应均返回 empty（不抛出）。
     *
     * @param changedFilePaths 本次 Claude 改动的文件路径列表（用于提示词）
     * @param combinedContent  各文件内容合并字符串（超长时应由调用方截断）
     */
    public Optional<ReqNote> extractFromExternalChanges(List<String> changedFilePaths,
                                                         String combinedContent) {
        if (changedFilePaths == null || changedFilePaths.isEmpty()) {
            return Optional.empty();
        }
        try {
            String pathList = String.join("\n", changedFilePaths);
            String userPrompt = "修改的文件:\n" + pathList
                    + "\n\n文件内容摘要:\n"
                    + (StringUtils.hasText(combinedContent) ? combinedContent : "(内容读取失败或为空)");
            LlmResponse response = llmClient.generate(LlmRequest.builder()
                    .modelName(llmRuntimeConfig.getModelName())
                    .systemPrompt(EXTERNAL_SYSTEM_PROMPT)
                    .userPrompt(userPrompt)
                    .temperature(0.0d)
                    .timeoutMs(llmRuntimeConfig.getTimeoutMs())
                    .build());
            if (response == null) {
                return Optional.empty();
            }
            return parse(response.getContent());
        } catch (Exception ex) {
            log.warn("external-changes requirement extraction failed, skip. err={}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析 LLM 输出。契约：NONE 或 "标题: &lt;标题&gt; | 摘要: &lt;摘要&gt; | 思路: &lt;思路&gt;"。
     * 缺少标题/摘要标记、任一为空视为解析失败，返回 empty。思路（approach）缺失时设为 null。
     */
    private Optional<ReqNote> parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Optional.empty();
        }
        String text = raw.trim();
        if ("NONE".equalsIgnoreCase(text)) {
            return Optional.empty();
        }
        if (!text.contains("标题") || !text.contains("摘要")) {
            return Optional.empty();
        }

        // 按竖线分段，支持 2 段（旧格式：标题|摘要）和 3 段（新格式：标题|摘要|思路）。
        String[] parts = text.split("\\|", 3);
        if (parts.length < 2) {
            return Optional.empty();
        }
        String title = firstLine(parts[0].replaceFirst("^.*?标题\\s*[:：]?\\s*", "").trim());
        String summary = firstLine(parts[1].replaceFirst("^.*?摘要\\s*[:：]?\\s*", "").trim());
        if (title.isEmpty() || summary.isEmpty()) {
            return Optional.empty();
        }
        if (title.length() > MAX_TITLE_CHARS) {
            title = title.substring(0, MAX_TITLE_CHARS);
        }
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS);
        }

        // 思路（approach）可选：仅在第三段存在时解析。
        String approach = null;
        if (parts.length >= 3) {
            String raw3 = parts[2].replaceFirst("^.*?思路\\s*[:：]?\\s*", "").trim();
            String a = firstLine(raw3);
            if (!a.isEmpty()) {
                approach = a.length() > MAX_APPROACH_CHARS ? a.substring(0, MAX_APPROACH_CHARS) : a;
            }
        }
        return Optional.of(new ReqNote(title, summary, approach));
    }

    /** 只取首行，避免模型多输出换行内容混入。 */
    private String firstLine(String value) {
        int nl = value.indexOf('\n');
        return nl >= 0 ? value.substring(0, nl).trim() : value;
    }

    /**
     * 一条待沉淀的需求：title 是简短标题，summary 是一句话摘要，
     * approach 是 AI 整体思路（可 null，表示抽取器未能生成）。
     */
    public record ReqNote(String title, String summary, String approach) {
    }
}
