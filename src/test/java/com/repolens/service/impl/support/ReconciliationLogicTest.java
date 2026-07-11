package com.repolens.service.impl.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账算法纯函数单测（Feature B P1）。
 * 覆盖：四态判定、改动分类（IN_PLAN/OVER_SCOPE/SILENT_ADD）、
 * 同源判定、路径匹配、断言计数、测试文件检测。
 */
class ReconciliationLogicTest {

    // ── 四态判定 ─────────────────────────────────────────────────────────────

    @Test
    void determinePlanItemStatus_LANDED_whenAllDeclaredFilesChanged() {
        List<String> declared = List.of("src/CaptchaService.java", "src/CaptchaMapper.java");
        Set<String> actual = Set.of("src/CaptchaService.java", "src/CaptchaMapper.java");
        assertThat(ReconciliationLogic.determinePlanItemStatus(declared, actual, Set.of()))
                .isEqualTo(ReconciliationLogic.LANDED);
    }

    @Test
    void determinePlanItemStatus_PARTIAL_whenSomeDeclaredFilesChanged() {
        List<String> declared = List.of("src/CaptchaService.java", "src/CaptchaMapper.java");
        Set<String> actual = Set.of("src/CaptchaService.java"); // only one
        assertThat(ReconciliationLogic.determinePlanItemStatus(declared, actual, Set.of()))
                .isEqualTo(ReconciliationLogic.PARTIAL);
    }

    @Test
    void determinePlanItemStatus_MISSING_ATTEMPTED_whenReadButNotChanged() {
        List<String> declared = List.of("src/LoginService.java");
        Set<String> actual = Set.of("src/OtherFile.java"); // not in declared
        Set<String> toolReads = Set.of("src/LoginService.java"); // read declared
        assertThat(ReconciliationLogic.determinePlanItemStatus(declared, actual, toolReads))
                .isEqualTo(ReconciliationLogic.MISSING_ATTEMPTED);
    }

    @Test
    void determinePlanItemStatus_MISSING_SILENT_whenNeitherChangedNorRead() {
        List<String> declared = List.of("src/LoginService.java");
        Set<String> actual = Set.of("src/OtherFile.java");
        Set<String> toolReads = Set.of("src/SomeOtherFile.java");
        assertThat(ReconciliationLogic.determinePlanItemStatus(declared, actual, toolReads))
                .isEqualTo(ReconciliationLogic.MISSING_SILENT);
    }

    @Test
    void determinePlanItemStatus_MISSING_SILENT_whenDeclaredEmpty() {
        assertThat(ReconciliationLogic.determinePlanItemStatus(
                List.of(), Set.of("src/Foo.java"), Set.of()))
                .isEqualTo(ReconciliationLogic.MISSING_SILENT);
    }

    @Test
    void determinePlanItemStatus_LANDED_withClassNameMatching() {
        List<String> declared = List.of("SecurityConfig"); // just class name
        Set<String> actual = Set.of("src/main/java/com/example/SecurityConfig.java");
        assertThat(ReconciliationLogic.determinePlanItemStatus(declared, actual, Set.of()))
                .isEqualTo(ReconciliationLogic.LANDED);
    }

    // ── 改动分类 ─────────────────────────────────────────────────────────────

    @Test
    void classifyChange_IN_PLAN_whenExactMatch() {
        List<String> declared = List.of("src/service/CaptchaService.java");
        assertThat(ReconciliationLogic.classifyChange("src/service/CaptchaService.java", declared))
                .isEqualTo(ReconciliationLogic.IN_PLAN);
    }

    @Test
    void classifyChange_IN_PLAN_whenClassNameMatch() {
        List<String> declared = List.of("CaptchaService");
        assertThat(ReconciliationLogic.classifyChange("src/service/CaptchaService.java", declared))
                .isEqualTo(ReconciliationLogic.IN_PLAN);
    }

    @Test
    void classifyChange_OVER_SCOPE_whenSameDirectory() {
        // declared: src/service/CaptchaService.java, actual: src/service/CaptchaHelper.java (same dir)
        List<String> declared = List.of("src/service/CaptchaService.java");
        assertThat(ReconciliationLogic.classifyChange("src/service/CaptchaHelper.java", declared))
                .isEqualTo(ReconciliationLogic.OVER_SCOPE);
    }

    @Test
    void classifyChange_SILENT_ADD_whenCompletelyUnrelated() {
        // declared: service/, actual: SecurityConfig (unrelated)
        List<String> declared = List.of("src/service/CaptchaService.java",
                "src/service/LoginService.java");
        assertThat(ReconciliationLogic.classifyChange(
                "src/config/SecurityConfig.java", declared))
                .isEqualTo(ReconciliationLogic.SILENT_ADD);
    }

    @Test
    void classifyChange_SILENT_ADD_whenDeclaredEmpty() {
        assertThat(ReconciliationLogic.classifyChange("src/Foo.java", List.of()))
                .isEqualTo(ReconciliationLogic.SILENT_ADD);
    }

