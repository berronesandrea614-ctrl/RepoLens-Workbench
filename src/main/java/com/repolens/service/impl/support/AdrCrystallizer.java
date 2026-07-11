package com.repolens.service.impl.support;

import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ADR 结晶器（Task 2）：把捕获的 AI 意图（approach + plan steps + 改动文件）通过 LLM 单发
 * 结晶成 MADR 草稿（Title/Context/Decision/Consequences/Drivers/Options）。
 *
 * <p>设计要点（对齐 {@link RequirementExtractor}）：
 * 1. 轻量单发 LLM 调用，temperature=0 追求稳定输出；
 * 2. 严格的输出契约（TITLE/CONTEXT/DECISION/CONSEQUENCES/DRIVERS/OPTIONS 带标签行），
 *    多行 CONTEXT/DECISION/CONSEQUENCES 支持：文本延续到下一个已知标签前；
 * 3. 失败安全——response==null、输出不可解析（无 DECISION）、任何异常
 *    均降级为确定性模板骨架（degraded=true），绝不抛出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdrCrystallizer {

    private static final String[] KNOWN_LABELS = {"TITLE", "CONTEXT", "DECISION", "CONSEQUENCES", "DRIVERS", "OPTIONS"};

    private static final String SYSTEM_PROMPT = """
            You are an Architecture Decision Record (ADR) writer following the MADR format.
            Given the engineering context below, write an ADR draft using EXACTLY these labeled lines:
            TITLE: <concise decision title, one line>
            CONTEXT: <what forces and constraints drove this decision>
            DECISION: <the chosen approach>
            CONSEQUENCES: <trade-offs, benefits, and risks>
            DRIVERS: <driver1> | <driver2> | ...
            OPTIONS: <optionA> | <optionB> | ...
            Rules: output only these labeled lines; multi-line values are allowed within a section \
            (continue until the next label); if you cannot identify drivers or options, omit those lines.""";

    private final LlmClient llmClient;
    private final LlmRuntimeConfig llmRuntimeConfig;

    /** Input gathered by the service (no DB access here). */
    public record CrystallizeInput(
            String approach,          // requirement.approach (may be null/blank)
            List<StepNote> steps,     // from plan_json steps (may be empty)
            List<String> changedFiles // touched file paths (may be empty)
    ) {}

    public record StepNote(String title, String why, String insight) {}

    /** Result. degraded=true when LLM unavailable/unparseable (template skeleton used). */
    public record AdrDraft(
            String title, String context, String decision, String consequences,
            List<String> drivers, List<String> options, boolean degraded
    ) {}

    /**
     * 结晶一条 ADR 草稿。LLM 不可用或输出不可解析时降级为确定性模板骨架（degraded=true），绝不抛出。
     */
    public AdrDraft crystallize(CrystallizeInput input) {
        try {
            String userPrompt = buildUserPrompt(input);
            LlmResponse response = llmClient.generate(LlmRequest.builder()
                    .modelName(llmRuntimeConfig.getModelName())
                    .systemPrompt(SYSTEM_PROMPT)
                    .userPrompt(userPrompt)
                    .temperature(0.0d)
                    .timeoutMs(llmRuntimeConfig.getTimeoutMs())
                    .build());
            if (response == null) {
                log.debug("AdrCrystallizer: LLM returned null, using template fallback");
                return buildTemplate(input);
            }
            AdrDraft parsed = parse(response.getContent());
            if (parsed == null) {
                log.debug("AdrCrystallizer: LLM output unparseable (no DECISION found), using template fallback");
                return buildTemplate(input);
            }
            return parsed;
        } catch (Exception ex) {
            log.warn("AdrCrystallizer LLM call failed, using template fallback. err={}", ex.getMessage());
            return buildTemplate(input);
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private String buildUserPrompt(CrystallizeInput input) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(input.approach())) {
            sb.append("Approach: ").append(input.approach()).append("\n\n");
        }
        List<StepNote> steps = input.steps();
        if (steps != null && !steps.isEmpty()) {
            sb.append("Plan steps:\n");
            for (StepNote step : steps) {
                sb.append("- ").append(step.title() != null ? step.title() : "(untitled)");
                if (StringUtils.hasText(step.why())) {
                    sb.append(" (why: ").append(step.why()).append(")");
                }
                if (StringUtils.hasText(step.insight())) {
                    sb.append(" [insight: ").append(step.insight()).append("]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        List<String> changedFiles = input.changedFiles();
        if (changedFiles != null && !changedFiles.isEmpty()) {
            sb.append("Changed files:\n");
            for (String f : changedFiles) {
                sb.append("- ").append(f).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 解析 LLM 输出。契约：每个已知标签独占行头（"LABEL: value"），value 延续到下一标签前。
     * DECISION 为必需字段；缺失则返回 null（触发模板降级）。
     */
    private AdrDraft parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        Map<String, String> sections = new HashMap<>();
        String currentLabel = null;
        StringBuilder currentValue = new StringBuilder();

        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            String foundLabel = detectLabel(trimmed);
            if (foundLabel != null) {
                // Flush previous section
                if (currentLabel != null) {
                    sections.put(currentLabel, currentValue.toString().trim());
                }
                currentLabel = foundLabel;
                currentValue = new StringBuilder(trimmed.substring(foundLabel.length() + 1).trim());
            } else if (currentLabel != null) {
                // Continuation of current section (multi-line support)
                if (!currentValue.isEmpty()) {
                    currentValue.append("\n");
                }
                currentValue.append(trimmed);
            }
        }
        // Flush last section
        if (currentLabel != null) {
            sections.put(currentLabel, currentValue.toString().trim());
        }

        // DECISION is required; its absence signals unparseable output
        if (!sections.containsKey("DECISION")) {
            return null;
        }

        String title = sections.getOrDefault("TITLE", "Architecture Decision");
        String context = sections.getOrDefault("CONTEXT", "(no captured intent)");
        String decision = sections.get("DECISION");
        String consequences = sections.getOrDefault("CONSEQUENCES", "(consequences not captured — please review)");
        List<String> drivers = splitPipe(sections.get("DRIVERS"));
        List<String> options = splitPipe(sections.get("OPTIONS"));

        return new AdrDraft(title, context, decision, consequences, drivers, options, false);
    }

    /** 如果行以已知标签开头（"LABEL:"），返回标签名；否则返回 null。 */
    private String detectLabel(String line) {
        for (String label : KNOWN_LABELS) {
            if (line.startsWith(label + ":")) {
                return label;
            }
        }
        return null;
    }

    /** 按 | 分割，trim 每段，过滤空段。 */
    private List<String> splitPipe(String value) {
        if (!StringUtils.hasText(value)) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split("\\|"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    /**
     * 确定性模板骨架（降级路径）。绝不抛出。
     *
     * <ul>
     *   <li>title = approach 截断 ≤80 字符，否则 "Architecture Decision"</li>
     *   <li>context = approach（或占位符）+ "Touched files: ..." 当 changedFiles 非空</li>
     *   <li>decision = approach（或占位符）</li>
     *   <li>consequences = 固定占位符</li>
     *   <li>drivers = 步骤标题列表，options = 空</li>
     * </ul>
     */
    AdrDraft buildTemplate(CrystallizeInput input) {
        String approach = (input != null) ? input.approach() : null;
        List<StepNote> steps = (input != null && input.steps() != null) ? input.steps() : List.of();
        List<String> changedFiles = (input != null && input.changedFiles() != null) ? input.changedFiles() : List.of();

        // title
        String title;
        if (StringUtils.hasText(approach)) {
            title = approach.length() <= 80 ? approach : approach.substring(0, 80);
        } else {
            title = "Architecture Decision";
        }

        // context
        String context;
        if (StringUtils.hasText(approach)) {
            context = approach;
        } else {
            context = "(no captured intent)";
        }
        if (!changedFiles.isEmpty()) {
            context = context + "\nTouched files: " + String.join(", ", changedFiles);
        }

        // decision
        String decision = StringUtils.hasText(approach) ? approach : "(decision not captured)";

        // consequences
        String consequences = "(consequences not captured — please review)";

        // drivers from step titles; options always empty in template
        List<String> drivers = steps.stream()
                .map(StepNote::title)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        return new AdrDraft(title, context, decision, consequences, drivers, new ArrayList<>(), true);
    }
}
