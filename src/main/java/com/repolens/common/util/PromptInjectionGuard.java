package com.repolens.common.util;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Prompt 注入基础防护。
 * 落地 application.yml 里 repolens.security.prompt-injection-check-enabled 的开关
 * （原先有配置但无实现）。这是一道轻量启发式护栏，不替代模型侧防护，目的是挡掉
 * 最常见的“忽略以上指令 / 越权操作”类注入与超长输入。
 */
@Component
public class PromptInjectionGuard {

    private static final int DEFAULT_MAX_QUESTION_LENGTH = 2000;

    /** 常见注入意图模式（中英），命中即拒绝。 */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore\\s+(all\\s+)?(the\\s+)?(above|previous|prior)\\s+(instructions|prompts?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+)?(the\\s+)?(above|previous)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(all\\s+)?(your\\s+)?(previous\\s+)?(instructions|rules)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(reveal|show|print|leak)\\s+(your\\s+|the\\s+)?(system\\s+prompt|instructions)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("忽略(以上|上述|之前|前面)(的)?(所有)?(指令|提示|要求)"),
            Pattern.compile("(无视|不要遵守|忘记)(以上|上述|之前|你的)?(指令|规则|提示)"),
            Pattern.compile("(泄露|显示|打印|输出)(你的|系统)?(系统提示词?|提示词|指令)")
    );

    @Value("${repolens.security.prompt-injection-check-enabled:true}")
    private boolean enabled;

    @Value("${repolens.security.max-question-length:2000}")
    private int maxQuestionLength;

    /**
     * 校验用户问题。命中注入模式或超长则抛 BizException（BAD_REQUEST）。
     * 关闭开关时不做任何检查。
     */
    public void check(String question) {
        if (!enabled || !StringUtils.hasText(question)) {
            return;
        }
        int limit = maxQuestionLength > 0 ? maxQuestionLength : DEFAULT_MAX_QUESTION_LENGTH;
        if (question.length() > limit) {
            throw new BizException(ErrorCode.BAD_REQUEST, "question is too long");
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(normalized).find() || pattern.matcher(question).find()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "question rejected by prompt-injection guard");
            }
        }
    }
}
