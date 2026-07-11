package com.repolens.kernel.edit;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 编辑后的语法护栏（规划 M3 关键类）。
 *
 * <p>作用：agent 每次 Write/Edit/MultiEdit <b>产出的最终内容</b>在落盘影子区前先 parse 一遍，
 * 语法非法就<b>拒绝</b>并把 JavaParser 的具体报错（含行列）喂回 agent 让它自愈——
 * 这样坏编辑不会进影子区、更不会等到 verification 才发现，是「改坏立即被挡」的第一道闸。
 *
 * <p>诚实边界：只护栏 Java（{@code .java}）。非 Java 文件（md/xml/properties/前端）当前一律放行，
 * 因为对它们做「语法合法」判断需各自的 parser，属后续扩展；返回 {@code valid} 且注明 skipped。
 * 这是真校验（JavaParser 3.25，非空壳），不是「字段存在即通过」。
 */
@Component("kernelSyntaxValidator")
public class SyntaxValidator {

    /**
     * 校验结果。
     *
     * @param valid   是否合法（skipped 的非 Java 文件也记为 valid）
     * @param skipped 是否因非 Java 而跳过
     * @param message 人读的结论；非法时含首条 JavaParser 报错（行列）
     */
    public record ValidationResult(boolean valid, boolean skipped, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, false, "语法校验通过");
        }

        public static ValidationResult skipped(String reason) {
            return new ValidationResult(true, true, reason);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, false, message);
        }
    }

    /**
     * 对某文件的最终内容做语法校验。
     *
     * @param relPath 相对路径（用于判断文件类型与报错定位）
     * @param content 编辑后的完整文件内容
     */
    public ValidationResult validate(String relPath, String content) {
        if (relPath == null || !relPath.endsWith(".java")) {
            return ValidationResult.skipped("非 Java 文件，跳过语法护栏：" + relPath);
        }
        try {
            ParseResult<CompilationUnit> result = new JavaParser().parse(content);
            if (result.isSuccessful()) {
                return ValidationResult.ok();
            }
            List<Problem> problems = result.getProblems();
            String detail = problems.stream()
                    .limit(3)
                    .map(Problem::getVerboseMessage)
                    .collect(Collectors.joining("；"));
            return ValidationResult.invalid("Java 语法非法，编辑被拒绝：" + detail
                    + "（共 " + problems.size() + " 处问题）。请修正后重试。");
        } catch (Exception e) {
            // fail-safe：parser 自身异常不应崩掉编辑链路，退化为「无法确认合法」按非法处理更安全
            return ValidationResult.invalid("语法校验器异常，保守拒绝编辑：" + e.getMessage());
        }
    }
}
