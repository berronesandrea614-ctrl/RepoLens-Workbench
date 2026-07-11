package com.repolens.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * 验证 SymbolSolver source-only 真类型推断在真实 Spring 代码上生效：
 * UserController 里 `userService.getUserById(id)` 这个调用，
 * 能被解析到真实声明类型 com.example.demo.service.UserService（而非停留在变量名文本）。
 *
 * 这是 JavaParser 纯文本匹配做不到的——它只能记录 "userService.getUserById"，
 * 不知道 userService 的类型；SymbolSolver 经 @Autowired 字段类型解析到了真实类。
 */
class SymbolSolverParseTest {

    private static final Path SRC_ROOT =
            Paths.get("test-repos/repolens-demo-service/src/main/java").toAbsolutePath().normalize();
    private static final Path CONTROLLER =
            SRC_ROOT.resolve("com/example/demo/controller/UserController.java");

    @Test
    void shouldResolveIntraRepoCallToDeclaringType() throws Exception {
        // 前置：demo 仓库存在（仓库自带的测试夹具）。
        Assertions.assertTrue(Files.exists(CONTROLLER), "demo 控制器不存在: " + CONTROLLER);

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(SRC_ROOT.toFile()));
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver)));

        CompilationUnit cu = parser.parse(CONTROLLER).getResult().orElseThrow();

        // 找到 getUserById 这个调用并解析其声明类型。
        Optional<MethodCallExpr> call = cu.findAll(MethodCallExpr.class).stream()
                .filter(m -> m.getNameAsString().equals("getUserById"))
                .findFirst();
        Assertions.assertTrue(call.isPresent(), "未找到 getUserById 调用");

        ResolvedMethodDeclaration decl = call.get().resolve();
        Assertions.assertEquals("com.example.demo.service.UserService", decl.declaringType().getQualifiedName());
        Assertions.assertEquals("getUserById", decl.getName());
    }

    @Test
    void unresolvedExternalCallShouldNotCrash() throws Exception {
        // 解析失败（如外部库调用）应抛 UnsolvedSymbol，而非让整个流程崩——
        // 生产代码里用 try/catch 退回文本匹配，这里只验证“解析会失败但可捕获”。
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver)));
        // 一个引用未知类型的代码片段
        CompilationUnit cu = parser.parse(
                "class T { void m(com.unknown.Ext e){ e.doStuff(); } }").getResult().orElseThrow();
        List<MethodCallExpr> calls = cu.findAll(MethodCallExpr.class);
        Assertions.assertFalse(calls.isEmpty());
        // resolve 抛异常是预期行为；用 assertThrows 确认可被捕获
        Assertions.assertThrows(Throwable.class, () -> calls.get(0).resolve());
    }
}
