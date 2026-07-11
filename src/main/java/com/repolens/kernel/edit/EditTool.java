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
 * Edit 工具：str_replace 精确片段替换（点编辑）。
 *
 * <p>三重校验（规划 M3「Edit 三校验：读后编→精确匹配→唯一性」）：
 * <ol>
 *   <li><b>读后编</b>：{@link EditGuards} 校验写前已 read 且磁盘未变；</li>
 *   <li><b>精确匹配</b>：{@code old_string} 必须在文件中原样出现（不做模糊/正则）；</li>
 *   <li><b>唯一性</b>：默认 {@code old_string} 必须恰好出现 1 次，出现多次要求 agent 补足上下文行，
 *       或显式 {@code replace_all=true} 才全量替换——避免改错位置。</li>
 * </ol>
 * 替换后过 {@link SyntaxValidator} 语法护栏，非法则整体拒绝、不落盘。
 */
@Component
public class EditTool implements KernelTool {

    private final ShadowWorkspaceManager shadowManager;
    private final FileChangeRecorder recorder;
    private final SyntaxValidator syntaxValidator;

    public EditTool(ShadowWorkspaceManager shadowManager, FileChangeRecorder recorder,
                    SyntaxValidator syntaxValidator) {
        this.shadowManager = shadowManager;
        this.recorder = recorder;
        this.syntaxValidator = syntaxValidator;
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition d = new ToolDefinition();
        d.setName("edit");
        d.setDescription("在文件中做精确片段替换（old_string→new_string）。"
                + "做什么：把 file_path 里唯一出现的 old_string 替换为 new_string。"
                + "何时用：改动局部几行时首选（省 token、精准）。"
                + "何时不用：old_string 无法唯一定位时请补足上下文行，或改用 write 整体重写。"
                + "要点：old_string 必须与文件内容逐字符一致（含缩进），且默认须唯一；改前先 read。"
                + "示例：{\"file_path\":\"A.java\",\"old_string\":\"int x = 1;\",\"new_string\":\"int x = 2;\"}");
        d.setParameters(Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "相对仓库根的文件路径"),
                        "old_string", Map.of("type", "string", "description", "要被替换的原文片段（须逐字符一致）"),
                        "new_string", Map.of("type", "string", "description", "替换后的新片段"),
                        "replace_all", Map.of("type", "boolean", "description", "是否替换所有出现（默认 false，要求唯一）")),
                "required", List.of("file_path", "old_string", "new_string")));
        return d;
    }

    @Override
    public String execute(ToolContext ctx, Map<String, Object> args) {
        String relPath = EditSupport.str(args, "file_path");
        String oldStr = EditSupport.str(args, "old_string");
        String newStr = EditSupport.str(args, "new_string");
        boolean replaceAll = EditSupport.bool(args, "replace_all", false);
        if (relPath == null || relPath.isBlank()) {
            return "edit 失败：缺少 file_path。";
        }
        if (oldStr == null || oldStr.isEmpty()) {
            return "edit 失败：缺少 old_string（不能为空，用 write 新建文件）。";
        }
        if (newStr == null) {
            return "edit 失败：缺少 new_string。";
        }
        if (oldStr.equals(newStr)) {
            return "edit 无效：old_string 与 new_string 相同，未产生任何改动。";
        }
        try {
            Path file = shadowManager.resolveInShadow(ctx.shadow().root(), relPath);
            if (!Files.exists(file)) {
                return "edit 失败：文件不存在：" + relPath + "（新建请用 write）。";
            }
            String content = Files.readString(file);

            String guard = EditGuards.checkReadBeforeWrite(ctx, relPath, content);
            if (guard != null) {
                return guard;
            }

            int count = countOccurrences(content, oldStr);
            if (count == 0) {
                return "edit 失败：在 " + relPath + " 中未找到 old_string（须逐字符一致，含缩进/空白）。"
                        + "请先 read 确认原文后重试。";
            }
            if (count > 1 && !replaceAll) {
                return "edit 失败：old_string 在 " + relPath + " 中出现了 " + count + " 次，无法唯一定位。"
                        + "请在 old_string 里补足上下文行使其唯一，或设 replace_all=true 全部替换。";
            }

            String updated = replaceAll
                    ? content.replace(oldStr, newStr)
                    : replaceFirst(content, oldStr, newStr);

            SyntaxValidator.ValidationResult vr = syntaxValidator.validate(relPath, updated);
            if (!vr.valid()) {
                return "edit 被语法护栏拒绝（改动未落盘）：" + vr.message();
            }

            recorder.writeToShadow(ctx.shadow(), ctx.repoId(), ctx.sessionId(), ctx.runId(),
                    ctx.repoDir(), relPath, updated);
            ctx.tracker().refreshAfterWrite(relPath, EditSupport.sha256(updated));
            return "已编辑 " + relPath + "（替换 " + (replaceAll ? count : 1) + " 处，写入影子区）"
                    + (vr.skipped() ? "。" : "，语法校验通过。");
        } catch (Exception e) {
            return "edit 失败：" + e.getMessage();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** 只替换第一处（唯一性已保证只有一处，等价全替，但语义上明确「首处」）。 */
    private static String replaceFirst(String content, String oldStr, String newStr) {
        int idx = content.indexOf(oldStr);
        return content.substring(0, idx) + newStr + content.substring(idx + oldStr.length());
    }
}
