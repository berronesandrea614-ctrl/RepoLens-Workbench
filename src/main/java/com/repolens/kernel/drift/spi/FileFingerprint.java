package com.repolens.kernel.drift.spi;

/**
 * 文件级指纹（对应隔壁 {@code code_file} 一行）——内核 M9 的「时间指纹 / 漂移触发源」。
 *
 * <p>{@code contentHash}（文件内容 SHA-256）能判某文件是否变过；{@code lastCommitId} 是导入时的 commit
 * （git 仓库是真 commit，本地 {@code file://} 导入是 {@code "local-snapshot"}）——两者一起给漂移做
 * 「哪个 commit/导入引入」的归因锚点。内核不改隔壁 schema，只读这三样。
 *
 * @param filePath    文件路径
 * @param contentHash 文件内容 SHA-256
 * @param lastCommitId 导入时 commit（真 commit 或 "local-snapshot"）
 * @param lineCount   行数
 */
public record FileFingerprint(String filePath,
                              String contentHash,
                              String lastCommitId,
                              int lineCount) {
}
