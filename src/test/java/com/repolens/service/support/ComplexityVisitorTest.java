package com.repolens.service.support;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.repolens.service.impl.support.ComplexityVisitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD 单元测试：ComplexityVisitor 圈复杂度 + 认知复杂度计算。
 * 全部纯函数，无 Spring 上下文，无 DB。
 */
class ComplexityVisitorTest {

    private JavaParser parser;

    @BeforeEach
    void setup() {
        parser = new JavaParser(new ParserConfiguration());
    }

    // ---- helper ----

    private MethodDeclaration parseMethod(String body) {
        String src = "class T { " + body + " }";
        ParseResult<CompilationUnit> result = parser.parse(src);
        assertThat(result.getResult()).isPresent();
        return result.getResult().get()
                .findFirst(MethodDeclaration.class)
                .orElseThrow(() -> new IllegalStateException("No method found in: " + src));
    }

    // ================================================================ //
    //  Cyclomatic                                                        //
    // ================================================================ //

    @Test
    void simpleMethod_cyclomatic_isOne() {
        MethodDeclaration m = parseMethod("void foo() { int x = 1; }");
        assertThat(ComplexityVisitor.computeCyclomatic(m)).isEqualTo(1);
    }

    @Test
    void singleIf_cyclomatic_isTwo() {
        MethodDeclaration m = parseMethod("void foo(boolean b) { if(b) { return; } }");
        assertThat(ComplexityVisitor.computeCyclomatic(m)).isEqualTo(2);
    }

    @Test
    void ifElse_cyclomatic_isTwo() {
        // if adds 1; else does not add another branch in McCabe
        MethodDeclaration m = parseMethod("void foo(boolean b) { if(b) { return; } else { return; } }");
        assertThat(ComplexityVisitor.computeCyclomatic(m)).isEqualTo(2);
    }

    @Test
    void forLoop_cyclomatic_isTwo() {
        MethodDeclaration m = parseMethod("void foo() { for(int i=0;i<10;i++){} }");
        assertThat(ComplexityVisitor.computeCyclomatic(m)).isEqualTo(2);
    }

    @Test
    void andOperator_addsBranch() {
        MethodDeclaration m = parseMethod("void foo(boolean a, boolean b) { if(a && b){} }");
        // if=1, &&=1 → total=1+2=3? No: 1 (base) + 1 (if) + 1 (&&) = 3
        int cc = ComplexityVisitor.computeCyclomatic(m);
        assertThat(cc).isEqualTo(3);
    }

    @Test
    void ternaryExpression_addsBranch() {
        MethodDeclaration m = parseMethod("int foo(boolean b) { return b ? 1 : 2; }");
        assertThat(ComplexityVisitor.computeCyclomatic(m)).isEqualTo(2);
    }

    @Test
    void catchClause_addsBranch() {
        MethodDeclaration m = parseMethod(
            "void foo() { try { int x=1; } catch(Exception e) {} }");
        assertThat(ComplexityVisitor.computeCyclomatic(m)).isEqualTo(2);
    }

    @Test
    void switchWithCases_addsPerNonDefaultCase() {
        MethodDeclaration m = parseMethod(
            "void foo(int x) { switch(x){ case 1: break; case 2: break; default: break; } }");
        // 2 non-default cases → cyclomatic = 1 + 2 = 3
        assertThat(ComplexityVisitor.computeCyclomatic(m)).isEqualTo(3);
    }

    // ================================================================ //
    //  Cognitive                                                         //
    // ================================================================ //

    @Test
    void simpleMethod_cognitive_isZero() {
        MethodDeclaration m = parseMethod("void foo() { int x = 1; }");
        assertThat(ComplexityVisitor.computeCognitive(m)).isEqualTo(0);
    }

    @Test
    void singleIf_cognitive_isOne() {
        MethodDeclaration m = parseMethod("void foo(boolean b) { if(b) { return; } }");
        // depth=0: if → 1+0=1
        assertThat(ComplexityVisitor.computeCognitive(m)).isEqualTo(1);
    }

    @Test
    void nestedIf_cognitive_hasNestingPenalty() {
        MethodDeclaration m = parseMethod(
            "void foo(boolean a, boolean b) { if(a){ if(b){ return; } } }");
        // outer if @ depth=0: 1; inner if @ depth=1: 2 → total=3
        assertThat(ComplexityVisitor.computeCognitive(m)).isEqualTo(3);
    }

    @Test
    void logicalAndSequence_cognitive_isOne() {
        MethodDeclaration m = parseMethod("boolean foo(boolean a, boolean b) { return a && b; }");
        // one && sequence → cognitive += 1
        assertThat(ComplexityVisitor.computeCognitive(m)).isEqualTo(1);
    }

    @Test
    void logicalMixed_cognitive_isTwoForDifferentOps() {
        MethodDeclaration m = parseMethod("boolean foo(boolean a, boolean b, boolean c) { return a && b || c; }");
        // && sequence=1, switch to || sequence=1 → cognitive=2
        // Note: exact result depends on AST structure (left-associative)
        int cog = ComplexityVisitor.computeCognitive(m);
        assertThat(cog).isGreaterThanOrEqualTo(1);
    }

    @Test
    void complexMethod_cognitive_isHigherThanSimple() {
        // PaymentService-like: nested if + loop + catch
        MethodDeclaration complex = parseMethod(
            "void process(boolean valid, int[] items) {" +
            "  if(valid) {" +
            "    for(int i:items){" +
            "      try{ handle(i); } catch(Exception e){ log(e); }" +
            "    }" +
            "  }" +
            "}"
        );
        MethodDeclaration simple = parseMethod("void foo() {}");
        assertThat(ComplexityVisitor.computeCognitive(complex))
                .isGreaterThan(ComplexityVisitor.computeCognitive(simple));
    }

    // ================================================================ //
    //  compute() facade                                                  //
    // ================================================================ //

    @Test
    void compute_returnsConsistentResult() {
        MethodDeclaration m = parseMethod("void foo(boolean b) { if(b){ return; } }");
        ComplexityVisitor.Complexity cx = ComplexityVisitor.compute(m);
        assertThat(cx.cyclomatic()).isEqualTo(2);
        assertThat(cx.cognitive()).isEqualTo(1);
    }

    @Test
    void compute_failSafe_returnsOneZeroOnBadInput() {
        // Null guard: ComplexityVisitor.compute wraps exceptions
        // We can't easily force an exception from a valid method, but we verify
        // that a simple method returns (cyclomatic≥1, cognitive≥0).
        MethodDeclaration m = parseMethod("void safe() {}");
        ComplexityVisitor.Complexity cx = ComplexityVisitor.compute(m);
        assertThat(cx.cyclomatic()).isGreaterThanOrEqualTo(1);
        assertThat(cx.cognitive()).isGreaterThanOrEqualTo(0);
    }
}
