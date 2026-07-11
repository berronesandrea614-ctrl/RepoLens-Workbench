package com.repolens.service.impl.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.vo.CodeReferenceVO;
import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Plan-and-Execute 的"规划"半：在 agent 主循环启动前，用一次轻量 LLM 调用（无工具）
 * 为复杂问题生成 2-6 个步骤，注入 agent system prompt 以引导 ReAct 少走弯路。
 *
 * <p><b>结构化输出</b>（{@link #planStructured}）：LLM 输出 JSON，解析成功时返回含
 * {@link StructuredPlan} 的 Optional；JSON 解析失败时回退到纯文本（计划文本仍注入 prompt，
 * 但不落结构化库）。两条路径均只发起一次 LLM 调用。
 *
 * <p>全程失败安全：任何异常/空结果都返回 Optional.empty()（绝不抛出），规划失败绝不能拖垮主回答。
 * 规划只是"建议"，注入时明确标注"供参考，可偏离"，模型仍可自主决策。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPlanner {

    /** 规划输出的字符上限：规划只是引导，短即可，避免撑大 system prompt。 */
    private static final int MAX_PLAN_CHARS = 800;

    /** 注入进 prompt 的证据条数上限：只给模型少量线索定位问题，不喂全量。 */
    private static final int MAX_EVIDENCE_HINTS = 6;

    // ---- 旧版（ask 模式）纯文本 system prompt ----
    private static final String SYSTEM_PROMPT_TEXT =
            "你是代码分析规划器。针对下面的问题和已有证据，用 3-5 个简短步骤列出应如何用可用的只读代码工具"
            + "（findMethodCallers/findSymbolByName/getFileContent/searchCodeChunks/findMethodCallees/"
            + "analyzeImpact/findApiByPath）逐步查清，只输出编号步骤，不要作答。";

    // ---- 新版（code 模式）结构化 JSON system prompt ----
    private static final String SYSTEM_PROMPT_JSON =
            "你是代码分析规划器。针对下面的问题和已有证据，生成 2-6 个执行步骤并以 JSON 输出（不要输出任何其他内容）：\n"
            + "{\"approach\":\"一句话整体思路\",\"steps\":[{\"stepId\":\"step-1\",\"title\":\"步骤标题\","
            + "\"why\":\"为什么这么做\",\"declaredFiles\":[\"相对路径或类名\"],"
            + "\"declaredOp\":\"MODIFY\",\"insight\":\"这步的关键点/权衡一句话\"}]}\n"
            + "stepId 用 step-1/step-2/... 编号；declaredOp 为 CREATE/MODIFY/DELETE 之一（如不确定填 MODIFY）；"
            + "declaredFiles 可为空数组。只输出 JSON，不要多余解释。";

    private final LlmClient llmClient;
    private final LlmRuntimeConfig llmRuntimeConfig;
    private final ObjectMapper objectMapper;

    // --------------------------------------------------------
    // 旧版 API（ask 模式使用，保持原有行为）
    // --------------------------------------------------------

    /**
     * 为问题生成一个简短的排查计划（纯文本，ask 模式使用）。
     *
     * @param question 用户问题
     * @param evidence 已有的 RAG 初始证据（可空），仅作为定位线索喂给规划器
     * @return 规划文本（已 trim/截断）；失败、为空或任何异常时返回 null（绝不抛出）
     */
    public String plan(String question, List<CodeReferenceVO> evidence) {
        if (!StringUtils.hasText(question)) {
            return null;
        }
        try {
            String userPrompt = buildUserPrompt(question, evidence);
            LlmResponse resp = llmClient.generate(LlmRequest.builder()
                    .modelName(llmRuntimeConfig.getModelName())
                    .systemPrompt(SYSTEM_PROMPT_TEXT)
                    .userPrompt(userPrompt)
                    .temperature(0.0d)
                    .timeoutMs(llmRuntimeConfig.getTimeoutMs())
                    .build());
            String plan = resp == null ? null : resp.getContent();
            if (!StringUtils.hasText(plan)) {
                return null;
            }
            String trimmed = plan.trim();
            if (trimmed.length() > MAX_PLAN_CHARS) {
                trimmed = trimmed.substring(0, MAX_PLAN_CHARS);
            }
            return trimmed;
        } catch (Exception ex) {
            log.warn("agent planning failed, continue without plan, err={}",
                    ex.getMessage() == null ? "unknown" : ex.getMessage());
            return null;
        }
    }

    // --------------------------------------------------------
    // 新版结构化 API（code 模式使用）
    // --------------------------------------------------------

    /**
     * 为问题生成结构化计划（code 模式使用）。
     *
     * <p>LLM 输出 JSON → 解析成功：返回含完整 {@link StructuredPlan} 的 Optional，
     * plan 中携带 approach / steps / planText（可读文本，供注入 system prompt）。
     * JSON 解析失败：返回 Optional 仍 present，但 approach/steps 为 null，planText 为原始 LLM 输出，
     * 实现"文本仍注入 prompt，但不落结构化库"的降级行为。
     * LLM 不返回内容或抛异常：返回 Optional.empty()（绝不抛出）。
     *
     * @param question 用户问题
     * @param evidence 已有的 RAG 初始证据（可空）
     * @return 结构化计划（空 = 无计划可用）
     */
    public Optional<StructuredPlan> planStructured(String question, List<CodeReferenceVO> evidence) {
        if (!StringUtils.hasText(question)) {
            return Optional.empty();
        }
        try {
            String userPrompt = buildUserPrompt(question, evidence);
            LlmResponse resp = llmClient.generate(LlmRequest.builder()
                    .modelName(llmRuntimeConfig.getModelName())
                    .systemPrompt(SYSTEM_PROMPT_JSON)
                    .userPrompt(userPrompt)
                    .temperature(0.0d)
                    .timeoutMs(llmRuntimeConfig.getTimeoutMs())
                    .build());
            String raw = resp == null ? null : resp.getContent();
            if (!StringUtils.hasText(raw)) {
                return Optional.empty();
            }
            return Optional.of(parseStructured(raw.trim()));
        } catch (Exception ex) {
            log.warn("agent structured planning failed, continue without plan, err={}",
                    ex.getMessage() == null ? "unknown" : ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 尝试将 LLM 原始输出解析为 {@link StructuredPlan}。
     * JSON 解析失败时降级：approach/steps = null，planText = 原始文本（截断后）。
     */
    private StructuredPlan parseStructured(String raw) {
        // 提取 JSON：LLM 可能在前后输出 markdown 代码块，尝试剥离。
        String json = extractJson(raw);
        try {
            PlanJson parsed = objectMapper.readValue(json, PlanJson.class);
            if (!StringUtils.hasText(parsed.approach)) {
                // approach 缺失视为解析失败，降级
                return buildFallbackPlan(raw);
            }
            String planText = buildPlanText(parsed);
            String approach = truncate(parsed.approach, 500);
            return new StructuredPlan(planText, approach, parsed.steps);
        } catch (Exception ex) {
            log.debug("structured plan JSON parse failed, fall back to plain text, err={}", ex.getMessage());
            return buildFallbackPlan(raw);
        }
    }

    /** 构造降级计划（只有 planText，无 approach/steps）。 */
    private StructuredPlan buildFallbackPlan(String raw) {
        String planText = raw.length() > MAX_PLAN_CHARS ? raw.substring(0, MAX_PLAN_CHARS) : raw;
        return new StructuredPlan(planText, null, null);
    }

    /** 将结构化 PlanJson 转为人类可读的 markdown 列表文本，供注入 system prompt。 */
    private String buildPlanText(PlanJson parsed) {
        StringBuilder sb = new StringBuilder();
        sb.append("整体思路：").append(parsed.approach).append("\n");
        if (parsed.steps != null) {
            for (int i = 0; i < parsed.steps.size(); i++) {
                PlanStepJson s = parsed.steps.get(i);
                sb.append(i + 1).append(". ").append(s.title);
                if (StringUtils.hasText(s.why)) {
                    sb.append("（").append(s.why).append("）");
                }
                sb.append("\n");
            }
        }
        String text = sb.toString().trim();
        return text.length() > MAX_PLAN_CHARS ? text.substring(0, MAX_PLAN_CHARS) : text;
    }

    /**
     * 从原始 LLM 输出中提取 JSON 字符串。
     * 处理 LLM 可能在 ```json ... ``` 块中包装输出的情况。
     */
    private String extractJson(String raw) {
        if (raw.startsWith("{")) {
            return raw;
        }
        // 尝试剥离 markdown 代码块
        int start = raw.indexOf("{");
        int end = raw.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 组装规划器 user prompt：问题 + 少量证据文件线索（帮助模型定位切入点）。 */
    private String buildUserPrompt(String question, List<CodeReferenceVO> evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题：\n").append(question).append("\n");
        if (evidence != null && !evidence.isEmpty()) {
            sb.append("\n已有证据（文件线索）：\n");
            int limit = Math.min(MAX_EVIDENCE_HINTS, evidence.size());
            for (int i = 0; i < limit; i++) {
                CodeReferenceVO ref = evidence.get(i);
                if (ref == null || !StringUtils.hasText(ref.getFilePath())) {
                    continue;
                }
                sb.append("- ").append(ref.getFilePath()).append(":")
                        .append(ref.getStartLine()).append("-").append(ref.getEndLine()).append("\n");
            }
        }
        return sb.toString();
    }

    // --------------------------------------------------------
    // 数据类型
    // --------------------------------------------------------

    /**
     * AgentPlanner 的结构化输出：从 JSON 解析而来（code 模式）。
     * planText 始终非 null（有结构时为格式化文本，降级时为原始 LLM 输出）。
     * approach/steps 仅在 JSON 解析成功时非 null。
     */
    public record StructuredPlan(
            String planText,
            String approach,
            List<PlanStepJson> steps
    ) {
        /** 是否解析成功（有结构化数据可落库）。 */
        public boolean hasStructure() {
            return approach != null;
        }
    }

    /** JSON 反序列化中间类：对应 LLM 输出的 steps 数组元素。向后兼容，旧数据缺字段时为 null。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlanStepJson {
        /** 稳定步骤 id，对账/前端寻址用。旧数据无此字段=null，对账时退化为下标。 */
        public String stepId;
        public String title;
        public String why;
        public List<String> declaredFiles;
        public String insight;
        /** 声明操作类型：CREATE/MODIFY/DELETE。旧数据无此字段=null，对账时降级为 MODIFY。 */
        public String declaredOp;
        /** P2 用：本步自我约束（如"不动 SecurityConfig"）。旧数据无此字段=null，P1 忽略。 */
        public List<String> constraints;
    }

    /** JSON 反序列化中间类：对应 LLM 输出的顶层 JSON 对象。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PlanJson {
        public String approach;
        public List<PlanStepJson> steps;
    }
}
