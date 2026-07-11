package com.repolens.kernel.verify;

import java.util.List;

/**
 * 一次验证运行的领域结果（未落库前的纯值对象）。
 *
 * @param verificationId 落库后的 {@code rk_verification_run}.id（未落库为 null）
 * @param buildTarget    构建体系 code；探测不到为 {@code "unknown"}
 * @param kind           COMPILE / TEST / LINT
 * @param exitCode       进程退出码（未执行为 -1）
 * @param passed         是否通过（exitCode==0 且未探测到失败）
 * @param outputTail     输出尾部（截断保留）
 * @param failures       结构化失败列表
 * @param networkIsolated 是否断网执行
 */
public record VerificationOutcome(
        Long verificationId,
        String buildTarget,
        String kind,
        int exitCode,
        boolean passed,
        String outputTail,
        List<Failure> failures,
        boolean networkIsolated
) {
    public VerificationOutcome withVerificationId(Long id) {
        return new VerificationOutcome(id, buildTarget, kind, exitCode, passed, outputTail, failures, networkIsolated);
    }
}