    @Test
    void classifyChange_SILENT_ADD_forSecurityConfig_whenOnlyServiceDeclared() {
        // The acceptance test scenario: declared only service/, SecurityConfig is SILENT_ADD
        List<String> declared = List.of(
                "src/main/java/com/service/VerifyCodeService.java",
                "src/main/java/com/service/LoginService.java",
                "src/main/java/com/mapper/VerifyCodeMapper.java");
        // SecurityConfig is in a completely different package
        String result = ReconciliationLogic.classifyChange(
                "src/main/java/com/config/SecurityConfig.java", declared);
        assertThat(result).isEqualTo(ReconciliationLogic.SILENT_ADD);
    }

    // ── 同源判定 ─────────────────────────────────────────────────────────────

    @Test
    void isSameSource_trueWhenSameDirectory() {
        assertThat(ReconciliationLogic.isSameSource(
                "src/service/CaptchaHelper.java",
                "src/service/CaptchaService.java")).isTrue();
    }

    @Test
    void isSameSource_falseWhenDifferentTopLevelDir() {
        assertThat(ReconciliationLogic.isSameSource(
                "src/config/SecurityConfig.java",
                "src/service/CaptchaService.java")).isFalse();
    }

    @Test
    void isSameSource_trueWhenChildDirectory() {
        // actual is in subdirectory of declared's parent
        assertThat(ReconciliationLogic.isSameSource(
                "src/service/impl/CaptchaServiceImpl.java",
                "src/service/CaptchaService.java")).isTrue();
    }

    // ── 路径匹配 ─────────────────────────────────────────────────────────────

    @Test
    void pathMatches_exactMatch() {
        assertThat(ReconciliationLogic.pathMatches("src/Foo.java", "src/Foo.java")).isTrue();
    }

    @Test
    void pathMatches_suffixWithSlash() {
        assertThat(ReconciliationLogic.pathMatches(
                "com/example/service/FooService.java",
                "service/FooService.java")).isTrue();
    }

    @Test
    void pathMatches_className_caseInsensitive() {
        assertThat(ReconciliationLogic.pathMatches(
                "src/main/java/com/FooService.java", "FooService")).isTrue();
    }

    @Test
    void pathMatches_falseWhenNoMatch() {
        assertThat(ReconciliationLogic.pathMatches("src/BarService.java", "FooService")).isFalse();
    }

    @Test
    void pathMatches_falseWhenNullActual() {
        assertThat(ReconciliationLogic.pathMatches(null, "FooService")).isFalse();
    }

    @Test
    void pathMatches_falseWhenNullDeclared() {
        assertThat(ReconciliationLogic.pathMatches("src/Foo.java", null)).isFalse();
    }

    // ── 断言计数 ─────────────────────────────────────────────────────────────

    @Test
    void countAssertions_basicJunitAsserts() {
        String code = "assertEquals(1, result);\n"
                    + "assertTrue(ok);\n"
                    + "assertNotNull(obj);\n";
        assertThat(ReconciliationLogic.countAssertions(code)).isEqualTo(3);
    }

    @Test
    void countAssertions_includesAtTestAnnotation() {
        String code = "@Test\nvoid myTest() {\n  assertEquals(1, 1);\n}\n";
        assertThat(ReconciliationLogic.countAssertions(code)).isEqualTo(2); // @Test + assertEquals
    }

    @Test
    void countAssertions_includesAtDisabled() {
        String code = "@Disabled\n@Test\nvoid myTest() {}\n";
        assertThat(ReconciliationLogic.countAssertions(code)).isEqualTo(2); // @Disabled + @Test
    }

    @Test
    void countAssertions_nullReturnsZero() {
        assertThat(ReconciliationLogic.countAssertions(null)).isZero();
    }

    @Test
    void countAssertions_emptyStringReturnsZero() {
        assertThat(ReconciliationLogic.countAssertions("")).isZero();
    }

    @Test
    void countAssertions_netDecrease_detectable() {
        String oldCode = "@Test\n"
                       + "void t1() {\n"
                       + "  assertEquals(1, x);\n"
                       + "}\n"
                       + "@Test\n"
                       + "void t2() {\n"
                       + "  assertTrue(ok);\n"
                       + "}\n";
        String newCode = "@Test\n"
                       + "void t1() {\n"
                       + "  assertEquals(1, x);\n"
                       + "}\n";
        int oldCount = ReconciliationLogic.countAssertions(oldCode);
        int newCount = ReconciliationLogic.countAssertions(newCode);
        assertThat(oldCount).isGreaterThan(newCount);
    }

    // ── 测试文件检测 ─────────────────────────────────────────────────────────

    @Test
    void isTestFile_trueForJavaTestDir() {
        assertThat(ReconciliationLogic.isTestFile("src/test/java/FooTest.java")).isTrue();
    }

    @Test
    void isTestFile_trueForClassEndingWithTest() {
        assertThat(ReconciliationLogic.isTestFile("src/main/FooTest.java")).isTrue();
    }

    @Test
    void isTestFile_trueForTypeScriptSpec() {
        assertThat(ReconciliationLogic.isTestFile("src/foo.spec.ts")).isTrue();
    }

    @Test
    void isTestFile_falseForNonTestFile() {
        assertThat(ReconciliationLogic.isTestFile("src/main/java/FooService.java")).isFalse();
    }

    @Test
    void isTestFile_falseForNull() {
        assertThat(ReconciliationLogic.isTestFile(null)).isFalse();
    }
}
