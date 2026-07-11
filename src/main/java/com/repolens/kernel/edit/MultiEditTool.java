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
 * MultiEdit 工具：对<b>单个文件</b>按顺序施加多处编辑，<b>全有或全无</b>（原子）。
 *
 * <p>为什么要原子：多处相关改动若「改了前两处、第三处片段找不到」半途而废，会留下语法/语义不一致的
 * 中间态。本工具把所有编辑先施加在内存副本上，任一处失败（片段不存在/不唯一）整体回滚、一字不落盘；
 * 全部成功且最终内容过语法护栏后才一次性写入影子区。
 *
 * <p>每一处编辑在<b>前序编辑后的中间内容</b>上定位（顺序依赖），与 agent 直觉一致。
 */
@Component
public class MultiEditTool implements KernelTool {

    private final ShadowWorkspaceManager shadowManager;
    private final FileChangeRecorder recorder;
    private final SyntaxValidator syntaxValidator;

    public MultiEditTool(ShadowWorkspaceManager shadowManager, FileChangeRecorder recorder,
                         SyntaxValidator syntaxValidator) {
        this.shadowManager = shadowManager;
        this.recorder = recorder;
        this.syntaxValidator = syntaxValidator;
    }

    @Override
    public String name() {
        return "multi_edit";
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition d = new ToolDefinition();
        d.setName("multi_edit");
        d.setDescription("对单个文件按顺序施加多处片段替换，全部成功才落盘（原子）。"
                + "做什么：edits 数组里每项 {old_string,new_string,replace_all} 依次施加在 file_path 上。"
                + "何时用：同一文件需要多处相关改动，希望要么全改要么全不改时。"
                + "何时不用：只改一处用 edit；跨多个文件请分别调用。"
                + "要点：任一处 old_string 找不到或不唯一，整批拒绝、一字不落盘；改前先 read。");
        d.setParameters(Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "相对仓库根的文件路径"),
                        "edits", Map.of(
                                "type", "array",
                                "description", "编辑列表，按顺序施加",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "old_string", Map.of("type", "string"),
                                                "new_string", Map.of("type", "string"),
                                                "replace_all", Map.of("type", "boolean")),
                                        "required", List.of("old_string", "new_string")))),
                "required", List.of("file_path", "edits")));
        return d;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(ToolContext ctx, Map<String, Object> args) {
        String relPath = EditSupport.str(args, "file_path");
        if (relPath == null || relPath.isBlank()) {
            return "multi_edit 失败：缺少 file_path。";
        }
        Object editsObj = args == null ? null : args.get("edits");
        if (!(editsObj instanceof List<?> rawEdits) || rawEdits.isEmpty()) {
            return "multi_edit 失败：edits 必须是非空数组。";
        }
        try {
            Path file = shadowManager.resolveInShadow(ctx.shadow().root(), relPath);
            if (!Files.exists(file)) {
                return "multi_edit 失败：文件不存在：" + relPath + "（新建请用 write）。";
            }
            String content = Files.readString(file);

            String guard = EditGuards.checkReadBeforeWrite(ctx, relPath, content);
            if (guard != null) {
                return guard;
            }

            // 全部先施加在内存副本上——任一处失败整体回滚，绝不半途落盘
            String working = content;
            int totalReplacements = 0;
            for (int i = 0; i < rawEdits.size(); i++) {
                if (!(rawEdits.get(i) instanceof Map<?, ?> em)) {
                    return "multi_edit 失败：第 " + (i + 1) + " 项编辑格式非法。";
                }
                Map<String, Object> edit = (Map<String, Object>) em;
                String oldStr = EditSupport.str(edit, "old_string");
                String newStr = EditSupport.str(edit, "new_string");
                boolean replaceAll = EditSupport.bool(edit, "replace_all", false);
                if (oldStr == null || oldStr.isEmpty() || newStr == null) {
                    return "multi_edit 失败：第 " + (i + 1) + " 项缺少 old_string/new_string。";
                }
                int count = countOccurrences(working, oldStr);
                if (count == 0) {
                    return "multi_edit 整体拒绝（未落盘）：第 " + (i + 1) + " 项的 old_string 在当前内容中找不到"
                            + "（须逐字符一致；注意它可能依赖前序编辑的结果）。";
                }
                if (count > 1 && !replaceAll) {
                    return "multi_edit 整体拒绝（未落盘）：第 " + (i + 1) + " 项的 old_string 出现 " + count
                            + " 次不唯一。请补足上下文或设 replace_all=true。";
                }
                working = replaceAll ? working.replace(oldStr, newStr) : replaceFirst(working, oldStr, newStr);
                totalReplacements += replaceAll ? count : 1;
            }

            SyntaxValidator.ValidationResult vr = syntaxValidator.validate(relPath, working);
            if (!vr.valid()) {
                return "multi_edit 被语法护栏整体拒绝（未落盘）：" + vr.message();
            }

            recorder.writeToShadow(ctx.shadow(), ctx.repoId(), ctx.sessionId(), ctx.runId(),
                    ctx.repoDir(), relPath, working);
            ctx.tracker().refreshAfterWrite(relPath, EditSupport.sha256(working));
            return "已原子编辑 " + relPath + "（" + rawEdits.size() + " 项、共替换 " + totalReplacements
                    + " 处，写入影子区）" + (vr.skipped() ? "。" : "，语法校验通过。");
        } catch (Exception e) {
            return "multi_edit 失败：" + e.getMessage();
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

    private static String replaceFirst(String content, String oldStr, String newStr) {
        int idx = content.indexOf(oldStr);
        return content.substring(0, idx) + newStr + content.substring(idx + oldStr.length());
    }
}
