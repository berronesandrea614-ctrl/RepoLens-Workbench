package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 理解测验提交结果 VO。
 *
 * <p>grading：score = 答对题数 / 总题数 × 100（取整）。
 * passed = score ≥ 65；passed 时后端自动调用 mark-reviewed(QUIZZED, score)。
 */
@Data
@Builder
public class QuizResultVO {

    /** 测验得分 0–100。 */
    private int quizScore;

    /** 是否通过（得分 ≥ 65%）。 */
    private boolean passed;

    /** 每题反馈（答对/答错 + 正确选项提示）。 */
    private List<String> feedbacks;

    /**
     * 被标记为 QUIZZED 的 change_id；仅 passed=true 时有值。
     * null 表示通过但未找到该文件的 change（不影响偿债闭环，仅无法在前端跳 diff）。
     */
    private Long changeId;

    /**
     * 偿债后最新债务分（mark-reviewed 触发重算后读物化表）。
     * null 表示未能重算（非致命）。
     */
    private Integer newDebtScore;

    /**
     * 偿债后债务分档（RED / YELLOW / GREEN / null）。
     */
    private String newDebtBand;
}
