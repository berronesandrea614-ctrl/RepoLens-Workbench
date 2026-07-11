package com.repolens.service.support;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SyntaxValidator {

    public record ValidationResult(boolean valid, String errorMessage) {
        public static ValidationResult ok() { return new ValidationResult(true, null); }
        public static ValidationResult fail(String msg) { return new ValidationResult(false, msg); }
    }

    public ValidationResult validate(String filePath, String newContent) {
        if (filePath == null || !filePath.endsWith(".java")) {
            return ValidationResult.ok();
        }
        if (newContent == null || newContent.isBlank()) {
            return ValidationResult.fail("语法错误：内容为空");
        }
        try {
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> result = parser.parse(newContent == null ? "" : newContent);
            if (result.isSuccessful()) {
                return ValidationResult.ok();
            }
            StringBuilder sb = new StringBuilder("语法错误：");
            result.getProblems().forEach(p -> sb.append(p.getMessage()).append("; "));
            return ValidationResult.fail(sb.toString());
        } catch (Exception e) {
            log.warn("SyntaxValidator: parser threw exception for {} (fail-safe, allowing): {}",
                    filePath, e.getMessage());
            return ValidationResult.ok();
        }
    }
}
