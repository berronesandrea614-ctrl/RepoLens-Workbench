package com.repolens.service.impl.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 AI 最终答案（answer_preview）里正则捕获「声称完成」和「声称验证通过」。
 * 全确定性，不依赖 LLM。失败安全：解析异常返回全 false 结果。
 *
 * <p>落库时机：AgentRunServiceImpl.record() 时调用，结果写入 agent_run.claimed_* 列。
 */
public final class SelfReportParser {

    // 声称验证通过的关键词
    private static final Pattern VERIFIED_PAT = Pattern.compile(
            "(?:已测试通过|测试全部通过|测试通过|已验证|所有测试.*通过|verified|build green|编译通过|tests? pass)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // 声称完成的关键词
    private static final Pattern SUCCESS_PAT = Pattern.compile(
            "(?:已完成|已实现|完成了|搞定|已经完成|全部完成|done|fixed|完成)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private SelfReportParser() {}

    /**
     * 解析答案预览，提取声称字段。
     *
     * @param answerPreview AI 最终答案（可为 null/空）
     * @return 解析结果（claimedSuccess/claimedVerified/claimEvidence）
     */
    public static Result parse(String answerPreview) {
        if (answerPreview == null || answerPreview.isBlank()) {
            return Result.empty();
        }
        try {
            boolean verified = VERIFIED_PAT.matcher(answerPreview).find();
            boolean success  = SUCCESS_PAT.matcher(answerPreview).find();

            String evidence = null;
            if (verified || success) {
                // 提取命中的原句（最多 500 字）
                evidence = extractEvidence(answerPreview, verified ? VERIFIED_PAT : SUCCESS_PAT);
            }
            return new Result(success, verified, evidence);
        } catch (Exception ex) {
            return Result.empty();
        }
    }

    /** 提取正则首次命中所在句子（最多 500 字）。 */
    private static String extractEvidence(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return null;
        int start = Math.max(0, m.start() - 30);
        int end   = Math.min(text.length(), m.end() + 60);
        String snippet = text.substring(start, end).trim();
        return snippet.length() > 500 ? snippet.substring(0, 500) : snippet;
    }

    /** 解析结果。 */
    public record Result(boolean claimedSuccess, boolean claimedVerified, String claimEvidence) {
        static Result empty() { return new Result(false, false, null); }
    }
}
