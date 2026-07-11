package com.repolens.kernel.ask;

import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * askUser 反问工具（Part7 7.9）——让自主 agent 能主动停下来问用户，而不是按臆测硬闯。
 *
 * <p>只读（不碰文件），委托 {@link AskUserPort} 做真正的挂起等待。软依赖：无实现（非流式/纯内核单测）时
 * 直接降级为「按最合理方案继续」的文本，绝不阻塞、绝不抛异常（fail-safe）。
 */
@Component
public class AskUserTool implements KernelTool {

    private final ObjectProvider<AskUserPort> portProvider;

    public AskUserTool(ObjectProvider<AskUserPort> portProvider) {
        this.portProvider = portProvider;
    }

    @Override
    public String name() {
        return "askUser";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition d = new ToolDefinition();
        d.setName("askUser");
        d.setDescription("""
                向用户提一到多个问题并等待其选择/回复，拿到答复后再继续。前端会渲染成可点选的卡片。
                何时用：需要用户在多个合理方案里拍板、或缺关键方向无法继续时。要问就问方向性的大选择。
                何时不用：能自己合理决策的事不要问（别当每步确认开关）；只想汇报进展也别用。
                每个问题尽量给 2-4 个候选选项（options），每个选项含简短 label + 一句 description 解释含义；
                用户也可选"其它"自由填。可一次问多个相关问题（questions 数组），用户能逐个作答。
                单选还是多选看情况：若该问题的答案本就互斥、只能选一个（如"用架构 A 还是 B""要不要分页"），保持默认单选；
                若该问题允许同时选多个（如"关注哪些方面""包含哪些来源/语言"），就设 multiSelect=true，让用户能多选。
                示例：{"questions":[{"header":"研究主题","question":"你想研究 Claude 的哪个方面？","options":[
                  {"label":"模型与能力对比","description":"各型号能力、基准、与 GPT/Gemini 横向对比"},
                  {"label":"API 与定价","description":"价格、速率限制、上下文窗口、tool use"}]}]}
                """);
        d.setParameters(Map.of(
                "type", "object",
                "properties", Map.of(
                        "questions", Map.of(
                                "type", "array",
                                "description", "一到多个问题；每个含 question(问题) + 可选 header(短标题) + "
                                        + "options(候选，各含 label+description) + multiSelect(是否多选)",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "header", Map.of("type", "string", "description", "短标题(≤12字，做切换标签)"),
                                                "question", Map.of("type", "string", "description", "问题正文"),
                                                "multiSelect", Map.of("type", "boolean", "description", "是否可多选"),
                                                "options", Map.of(
                                                        "type", "array",
                                                        "items", Map.of(
                                                                "type", "object",
                                                                "properties", Map.of(
                                                                        "label", Map.of("type", "string"),
                                                                        "description", Map.of("type", "string")),
                                                                "required", List.of("label")))),
                                        "required", List.of("question"))),
                        // 向后兼容：也允许只给一个纯文本 question
                        "question", Map.of("type", "string", "description", "（可选，简单场景）只问一个自由文本问题")),
                "required", List.of()));
        return d;
    }

    @Override
    public String execute(ToolContext ctx, Map<String, Object> args) {
        AskSpec spec = parseSpec(args);
        if (spec == null || spec.questions().isEmpty()) {
            return "askUser 失败：至少要给一个 question。";
        }
        AskUserPort port = portProvider.getIfAvailable();
        if (port == null) {
            return "（当前环境无法向用户提问，请按你判断最合理的方案继续。）";
        }
        try {
            return port.ask(ctx == null ? null : ctx.sessionId(), spec);
        } catch (Exception e) {
            return "（向用户提问失败：" + e.getMessage() + "，请按最合理方案继续。）";
        }
    }

    /** 把 LLM 给的实参解析成 {@link AskSpec}：优先结构化 questions，回退到纯文本 question。 */
    @SuppressWarnings("unchecked")
    private AskSpec parseSpec(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        List<AskSpec.Question> out = new java.util.ArrayList<>();
        Object qs = args.get("questions");
        if (qs instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> qm)) {
                    continue;
                }
                String question = str(qm.get("question"));
                if (question == null || question.isBlank()) {
                    continue;
                }
                String header = str(qm.get("header"));
                boolean multi = Boolean.TRUE.equals(qm.get("multiSelect"))
                        || "true".equalsIgnoreCase(str(qm.get("multiSelect")));
                List<AskSpec.Option> opts = new java.util.ArrayList<>();
                Object os = qm.get("options");
                if (os instanceof List<?> ol) {
                    for (Object o : ol) {
                        if (o instanceof Map<?, ?> om) {
                            String label = str(om.get("label"));
                            if (label != null && !label.isBlank()) {
                                opts.add(new AskSpec.Option(label.trim(), str(om.get("description"))));
                            }
                        } else if (o != null) {
                            opts.add(new AskSpec.Option(o.toString().trim(), null));
                        }
                    }
                }
                out.add(new AskSpec.Question(header, question.trim(), multi, opts));
            }
        }
        if (out.isEmpty()) {
            String q = str(args.get("question"));
            if (q != null && !q.isBlank()) {
                out.add(new AskSpec.Question(null, q.trim(), false, List.of()));
            }
        }
        return out.isEmpty() ? null : new AskSpec(out);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
