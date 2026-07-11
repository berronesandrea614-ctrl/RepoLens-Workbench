package com.repolens.kernel.ask;

import java.util.List;

/**
 * 结构化反问规格（对标 Claude Code 的 AskUserQuestion）：一次 askUser 可含一到多个问题，
 * 每个问题带一个短标题（做左右切换的标签）+ 若干候选选项（标题 + 说明），也支持多选。
 * 无选项的问题退化为自由文本作答。
 *
 * @param questions 一到多个问题（前端做成可左右切换的多选卡片）
 */
public record AskSpec(List<Question> questions) {

    /**
     * 一个问题。
     *
     * @param header      短标题（≤12 字，做切换标签用；可空则用序号）
     * @param question    问题正文
     * @param multiSelect 是否可多选（默认单选）
     * @param options     候选选项（空则该问题为自由文本作答）
     */
    public record Question(String header, String question, boolean multiSelect, List<Option> options) {
    }

    /**
     * 一个候选选项。
     *
     * @param label       选项标题（简短）
     * @param description 选项说明（解释这个选项意味着什么，可空）
     */
    public record Option(String label, String description) {
    }
}
