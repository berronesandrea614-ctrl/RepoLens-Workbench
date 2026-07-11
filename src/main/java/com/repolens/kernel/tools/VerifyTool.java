package com.repolens.kernel.tools;

import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.kernel.verify.Failure;
import com.repolens.kernel.verify.VerificationOutcome;
import com.repolens.kernel.verify.VerificationRunner;
import com.repolens.llm.model.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 验证工具：把 M1 的 {@link VerificationRunner} 接入内核工具注册表，让 agent 能在主循环里
 * <b>自主触发</b>「改→编译/测试→读真错→自愈」的真实行为门（此前 loop 里缺这一环，
 * agent 无法自证改动是否真的通过）。
 *
 * <p>在<b>影子工作区</b>真跑构建/测试（断网、结果落 {@code rk_verification_run} 带 shadow_id 自证），
 * 返回结构化失败（文件/行号/函数级上下文）供模型精准自愈——而非模型口头声称"已通过"。
 *
 * <p>{@code readOnly=false}：验证在影子区跑真实构建（写 target/ 等），必须串行调度，
 * 避免与写工具或另一次验证并发在同一影子区互相踩踏。
 */
@Component
public class VerifyTool implements KernelTool {

    /** 结构化失败最多回传条数（防超长 observation 挤爆上下文）。 */
    private static final int MAX_FAILURES = 20;
    /** 单条失败的函数级上下文截断长度。 */
    private static final int CONTEXT_CAP = 1200;

    private final VerificationRunner runner;

    public VerifyTool(VerificationRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "runVerification";
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition d = new ToolDefinition();
        d.setName("runVerification");
        d.setDescription(
                "在影子工作区真跑构建或测试，验证你的改动是否真的通过。"
                + "做什么：kind=build 跑编译、kind=test 跑测试；断网执行，返回退出码 + 结构化失败"
                + "（文件/行号/出错方法的上下文）。"
                + "何时用：写完/改完代码后，用它确认改动真的编译/测试通过——这是你声称『已修复/已完成』前的硬门槛，"
                + "不要凭空声称通过。失败时读 failures 里的文件行号与上下文，用 editFileContent 精准修正，再次验证直到 passed。"
                + "何时不用：还没做任何改动、或只是读代码理解时不需要；它跑的是真实构建，较慢，别无意义地反复调用。"
                + "示例：{\"kind\":\"build\"} 或 {\"kind\":\"test\"}");
        d.setParameters(Map.of(
                "type", "object",
                "properties", Map.of(
                        "kind", Map.of(
                                "type", "string",
                                "enum", List.of("build", "test"),
                                "description", "build=编译，test=跑测试（默认 build）")),
                "required", List.of()));
        return d;
    }

    @Override
    public String execute(ToolContext ctx, Map<String, Object> args) {
        if (ctx.shadow() == null || ctx.shadow().root() == null) {
            return "runVerification 失败：当前 session 没有活跃影子工作区，无法验证（请先产生改动）。";
        }
        VerificationRunner.Kind kind = parseKind(str(args, "kind"));

        VerificationOutcome outcome = runner.verify(
                ctx.repoId(), ctx.sessionId(), ctx.runId(),
                ctx.shadow().id(), ctx.shadow().root(), kind);

        return format(outcome);
    }

    private String format(VerificationOutcome o) {
        StringBuilder sb = new StringBuilder();
        sb.append(o.passed() ? "✅ 验证通过" : "❌ 验证未通过")
                .append("（kind=").append(o.kind())
                .append(", buildTarget=").append(o.buildTarget())
                .append(", exitCode=").append(o.exitCode())
                .append(", networkIsolated=").append(o.networkIsolated())
                .append("）\n");

        if (o.passed()) {
            return sb.toString().trim();
        }

        List<Failure> failures = o.failures();
        if (failures != null && !failures.isEmpty()) {
            int shown = Math.min(failures.size(), MAX_FAILURES);
            sb.append("发现 ").append(failures.size()).append(" 处失败")
                    .append(failures.size() > shown ? "（仅列前 " + shown + " 条）" : "").append("：\n");
            for (int i = 0; i < shown; i++) {
                Failure f = failures.get(i);
                sb.append(i + 1).append(". ");
                if (f.file() != null && !f.file().isEmpty()) {
                    sb.append(f.file());
                    if (f.line() > 0) {
                        sb.append(':').append(f.line());
                    }
                    sb.append("  ");
                }
                if (f.symbol() != null && !f.symbol().isEmpty()) {
                    sb.append('[').append(f.symbol()).append("] ");
                }
                sb.append(f.message() == null ? "" : f.message()).append('\n');
                if (f.context() != null && !f.context().isEmpty()) {
                    sb.append("   上下文：\n").append(cap(f.context(), CONTEXT_CAP)).append('\n');
                }
            }
            sb.append("请按 failures 的文件行号与上下文用 editFileContent 修正后再次 runVerification。");
        } else {
            // 无结构化失败（解析器未命中）——回传输出尾部供人工/模型判断
            sb.append("未解析出结构化失败，构建/测试输出尾部：\n")
                    .append(o.outputTail() == null ? "" : o.outputTail());
        }
        return sb.toString();
    }

    private static VerificationRunner.Kind parseKind(String kind) {
        if (kind == null) {
            return VerificationRunner.Kind.COMPILE;
        }
        String k = kind.trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "test" -> VerificationRunner.Kind.TEST;
            case "build", "compile", "" -> VerificationRunner.Kind.COMPILE;
            default -> VerificationRunner.Kind.COMPILE;
        };
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(截断)";
    }

    private static String str(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
