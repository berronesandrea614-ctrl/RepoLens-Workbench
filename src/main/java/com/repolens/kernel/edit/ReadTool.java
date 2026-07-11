package com.repolens.kernel.edit;

import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.ToolDefinition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Read 工具：带行号、分页（offset/limit）读取影子区文件。
 *
 * <p>两个职责：①把内容按 {@code <行号>\t<原文>} 格式回给 agent（行号是后续 Edit 定位与
 * {@code file_path:line} 引用的基础）；②在 {@link com.repolens.kernel.spi.ReadTracker} 里
 * 记下「读到时的内容 hash」，为写类工具的读后写不变式打底。
 *
 * <p>读的是<b>影子区</b>而非真目录：影子区是真目录的 CoW 克隆，agent 看到的是「含自己改动」的
 * 一致视图；这样「读→改→再读」看到的是自己刚写的内容，而非真目录旧值。
 */
@Component
public class ReadTool implements KernelTool {

    /** 一次默认最多回多少行，防止把整个大文件灌进上下文。 */
    private static final int DEFAULT_LIMIT = 2000;

    private final ShadowWorkspaceManager shadowManager;

    public ReadTool(ShadowWorkspaceManager shadowManager) {
        this.shadowManager = shadowManager;
    }

    @Override
    public String name() {
        return "read";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition d = new ToolDefinition();
        d.setName("read");
        d.setDescription("读取仓库内某文件的内容，返回带行号的文本。"
                + "做什么：按 file_path 读文件，可用 offset/limit 分页读大文件。"
                + "何时用：编辑任何文件前必须先 read（读后写不变式），或需查看代码时。"
                + "何时不用：搜索代码用 grep、找文件用 glob，不要用 read 逐个翻。"
                + "示例：{\"file_path\":\"src/main/java/A.java\",\"offset\":100,\"limit\":50}");
        d.setParameters(Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "相对仓库根的文件路径"),
                        "offset", Map.of("type", "integer", "description", "起始行号（1 基，默认 1）"),
                        "limit", Map.of("type", "integer", "description", "最多读多少行（默认 2000）")),
                "required", List.of("file_path")));
        return d;
    }

    @Override
    public String execute(ToolContext ctx, Map<String, Object> args) {
        String relPath = EditSupport.str(args, "file_path");
        if (relPath == null || relPath.isBlank()) {
            return "read 失败：缺少 file_path 参数。";
        }
        try {
            Path file = shadowManager.resolveInShadow(ctx.shadow().root(), relPath);
            if (!Files.exists(file)) {
                return "read 失败：文件不存在：" + relPath + "（如需新建请用 write）。";
            }
            if (Files.isDirectory(file)) {
                return "read 失败：这是目录不是文件：" + relPath;
            }
            String content = Files.readString(file);
            // 记账：读到时的完整内容 hash，供写前核对磁盘是否已变
            ctx.tracker().recordRead(relPath, EditSupport.sha256(content));

            List<String> lines = content.isEmpty() ? List.of() : List.of(content.split("\n", -1));
            int offset = Math.max(1, EditSupport.intArg(args, "offset", 1));
            int limit = Math.max(1, EditSupport.intArg(args, "limit", DEFAULT_LIMIT));
            int from = Math.min(offset, Math.max(1, lines.size()));
            int to = Math.min(lines.size(), from - 1 + limit);

            StringBuilder sb = new StringBuilder();
            sb.append("文件 ").append(relPath).append("（共 ").append(lines.size()).append(" 行");
            if (from > 1 || to < lines.size()) {
                sb.append("，显示 ").append(from).append("-").append(to);
            }
            sb.append("）：\n");
            for (int i = from; i <= to; i++) {
                sb.append(i).append('\t').append(lines.get(i - 1)).append('\n');
            }
            if (to < lines.size()) {
                sb.append("… 还有 ").append(lines.size() - to).append(" 行未显示，用 offset=")
                        .append(to + 1).append(" 继续读。");
            }
            return sb.toString();
        } catch (IOException e) {
            return "read 失败：" + e.getMessage();
        }
    }
}
