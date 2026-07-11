package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.CodeDependencyEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.SymbolType;
import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.vo.ParseRepoResultVO;
import com.repolens.mapper.CodeDependencyMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.JavaCodeParseService;
import com.repolens.service.impl.support.ComplexityVisitor;
import com.repolens.service.impl.support.SpringApiAnnotationResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Java 代码解析服务实现，对应主链路第二阶段“结构化解析”。
 * 这一层把 code_file 中的 Java 源文件进一步解析成：
 * 1. class/method/api 等结构化符号；
 * 2. 方法调用关系近似依赖；
 * 3. 后续 chunk 构建所需的行号和签名信息。
 *
 * 这里追求的是工程可用的静态近似，而不是编译器级语义精确分析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JavaCodeParseServiceImpl implements JavaCodeParseService {

    // 调用关系 confidence 分档：SymbolSolver 解析到真实声明类型(高) vs 退回文本匹配(低)。
    private static final BigDecimal CALL_CONFIDENCE_RESOLVED = new BigDecimal("0.95");
    private static final BigDecimal CALL_CONFIDENCE_TEXT = new BigDecimal("0.50");
    private static final BigDecimal IMPLEMENTS_CONFIDENCE = new BigDecimal("0.90");

    private final RepoMapper repoMapper;
    private final CodeFileMapper codeFileMapper;
    private final CodeSymbolMapper codeSymbolMapper;
    private final CodeDependencyMapper codeDependencyMapper;
    private final PermissionService permissionService;
    private final PlatformTransactionManager txManager;
    /** 与 AI 读写一致的目录解析：file:// 本地仓库返回真实项目目录、git 仓库返回 clone。 */
    private final com.repolens.service.support.RepoWorkspaceResolver repoWorkspaceResolver;

    // 不再持有可变的 parser 实例字段：JavaParser + SymbolSolver 均非线程安全，且 SymbolSolver
    // 绑定的是当前 repo 源码根。以前用共享实例字段会导致并发解析两个仓库时相互覆盖 solver，
    // 让 A 仓库用到 B 仓库的解析器，落下错误的高置信度（0.95）调用边、污染调用图。
    // 现改为每次 parseRepository 在方法内构建局部 parser，并逐层作为参数传递，无任何共享可变状态。

    @Value("${repolens.repo-storage-root:./workspace/repos}")
    private String repoStorageRoot;

    @Override
    public ParseRepoResultVO parseRepository(Long repoId, Long userId) {
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            codeDependencyMapper.delete(Wrappers.<CodeDependencyEntity>lambdaQuery()
                    .eq(CodeDependencyEntity::getRepoId, repoId));
            codeSymbolMapper.delete(Wrappers.<CodeSymbolEntity>lambdaQuery()
                    .eq(CodeSymbolEntity::getRepoId, repoId));
        });

        List<CodeFileEntity> javaFiles = codeFileMapper.selectList(Wrappers.<CodeFileEntity>lambdaQuery()
                .eq(CodeFileEntity::getRepoId, repoId)
                .eq(CodeFileEntity::getFileType, "JAVA")
                .orderByAsc(CodeFileEntity::getFilePath));

        // 重新解析前先清掉旧的 symbol/dependency，避免旧分支或旧 commit 数据残留。
        Path repoDirectory = resolveRepoDirectory(repo);
        // 用仓库源码根配置 SymbolSolver，实现 source-only 的真类型推断（跨文件调用/字段类型/接口实现）。
        // 局部变量、不落字段：绑定当前 repo 源码根，天然隔离并发解析，杜绝跨仓库 solver 串用。
        JavaParser javaParser = buildSymbolSolvingParser(repoDirectory);
        ParseStats stats = new ParseStats();
        List<String> failedFiles = new ArrayList<>();

        for (CodeFileEntity javaFile : javaFiles) {
            try {
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    try {
                        parseSingleJavaFile(javaParser, repoId, repoDirectory, javaFile, stats);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                });
                stats.parsedFileCount++;
            } catch (Exception ex) {
                stats.failedFileCount++;
                failedFiles.add(javaFile.getFilePath());
                log.warn("Parse java file failed, repoId={}, filePath={}, reason={}",
                        repoId, javaFile.getFilePath(), ex.getMessage());
            }
        }

        TaskStatus finalStatus = stats.failedFileCount > 0 && stats.parsedFileCount == 0
                ? TaskStatus.FAILED
                : TaskStatus.SUCCESS;
        String errorMsg = null;
        if (!failedFiles.isEmpty()) {
            errorMsg = "Failed files: " + String.join(", ", failedFiles);
            if (errorMsg.length() > 2000) {
                errorMsg = errorMsg.substring(0, 2000);
            }
        }

        return ParseRepoResultVO.builder()
                .repoId(repoId)
                .parsedFileCount(stats.parsedFileCount)
                .failedFileCount(stats.failedFileCount)
                .classCount(stats.classCount)
                .methodCount(stats.methodCount)
                .apiCount(stats.apiCount)
                .dependencyCount(stats.dependencyCount)
                .status(finalStatus)
                .errorMsg(errorMsg)
                .build();
    }

    /**
     * 解析单个 Java 文件并抽取：
     * 1. CLASS 符号；
     * 2. METHOD 符号；
     * 3. Spring API 映射；
     * 4. 静态近似调用依赖。
     */
    private void parseSingleJavaFile(JavaParser javaParser,
                                     Long repoId,
                                     Path repoDirectory,
                                     CodeFileEntity javaFile,
                                     ParseStats stats) throws IOException {
        Path javaFilePath = resolveJavaFilePath(repoDirectory, javaFile.getFilePath());
        if (!Files.exists(javaFilePath) || !Files.isRegularFile(javaFilePath)) {
            throw new IOException("Java file not found: " + javaFilePath);
        }

        ParseResult<CompilationUnit> parseResult = javaParser.parse(javaFilePath);
        if (parseResult.getResult().isEmpty()) {
            String problem = parseResult.getProblems().isEmpty()
                    ? "Unknown JavaParser parse error"
                    : parseResult.getProblems().get(0).toString();
            throw new IOException(problem);
        }

        CompilationUnit compilationUnit = parseResult.getResult().get();
        List<MethodSymbolContext> methodContexts = new ArrayList<>();

        for (TypeDeclaration<?> typeDeclaration : compilationUnit.findAll(TypeDeclaration.class)) {
            if (!isSupportedType(typeDeclaration)) {
                continue;
            }
            String className = resolveClassName(typeDeclaration);

            // class/interface/enum 都会落一条 CLASS 符号，作为后续 chunk 和工具检索的锚点。
            CodeSymbolEntity classSymbol = new CodeSymbolEntity();
            classSymbol.setRepoId(repoId);
            classSymbol.setFileId(javaFile.getId());
            classSymbol.setSymbolType(SymbolType.CLASS);
            classSymbol.setClassName(className);
            classSymbol.setMethodName(null);
            classSymbol.setSignature(buildTypeSignature(typeDeclaration));
            classSymbol.setApiPath(null);
            classSymbol.setHttpMethod(null);
            classSymbol.setStartLine(startLine(typeDeclaration));
            classSymbol.setEndLine(endLine(typeDeclaration));
            classSymbol.setSummary(null);
            codeSymbolMapper.insert(classSymbol);
            stats.classCount++;

            // 记录“类实现接口”关系（IMPLEMENTS），让影响分析能从接口找到实现，解决 Spring 面向接口编程的追踪。
            if (typeDeclaration instanceof ClassOrInterfaceDeclaration coi && !coi.isInterface()) {
                coi.getImplementedTypes().forEach(impl -> {
                    CodeDependencyEntity dep = new CodeDependencyEntity();
                    dep.setRepoId(repoId);
                    dep.setSourceSymbolId(classSymbol.getId());
                    dep.setTargetSymbolName(impl.getNameAsString());
                    dep.setRelationType("IMPLEMENTS");
                    dep.setConfidence(IMPLEMENTS_CONFIDENCE);
                    codeDependencyMapper.insert(dep);
                    stats.dependencyCount++;
                });
            }

            boolean isController = SpringApiAnnotationResolver.isController(typeDeclaration);
            String classLevelPath = isController
                    ? SpringApiAnnotationResolver.resolveClassLevelPath(typeDeclaration)
                    : null;

            for (MethodDeclaration methodDeclaration : typeDeclaration.getMethods()) {
                // 每个方法单独落 METHOD 符号，后续可用于调用关系、影响分析和问答引用。
                CodeSymbolEntity methodSymbol = new CodeSymbolEntity();
                methodSymbol.setRepoId(repoId);
                methodSymbol.setFileId(javaFile.getId());
                methodSymbol.setSymbolType(SymbolType.METHOD);
                methodSymbol.setClassName(className);
                methodSymbol.setMethodName(methodDeclaration.getNameAsString());
                methodSymbol.setSignature(methodDeclaration.getDeclarationAsString(false, false, false));
                methodSymbol.setApiPath(null);
                methodSymbol.setHttpMethod(null);
                methodSymbol.setStartLine(startLine(methodDeclaration));
                methodSymbol.setEndLine(endLine(methodDeclaration));
                methodSymbol.setSummary(null);
                // Feature A: 计算方法级圈/认知复杂度，失败安全（不影响主流程）。
                try {
                    ComplexityVisitor.Complexity cx = ComplexityVisitor.compute(methodDeclaration);
                    methodSymbol.setCyclomatic(cx.cyclomatic());
                    methodSymbol.setCognitive(cx.cognitive());
                } catch (Exception cxEx) {
                    log.debug("ComplexityVisitor failed for method {}, defaulting to 0: {}",
                            methodDeclaration.getNameAsString(), cxEx.getMessage());
                    methodSymbol.setCyclomatic(0);
                    methodSymbol.setCognitive(0);
                }
                codeSymbolMapper.insert(methodSymbol);
                stats.methodCount++;

                methodContexts.add(new MethodSymbolContext(methodDeclaration, methodSymbol.getId()));

                if (isController) {
                    // 控制器方法额外尝试解析 API 映射，便于做接口检索与链路问答。
                    SpringApiAnnotationResolver.ApiMapping apiMapping =
                            SpringApiAnnotationResolver.resolveApiMapping(methodDeclaration, classLevelPath);
                    if (apiMapping != null) {
                        CodeSymbolEntity apiSymbol = new CodeSymbolEntity();
                        apiSymbol.setRepoId(repoId);
                        apiSymbol.setFileId(javaFile.getId());
                        apiSymbol.setSymbolType(SymbolType.API);
                        apiSymbol.setClassName(className);
                        apiSymbol.setMethodName(methodDeclaration.getNameAsString());
                        apiSymbol.setSignature(methodDeclaration.getDeclarationAsString(false, false, false));
                        apiSymbol.setApiPath(apiMapping.apiPath());
                        apiSymbol.setHttpMethod(apiMapping.httpMethod());
                        apiSymbol.setStartLine(startLine(methodDeclaration));
                        apiSymbol.setEndLine(endLine(methodDeclaration));
                        apiSymbol.setSummary(null);
                        codeSymbolMapper.insert(apiSymbol);
                        stats.apiCount++;
                    }
                }
            }
        }

        // 第二轮单独抽方法调用，避免在符号解析时把逻辑搅在一起。
        for (MethodSymbolContext methodContext : methodContexts) {
            for (CallTarget target : collectCallTargets(methodContext.methodDeclaration)) {
                CodeDependencyEntity dependency = new CodeDependencyEntity();
                dependency.setRepoId(repoId);
                dependency.setSourceSymbolId(methodContext.symbolId);
                dependency.setTargetSymbolName(target.target());
                dependency.setRelationType("CALL");
                dependency.setConfidence(target.confidence());
                codeDependencyMapper.insert(dependency);
                stats.dependencyCount++;
            }
        }
    }

    private Path resolveRepoDirectory(RepoEntity repo) {
        // 用与 AI 读写一致的「读目录」：file:// 本地导入仓库返回真实项目目录、git 仓库返回 clone。
        // 修复根因：原来只拼 clone 目录 repoStorageRoot/{id}/{branch}，对 file:// 本地仓库该目录是空的
        // → 解析不到任何符号（code_symbol=0）→ 调用图/时间轴符号/敏感文件 fan-in 全空。改用真实目录后符号可正常抽取。
        Path dir = repoWorkspaceResolver.resolveReadDirectory(repo);
        if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new BizException(ErrorCode.NOT_FOUND, "Local repository not found, import repository first");
        }
        return dir;
    }

    /**
     * JavaParser 只允许读取当前 repo 工作目录内的相对文件，
     * 防止数据库里的 filePath 被拼成仓库外绝对路径。
     */
    private Path resolveJavaFilePath(Path repoDirectory, String relativePath) {
        Path resolvedPath = repoDirectory.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolvedPath.startsWith(repoDirectory)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "File path escapes repository root");
        }
        return resolvedPath;
    }

    private String sanitizeBranchNameForPath(String branchName) {
        return branchName
                .replaceAll("[\\\\/:*?\"<>|]+", "_")
                .replaceAll("\\s+", "_");
    }

    private boolean isSupportedType(TypeDeclaration<?> typeDeclaration) {
        return typeDeclaration instanceof ClassOrInterfaceDeclaration || typeDeclaration instanceof EnumDeclaration;
    }

    /**
     * 用尽可能稳定的文本形式保存类型签名，便于后续展示和检索。
     */
    private String buildTypeSignature(TypeDeclaration<?> typeDeclaration) {
        if (typeDeclaration instanceof ClassOrInterfaceDeclaration classOrInterfaceDeclaration) {
            StringBuilder sb = new StringBuilder();
            if (!classOrInterfaceDeclaration.getModifiers().isEmpty()) {
                sb.append(classOrInterfaceDeclaration.getModifiers().stream()
                        .map(modifier -> modifier.getKeyword().asString())
                        .collect(Collectors.joining(" ")));
                sb.append(' ');
            }
            sb.append(classOrInterfaceDeclaration.isInterface() ? "interface " : "class ");
            sb.append(classOrInterfaceDeclaration.getNameAsString());
            if (!classOrInterfaceDeclaration.getTypeParameters().isEmpty()) {
                sb.append("<");
                sb.append(classOrInterfaceDeclaration.getTypeParameters().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(", ")));
                sb.append(">");
            }
            if (!classOrInterfaceDeclaration.getExtendedTypes().isEmpty()) {
                sb.append(" extends ");
                sb.append(classOrInterfaceDeclaration.getExtendedTypes().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(", ")));
            }
            if (!classOrInterfaceDeclaration.getImplementedTypes().isEmpty()) {
                sb.append(" implements ");
                sb.append(classOrInterfaceDeclaration.getImplementedTypes().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(", ")));
            }
            return sb.toString();
        }
        if (typeDeclaration instanceof EnumDeclaration enumDeclaration) {
            return "enum " + enumDeclaration.getNameAsString();
        }
        return typeDeclaration.getNameAsString();
    }

    /**
     * 生成嵌套类型的完整类名，例如 Outer.Inner。
     */
    private String resolveClassName(TypeDeclaration<?> typeDeclaration) {
        List<String> names = new ArrayList<>();
        names.add(typeDeclaration.getNameAsString());
        Node current = typeDeclaration.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> parentType) {
                names.add(0, parentType.getNameAsString());
            }
            current = current.getParentNode().orElse(null);
        }
        return String.join(".", names);
    }

    private Integer startLine(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(null);
    }

    private Integer endLine(Node node) {
        return node.getRange().map(range -> range.end.line).orElse(null);
    }

    /**
     * 提取方法调用目标，优先用 SymbolSolver 解析到真实声明类型：
     * - 解析成功：target = 全限定类名#方法名（如 com.example.UserService#createUser），confidence 0.95；
     *   这能把 `userService.createUser()` 经 @Autowired 字段类型解析到接口/实现的真实方法，
     *   而不是停留在变量名文本。
     * - 解析失败（无 classpath 的外部库调用、泛型复杂场景等）：退回原文本匹配，confidence 0.50。
     * 解析每次调用都用 try/catch 包住，UnsolvedSymbolException 很常见，绝不让它打断整文件解析。
     */
    private List<CallTarget> collectCallTargets(MethodDeclaration methodDeclaration) {
        java.util.LinkedHashMap<String, CallTarget> targets = new java.util.LinkedHashMap<>();
        methodDeclaration.findAll(MethodCallExpr.class).forEach(methodCallExpr -> {
            String methodName = methodCallExpr.getNameAsString();
            CallTarget resolved = tryResolveCall(methodCallExpr, methodName);
            if (resolved != null) {
                targets.putIfAbsent(resolved.target(), resolved);
                return;
            }
            // 退回文本匹配
            String target = methodName;
            if (methodCallExpr.getScope().isPresent()) {
                String scope = methodCallExpr.getScope().get().toString().trim();
                if (StringUtils.hasText(scope)) {
                    target = scope + "." + methodName;
                }
            }
            if (StringUtils.hasText(target)) {
                targets.putIfAbsent(target, new CallTarget(target, CALL_CONFIDENCE_TEXT));
            }
        });
        return new ArrayList<>(targets.values());
    }

    private CallTarget tryResolveCall(MethodCallExpr methodCallExpr, String methodName) {
        try {
            ResolvedMethodDeclaration decl = methodCallExpr.resolve();
            String qualifiedClass = decl.declaringType().getQualifiedName();
            String target = qualifiedClass + "#" + methodName;
            return new CallTarget(target, CALL_CONFIDENCE_RESOLVED);
        } catch (Throwable ex) {
            // 解析失败极常见（外部库、缺 classpath），静默退回文本匹配。
            return null;
        }
    }

    /**
     * 用仓库源码根构建带 SymbolSolver 的 JavaParser。
     * 类型解析器组合：ReflectionTypeSolver(JDK 内置类型) + 每个 src 根一个 JavaParserTypeSolver。
     * 仅用源码、不需要编译产物——这是对“克隆仓库没有 classpath”的务实取舍。
     */
    private JavaParser buildSymbolSolvingParser(Path repoDirectory) {
        try {
            CombinedTypeSolver typeSolver = new CombinedTypeSolver();
            typeSolver.add(new ReflectionTypeSolver());
            List<Path> srcRoots = findSourceRoots(repoDirectory);
            for (Path root : srcRoots) {
                typeSolver.add(new JavaParserTypeSolver(root.toFile()));
            }
            ParserConfiguration config = new ParserConfiguration()
                    .setSymbolResolver(new JavaSymbolSolver(typeSolver));
            log.info("symbol solver enabled, repoDir={}, srcRoots={}", repoDirectory, srcRoots.size());
            return new JavaParser(config);
        } catch (Exception ex) {
            // 配置失败则退回无解析的 parser，解析仍可进行（只是调用关系退化为文本匹配）。
            log.warn("build symbol solver failed, fallback to plain parser, reason={}", ex.getMessage());
            return new JavaParser();
        }
    }

    /**
     * 找仓库里的 Java 源码根。优先 Maven/Gradle 约定的 src/main/java、src/test/java（支持多模块）；
     * 找不到则用仓库根兜底。
     */
    private List<Path> findSourceRoots(Path repoDirectory) {
        List<Path> roots = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(repoDirectory, 6)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return s.endsWith("/src/main/java") || s.endsWith("/src/test/java");
                    })
                    .forEach(roots::add);
        } catch (IOException ex) {
            log.warn("scan source roots failed, reason={}", ex.getMessage());
        }
        if (roots.isEmpty()) {
            roots.add(repoDirectory);
        }
        return roots;
    }

    /** 一个调用目标及其置信度。 */
    private record CallTarget(String target, BigDecimal confidence) {
    }

    /**
     * 解析统计只在单次 parse 请求内使用，不单独落库。
     */
    private static class ParseStats {
        private int parsedFileCount;
        private int failedFileCount;
        private int classCount;
        private int methodCount;
        private int apiCount;
        private int dependencyCount;
    }

    /**
     * 把方法 AST 节点和落库后的 METHOD 符号 ID 绑定起来，供第二轮依赖抽取使用。
     */
    private record MethodSymbolContext(MethodDeclaration methodDeclaration, Long symbolId) {
    }
}
