package com.repolens.kernel.edit;

import com.repolens.kernel.spi.ToolContext;

/**
 * 编辑前置守卫：读后写不变式的统一判定，Write/Edit/MultiEdit 共用一处口径。
 */
final class EditGuards {

    private EditGuards() {
    }

    /**
     * 校验「写前必须先 read 且磁盘自读后未变」。
     *
     * @param ctx            工具上下文（含读记账本）
     * @param relPath        目标相对路径
     * @param currentContent 目标文件当前磁盘（影子区）内容
     * @return 违规时返回喂回 agent 的错误串；通过返回 {@code null}
     */
    static String checkReadBeforeWrite(ToolContext ctx, String relPath, String currentContent) {
        if (!ctx.tracker().wasRead(relPath)) {
            return "编辑被拒：写前必须先 read 该文件（读后写不变式）。请先 read " + relPath + " 再编辑。";
        }
        String readHash = ctx.tracker().readHash(relPath).orElse(null);
        String nowHash = EditSupport.sha256(currentContent);
        if (readHash != null && !readHash.equals(nowHash)) {
            return "编辑被拒：文件 " + relPath + " 自上次 read 后已发生变化，请重新 read 拿到最新内容再改，"
                    + "以免覆盖中间修改。";
        }
        return null;
    }
}
