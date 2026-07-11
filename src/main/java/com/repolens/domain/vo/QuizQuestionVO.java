package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 理解测验单题 VO（发给前端，不含正确答案）。
 *
 * <p>对应 ComprehensionQuizService 生成的测验题。choices 为 4 个选项文本（含 A/B/C/D 前缀）。
 */
@Data
@Builder
public class QuizQuestionVO {

    /** 题序（0-based）。 */
    private int id;

    /** 题干文字。 */
    private String questionText;

    /** 选项文本列表（4 项）。 */
    private List<String> choices;
}
