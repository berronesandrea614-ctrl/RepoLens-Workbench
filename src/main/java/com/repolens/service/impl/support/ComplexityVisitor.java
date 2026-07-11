package com.repolens.service.impl.support;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * JavaParser AST 访问者，计算方法级圈复杂度（cyclomatic）和认知复杂度（cognitive）。
 *
 * <p><b>圈复杂度（cyclomatic）</b> = 1 + 决策点数。决策点：
 * if / else-if / for / foreach / while / do-while / switch-case /
 * catch / 三元表达式 / 二元 && / 二元 ||。
 * 依据：McCabe 1976，与 SonarQube 圈复杂度规则对齐。
 *
 * <p><b>认知复杂度（cognitive）</b>：SonarSource 规则近似实现。
 * 每个控制结构（if/for/while/do/catch/switch）贡献 <code>1 + 当前嵌套深度</code>；
 * 二元逻辑运算符（&&/||）以序列为单位计数（连续相同运算符算 1 次）。
 * 依据：SonarSource 认知复杂度白皮书 v1.4。
 *
 * <p>对外暴露纯静态方法 {@link #compute(MethodDeclaration)}，失败安全：
 * AST 遍历中的任何 RuntimeException 都静默忽略，返回(1,0)兜底。
 */
public final class ComplexityVisitor {

    private ComplexityVisitor() {}

    /** 复杂度结果载体。 */
    public record Complexity(int cyclomatic, int cognitive) {}

    /**
     * 计算给定方法的圈复杂度与认知复杂度。
     * 任何解析异常均静默兜底为 (1, 0)，不抛出。
     *
     * @param method JavaParser MethodDeclaration
     * @return (cyclomatic ≥ 1, cognitive ≥ 0)
     */
    public static Complexity compute(MethodDeclaration method) {
        try {
            int cyclomatic = computeCyclomatic(method);
            int cognitive   = computeCognitive(method);
            return new Complexity(cyclomatic, cognitive);
        } catch (Exception ex) {
            return new Complexity(1, 0);
        }
    }

    // ------------------------------------------------------------------ //
    //  Cyclomatic complexity                                               //
    // ------------------------------------------------------------------ //

    public static int computeCyclomatic(MethodDeclaration method) {
        AtomicInteger count = new AtomicInteger(0);
        method.accept(new CyclomaticVisitor(count), null);
        return 1 + count.get();
    }

    private static class CyclomaticVisitor extends VoidVisitorAdapter<Void> {
        private final AtomicInteger count;

        CyclomaticVisitor(AtomicInteger count) { this.count = count; }

        @Override public void visit(IfStmt n, Void a)          { count.incrementAndGet(); super.visit(n, a); }
        @Override public void visit(ForStmt n, Void a)         { count.incrementAndGet(); super.visit(n, a); }
        @Override public void visit(ForEachStmt n, Void a)     { count.incrementAndGet(); super.visit(n, a); }
        @Override public void visit(WhileStmt n, Void a)       { count.incrementAndGet(); super.visit(n, a); }
        @Override public void visit(DoStmt n, Void a)          { count.incrementAndGet(); super.visit(n, a); }
        @Override public void visit(CatchClause n, Void a)     { count.incrementAndGet(); super.visit(n, a); }
        @Override public void visit(ConditionalExpr n, Void a) { count.incrementAndGet(); super.visit(n, a); }

        @Override
        public void visit(SwitchEntry n, Void a) {
            // Default case does not add a branch
            if (!n.getLabels().isEmpty()) {
                count.incrementAndGet();
            }
            super.visit(n, a);
        }

        @Override
        public void visit(BinaryExpr n, Void a) {
            BinaryExpr.Operator op = n.getOperator();
            if (op == BinaryExpr.Operator.AND || op == BinaryExpr.Operator.OR) {
                count.incrementAndGet();
            }
            super.visit(n, a);
        }
    }

    // ------------------------------------------------------------------ //
    //  Cognitive complexity                                                //
    // ------------------------------------------------------------------ //

    /**
     * Cognitive complexity: recursively descend, tracking nesting depth.
     * Each control structure adds (1 + depth).
     * Logical operators &&/|| contribute 1 per distinct operator sequence.
     */
    public static int computeCognitive(MethodDeclaration method) {
        CognitiveContext ctx = new CognitiveContext();
        visitCognitive(method, ctx, 0);
        return ctx.total;
    }

    private static class CognitiveContext {
        int total = 0;
        BinaryExpr.Operator lastLogicalOp = null; // for operator-sequence dedup

        void addStructural(int depth) { total += 1 + depth; }
        void addLogical(BinaryExpr.Operator op) {
            if (op != lastLogicalOp) { total += 1; }
            lastLogicalOp = op;
        }
        void resetLogical() { lastLogicalOp = null; }
    }

    private static class CognitiveVisitor extends VoidVisitorAdapter<Integer> {
        private final CognitiveContext ctx;

        CognitiveVisitor(CognitiveContext ctx) { this.ctx = ctx; }

        @Override
        public void visit(IfStmt n, Integer depth) {
            ctx.addStructural(depth);
            ctx.resetLogical();
            n.getCondition().accept(this, depth);
            n.getThenStmt().accept(this, depth + 1);
            n.getElseStmt().ifPresent(e -> {
                // else-if does NOT add extra depth (SonarSource rule)
                if (e instanceof IfStmt) { e.accept(this, depth); }
                else { e.accept(this, depth + 1); }
            });
        }

        @Override
        public void visit(ForStmt n, Integer depth) {
            ctx.addStructural(depth);
            ctx.resetLogical();
            super.visit(n, depth + 1);
        }

        @Override
        public void visit(ForEachStmt n, Integer depth) {
            ctx.addStructural(depth);
            ctx.resetLogical();
            super.visit(n, depth + 1);
        }

        @Override
        public void visit(WhileStmt n, Integer depth) {
            ctx.addStructural(depth);
            ctx.resetLogical();
            super.visit(n, depth + 1);
        }

        @Override
        public void visit(DoStmt n, Integer depth) {
            ctx.addStructural(depth);
            ctx.resetLogical();
            super.visit(n, depth + 1);
        }

        @Override
        public void visit(CatchClause n, Integer depth) {
            ctx.addStructural(depth);
            ctx.resetLogical();
            super.visit(n, depth + 1);
        }

        @Override
        public void visit(SwitchStmt n, Integer depth) {
            ctx.addStructural(depth);
            ctx.resetLogical();
            super.visit(n, depth + 1);
        }

        @Override
        public void visit(BinaryExpr n, Integer depth) {
            BinaryExpr.Operator op = n.getOperator();
            if (op == BinaryExpr.Operator.AND || op == BinaryExpr.Operator.OR) {
                ctx.addLogical(op);
            } else {
                ctx.resetLogical();
            }
            // Only recurse operands (not via super, to control logical sequencing)
            n.getLeft().accept(this, depth);
            n.getRight().accept(this, depth);
        }

        @Override
        public void visit(ConditionalExpr n, Integer depth) {
            ctx.addStructural(depth);
            ctx.resetLogical();
            super.visit(n, depth + 1);
        }
    }

    private static void visitCognitive(MethodDeclaration method, CognitiveContext ctx, int depth) {
        method.accept(new CognitiveVisitor(ctx), depth);
    }
}
