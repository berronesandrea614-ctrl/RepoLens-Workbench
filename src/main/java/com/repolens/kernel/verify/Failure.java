package com.repolens.kernel.verify;

/**
 * 一条结构化验证失败（喂回模型自愈的最小单元）。
 *
 * @param file    相对影子区根的文件路径（无法定位则为空串）
 * @param line    行号（无法定位则为 0）
 * @param symbol  符号/错误码/测试方法（如 {@code ClassA#testX}、TS 错误码），无则空串
 * @param message 编译器/测试框架给出的原始错误信息
 * @param context 函数级上下文：出错处所在方法/函数的签名 + 邻近代码窗口，帮模型精准定位
 */
public record Failure(String file, int line, String symbol, String message, String context) {

    public static Failure of(String file, int line, String message) {
        return new Failure(file == null ? "" : file, Math.max(line, 0), "", message == null ? "" : message, "");
    }

    public Failure withContext(String ctx) {
        return new Failure(file, line, symbol, message, ctx == null ? "" : ctx);
    }

    public Failure withSymbol(String sym) {
        return new Failure(file, line, sym == null ? "" : sym, message, context);
    }
}
