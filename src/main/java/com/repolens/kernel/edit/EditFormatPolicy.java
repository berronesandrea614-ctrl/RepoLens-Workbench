package com.repolens.kernel.edit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 编辑格式自适应模型能力（规划 M3：「强模型 diff、弱模型整文件」）。
 *
 * <p>动机（对齐蓝图与 Confucius Code Agent 结论：弱模型 + 强 scaffold 可反超）：
 * 强模型能可靠地产出精确的 str_replace 片段（老片段唯一、缩进对齐），用「点编辑」省 token；
 * 弱模型（本地 Ollama / 小模型）经常给出不唯一或缩进错位的片段，让它整文件重写反而更稳。
 * 本策略据模型名/显式档位给出应向 agent 提示的编辑格式，并挑选默认工具。
 *
 * <p>它不改工具的正确性（Edit/Write 都真校验），只影响系统提示词该引导 agent 用哪种方式，
 * 从而提高弱模型下的编辑合规率——这正是 M3 的验收指标之一。
 */
@Component("kernelEditFormatPolicy")
public class EditFormatPolicy {

    /** 首选编辑格式。 */
    public enum EditFormat {
        /** 精确片段替换（Edit/MultiEdit）——强模型。 */
        STR_REPLACE,
        /** 整文件重写（Write）——弱模型更稳。 */
        WHOLE_FILE
    }

    /** 逗号分隔的「弱模型」名单前缀（小写包含匹配）。可由配置覆盖。 */
    private final String weakModelHints;

    public EditFormatPolicy(
            @Value("${repolens.kernel.edit.weak-models:ollama,qwen,phi,gemma,llama2,tinyllama,codellama:7b,deepseek-coder:1.3b}")
            String weakModelHints) {
        this.weakModelHints = weakModelHints == null ? "" : weakModelHints.toLowerCase(Locale.ROOT);
    }

    /** 依模型名判定首选编辑格式。null/空当作强模型（不误伤主力模型）。 */
    public EditFormat formatFor(String modelName) {
        if (isWeak(modelName)) {
            return EditFormat.WHOLE_FILE;
        }
        return EditFormat.STR_REPLACE;
    }

    /** 该模型是否被归为弱模型。 */
    public boolean isWeak(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String m = modelName.toLowerCase(Locale.ROOT);
        for (String hint : weakModelHints.split(",")) {
            String h = hint.trim();
            if (!h.isEmpty() && m.contains(h)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成注入系统提示词的编辑指引：告诉 agent 本模型档位下该优先用哪种编辑方式。
     * 强模型引导 str_replace（省 token、点改）；弱模型引导整文件重写（避免片段不唯一/缩进错位）。
     */
    public String guidanceFor(String modelName) {
        if (formatFor(modelName) == EditFormat.WHOLE_FILE) {
            return "编辑指引：本模型下优先使用 write 工具整文件重写（先 read 拿到完整内容，"
                    + "在其基础上改好后整体写回），避免 edit 的片段替换因不唯一或缩进错位失败。";
        }
        return "编辑指引：优先使用 edit 工具做精确片段替换（old_string 需在文件中唯一，"
                + "含足够上下文行）；仅当改动面过大时才用 write 整文件重写。";
    }
}
