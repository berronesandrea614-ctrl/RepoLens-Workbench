package com.repolens.kernel.drift.spi;

/**
 * 调用图里的一个符号节点（对应隔壁 {@code code_symbol} 一行，只取内核漂移比对需要的语义字段）。
 *
 * <p>⚠️ {@code symbolId} 是隔壁当前态的自增 id，<b>重索引会重置、跨快照不稳定</b>——它只用于把
 * {@link DependencyEdge#sourceSymbolId()} 关联到<b>同一份快照内</b>的节点；内核做跨时间漂移身份识别时
 * 一律改用「语义稳定 key」（{@code language|filePath|symbolType|className|methodName|signature}），不认 id。
 *
 * @param symbolId   隔壁当前态自增 id（仅同快照内关联边用；跨快照不作身份）
 * @param language   语言（java/ts/py/go/rust/csharp/ruby…）
 * @param symbolType 符号类型（隔壁 {@code symbol_type} 原样，如 METHOD/CLASS）
 * @param className  所属类名（可空）
 * @param methodName 方法名（可空）
 * @param signature  签名（可空）
 * @param filePath   所在文件路径（隔壁需 join {@code code_file} 由 {@code file_id} 取 {@code file_path}）
 * @param startLine  起始行
 * @param endLine    结束行
 */
public record SymbolNode(long symbolId,
                         String language,
                         String symbolType,
                         String className,
                         String methodName,
                         String signature,
                         String filePath,
                         int startLine,
                         int endLine) {

    /**
     * 语义稳定 key：跨快照识别「同一个符号」的依据（不依赖会变的自增 id）。
     * 内核漂移比对、节点增删判定、漂移归因全用它。
     */
    public String stableKey() {
        return nz(language) + "|" + nz(filePath) + "|" + nz(symbolType)
                + "|" + nz(className) + "|" + nz(methodName) + "|" + nz(signature);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
