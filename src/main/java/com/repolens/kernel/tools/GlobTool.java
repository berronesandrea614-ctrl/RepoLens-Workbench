package com.repolens.kernel.tools;

import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.ToolDefinition;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * glob 工具：把 M2 的 {@link GlobRunner} 接入内核工具注册表（只读，可并发调度）。
 * 在影子区按文件名模式匹配、mtime 倒序、上限 100。
 */
@Component
public class GlobTool implements KernelTool {

    private final GlobRunner runner;
    private final ShadowWorkspaceManager shadowManager;

    public GlobTool(GlobRunner runner, ShadowWorkspaceManager shadowManager) {
        this.runner = runner;
        this.shadowManager = shadowManager;
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition d = new ToolDefinition();
        d.setName("glob");
        d.setDescription("按文件名模式查找文件（支持 ** 跨目录），结果按修改时间倒序。"
                + "做什么：按 pattern 匹配文件路径。"
                + "何时用：知道文件名/后缀但不知位置时。"
                + "何时不用：搜文件内容用 grep。"
                + "示例：{\"pattern\":\"**/*.java\"}");
        d.setParameters(Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string", "description", "文件名 glob，如 **/*.java"),
                        "path", Map.of("type", "string", "description", "起始子目录（相对仓库根，默认全仓）")),
                "required", List.of("pattern")));
        return d;
    }

    @Override
    public String execute(ToolContext ctx, Map<String, Object> args) {
        Object pv = args == null ? null : args.get("pattern");
        String pattern = pv == null ? null : String.valueOf(pv);
        if (pattern == null || pattern.isEmpty()) {
            return "glob 失败：缺少 pattern。";
        }
        Object pathv = args.get("path");
        String path = pathv == null ? null : String.valueOf(pathv);
        Path base = (path == null || path.isBlank())
                ? ctx.shadow().root()
                : shadowManager.resolveInShadow(ctx.shadow().root(), path);

        GlobRunner.GlobResult r = runner.glob(pattern, base);
        if (r.paths().isEmpty()) {
            return "glob 无匹配：" + pattern;
        }
        return "glob 命中 " + r.paths().size() + (r.truncated() ? "（已截断至上限）" : "") + "：\n"
                + String.join("\n", r.paths());
    }
}
