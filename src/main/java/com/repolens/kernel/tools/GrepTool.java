package com.repolens.kernel.tools;

import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.ToolDefinition;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * grep 工具：把 M2 的 {@link RipgrepRunner} 接入内核工具注册表（只读，可并发调度）。
 * 在<b>影子区</b>搜索，保证 agent 搜到的是含自身改动的一致视图。
 */
@Component
public class GrepTool implements KernelTool {

    private final RipgrepRunner runner;
    private final ShadowWorkspaceManager shadowManager;

    public GrepTool(RipgrepRunner runner, ShadowWorkspaceManager shadowManager) {
        this.runner = runner;
        this.shadowManager = shadowManager;
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition d = new ToolDefinition();
        d.setName("grep");
        d.setDescription("在仓库中做字面（非正则）文本搜索。"
                + "做什么：按 pattern 搜内容，mode=content 回行号、files 回文件名、count 回每文件命中数。"
                + "何时用：找某标识符/字符串出现在哪。"
                + "何时不用：绝不要在 bash 里跑 grep/rg，一律用本工具。"
                + "示例：{\"pattern\":\"AgentLoopExecutor\",\"mode\":\"content\"}");
        d.setParameters(Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string", "description", "字面搜索串"),
                        "path", Map.of("type", "string", "description", "限定子目录（相对仓库根，默认全仓）"),
                        "glob", Map.of("type", "string", "description", "文件名过滤，如 *.java"),
                        "case_insensitive", Map.of("type", "boolean", "description", "忽略大小写（默认 false）"),
                        "mode", Map.of("type", "string", "description", "content|files|count（默认 content）")),
                "required", List.of("pattern")));
        return d;
    }

    @Override
    public String execute(ToolContext ctx, Map<String, Object> args) {
        String pattern = str(args, "pattern");
        if (pattern == null || pattern.isEmpty()) {
            return "grep 失败：缺少 pattern。";
        }
        String path = str(args, "path");
        String glob = str(args, "glob");
        boolean ci = bool(args, "case_insensitive");
        RipgrepRunner.Mode mode = parseMode(str(args, "mode"));

        Path base = (path == null || path.isBlank())
                ? ctx.shadow().root()
                : shadowManager.resolveInShadow(ctx.shadow().root(), path);
        RipgrepRunner.GrepResult r = runner.grep(pattern, base, glob, ci, mode);
        String head = "grep 命中 " + r.matchCount() + (r.truncated() ? "（已截断）" : "") + "：\n";
        return head + (r.output().isEmpty() ? "（无匹配）" : r.output());
    }

    private static RipgrepRunner.Mode parseMode(String m) {
        if (m == null) {
            return RipgrepRunner.Mode.CONTENT;
        }
        try {
            return RipgrepRunner.Mode.valueOf(m.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RipgrepRunner.Mode.CONTENT;
        }
    }

    private static String str(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean bool(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }
}
