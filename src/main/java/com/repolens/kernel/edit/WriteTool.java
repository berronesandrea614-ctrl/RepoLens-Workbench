package com.repolens.kernel.edit;

import com.repolens.kernel.shadow.FileChangeRecorder;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.ToolDefinition;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Write 工具：整文件写入影子区（新建或整体覆盖）。
 *
 * <p>不变式（规划 §3.3）：<b>覆盖已存在文件前，本会话必须先 read 过且磁盘自读后未变</b>——
 * 防止 agent 盲写覆盖没看过的代码，或覆盖掉读之后发生的中间修改。新建文件（影子区尚不存在）豁免读要求。
 *
 * <p>落盘前过 {@link SyntaxValidator} 语法护栏：写出的 Java 若语法非法直接拒绝并把报错喂回，
 * 坏内容不进影子区。写成功后刷新读视图，允许对同一文件连续编辑而不必重读。
 */
@Component
public class WriteTool implements KernelTool {

    private final ShadowWorkspaceManager shadowManager;
    private final FileChangeRecorder recorder;
    private final SyntaxValidator syntaxValidator;

    public WriteTool(ShadowWorkspaceManager shadowManager, FileChangeRecorder recorder,
                     SyntaxValidator syntaxValidator) {
        this.shadowManager = shadowManager;
        this.recorder = recorder;
        this.syntaxValidator = syntaxValidator;
    }

    @Override
    public String name() {
        return "write";
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition d = new ToolDefinition();
        d.setName("write");
        d.setDescription("把完整内容写入某文件（新建或整体覆盖）。"
                + "做什么：用 content 整体写 file_path。"
                + "何时用：新建文件，或改动面很大整体重写更清晰时。"
                + "何时不用：只改几行请用 edit（更省 token、更少误伤）；覆盖已有文件前必须先 read。"
                + "示例：{\"file_path\":\"src/main/java/A.java\",\"content\":\"package a;...\"}");
        d.setParameters(Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "相对仓库根的文件路径"),
                        "content", Map.of("type", "string", "description", "写入的完整文件内容")),
                "required", List.of("file_path", "content")));
        return d;
    }

    @Override
    public String execute(ToolContext ctx, Map<String, Object> args) {
        String relPath = EditSupport.str(args, "file_path");
        String content = EditSupport.str(args, "content");
        if (relPath == null || relPath.isBlank()) {
            return "write 失败：缺少 file_path。";
        }
        if (content == null) {
            return "write 失败：缺少 content。";
        }
        try {
            Path file = shadowManager.resolveInShadow(ctx.shadow().root(), relPath);
            boolean existed = Files.exists(file);

            // 读后写不变式：覆盖已有文件必须先 read 且磁盘未变；新建豁免
            if (existed) {
                String guard = EditGuards.checkReadBeforeWrite(ctx, relPath, Files.readString(file));
                if (guard != null) {
                    return guard;
                }
            }

            SyntaxValidator.ValidationResult vr = syntaxValidator.validate(relPath, content);
            if (!vr.valid()) {
                return "write 被语法护栏拒绝：" + vr.message();
            }

            recorder.writeToShadow(ctx.shadow(), ctx.repoId(), ctx.sessionId(), ctx.runId(),
                    ctx.repoDir(), relPath, content);
            ctx.tracker().refreshAfterWrite(relPath, EditSupport.sha256(content));
            return (existed ? "已覆盖 " : "已新建 ") + relPath + "（写入影子区，"
                    + content.split("\n", -1).length + " 行）"
                    + (vr.skipped() ? "。" : "，语法校验通过。");
        } catch (Exception e) {
            return "write 失败：" + e.getMessage();
        }
    }
}
