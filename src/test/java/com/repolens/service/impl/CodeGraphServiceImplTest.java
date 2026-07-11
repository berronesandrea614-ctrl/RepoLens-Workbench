package com.repolens.service.impl;

import com.repolens.domain.entity.CodeDependencyEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.mapper.CodeDependencyMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.ComprehensionDebtFileMapper;
import com.repolens.security.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeGraphServiceImplTest {

    private PermissionService permission;
    private CodeSymbolMapper symbolMapper;
    private CodeDependencyMapper dependencyMapper;
    private CodeFileMapper fileMapper;
    private ComprehensionDebtFileMapper debtFileMapper;
    private CodeGraphServiceImpl service;

    private CodeSymbolEntity sym(long id, long fileId, String cls, String method, String type) {
        CodeSymbolEntity s = new CodeSymbolEntity();
        s.setId(id); s.setFileId(fileId); s.setClassName(cls); s.setMethodName(method);
        s.setStartLine(1); s.setEndLine(2);
        s.setSymbolType(com.repolens.domain.enums.SymbolType.valueOf(type));
        return s;
    }

    private CodeSymbolEntity sym(long id, long fileId, String cls, String method, String type,
                                 String signature, String summary) {
        CodeSymbolEntity s = sym(id, fileId, cls, method, type);
        s.setSignature(signature); s.setSummary(summary);
        return s;
    }

    private CodeDependencyEntity dep(long id, long src, String target, String rel, String conf) {
        CodeDependencyEntity d = new CodeDependencyEntity();
        d.setId(id); d.setRepoId(1L); d.setSourceSymbolId(src);
        d.setTargetSymbolName(target); d.setRelationType(rel); d.setConfidence(new BigDecimal(conf));
        return d;
    }

    @BeforeEach
    void setup() {
        permission = mock(PermissionService.class);
        symbolMapper = mock(CodeSymbolMapper.class);
        dependencyMapper = mock(CodeDependencyMapper.class);
        fileMapper = mock(CodeFileMapper.class);
        debtFileMapper = mock(ComprehensionDebtFileMapper.class);
        // debt injection returns empty by default (tests don't verify debt coloring)
        when(debtFileMapper.selectList(any())).thenReturn(List.of());
        when(permission.checkRepoPermission(anyLong(), anyLong())).thenReturn(true);

        // 图：Controller.getUser -> Service.getUser -> Mapper.selectById
        // symbol 2 带返回类型签名与摘要；symbol 3 签名为空（校验 dataType 兜底）。
        List<CodeSymbolEntity> symbols = List.of(
                sym(1, 10, "com.x.UserController", "getUser", "API"),
                sym(2, 11, "com.x.UserService", "getUser", "METHOD",
                        "User getUser(Long)", "根据 id 查询用户"),
                sym(3, 12, "com.x.UserMapper", "selectById", "METHOD")
        );
        List<CodeDependencyEntity> deps = List.of(
                dep(100, 1, "com.x.UserService#getUser", "CALL", "0.95"),
                dep(101, 2, "com.x.UserMapper#selectById", "CALL", "0.95")
        );
        CodeSymbolEntity root = symbols.get(0);
        when(symbolMapper.selectById(eq(1L))).thenReturn(root);
        when(symbolMapper.selectList(any())).thenReturn(symbols);
        when(symbolMapper.selectBatchIds(any())).thenReturn(symbols);
        when(dependencyMapper.selectList(any())).thenReturn(deps);
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(
                file(10, "com/x/UserController.java"),
                file(11, "com/x/UserService.java"),
                file(12, "com/x/UserMapper.java")
        ));
        service = new CodeGraphServiceImpl(permission, symbolMapper, dependencyMapper, fileMapper, debtFileMapper);
    }

    private CodeFileEntity file(long id, String path) {
        CodeFileEntity f = new CodeFileEntity();
        f.setId(id); f.setFilePath(path);
        return f;
    }

    @Test
    void buildsCalleeChainToDepth2() {
        CodeGraphVO g = service.buildGraph(1L, 1L, 1L, "callees", 2, 0.0);

        assertThat(g.getRootId()).isEqualTo("1");
        // root + Service + Mapper = 3 节点
        assertThat(g.getNodes()).extracting("id").contains("1", "2", "3");
        // 两条边：1->2, 2->3
        assertThat(g.getEdges()).extracting("source").contains("1", "2");
        assertThat(g.getNodes()).filteredOn(n -> n.getId().equals("2"))
                .first().extracting("filePath").isEqualTo("com/x/UserService.java");
        assertThat(g.isTruncated()).isFalse();
    }

    @Test
    void depthOneStopsAtFirstLevel() {
        CodeGraphVO g = service.buildGraph(1L, 1L, 1L, "callees", 1, 0.0);
        assertThat(g.getNodes()).extracting("id").contains("1", "2");
        assertThat(g.getNodes()).extracting("id").doesNotContain("3");
    }

    @Test
    void callersDirectionFindsUpstream() {
        // 反向：Service.getUser 的调用者应含 Controller
        CodeSymbolEntity svc = sym(2, 11, "com.x.UserService", "getUser", "METHOD");
        when(symbolMapper.selectById(eq(2L))).thenReturn(svc);
        CodeGraphVO g = service.buildGraph(1L, 1L, 2L, "callers", 2, 0.0);
        assertThat(g.getNodes()).extracting("id").contains("2", "1");
    }

    @Test
    void minConfidenceFiltersLowEdges() {
        CodeGraphVO g = service.buildGraph(1L, 1L, 1L, "callees", 2, 0.99);
        // 所有边 conf=0.95 < 0.99，root 之外无扩展
        assertThat(g.getNodes()).extracting("id").containsExactly("1");
        assertThat(g.getEdges()).isEmpty();
    }

    /**
     * 高置信度（AST 解析）依赖携带全限定 target 时，即使另一个类有同名方法，也只连到正确的符号，
     * 不因简单方法名过度扇出。
     */
    @Test
    void highConfidenceFqTargetYieldsSingleEdgeNoOverMatch() {
        // symbol 4：另一个类里的同名方法 getUser（诱饵），不应被连到
        List<CodeSymbolEntity> symbols = List.of(
                sym(1, 10, "com.x.UserController", "getUser", "API"),
                sym(2, 11, "com.x.UserService", "getUser", "METHOD"),
                sym(3, 12, "com.x.UserMapper", "selectById", "METHOD"),
                sym(4, 13, "com.y.AdminService", "getUser", "METHOD")
        );
        List<CodeDependencyEntity> deps = List.of(
                dep(100, 1, "com.x.UserService#getUser", "CALL", "0.95"),
                dep(101, 2, "com.x.UserMapper#selectById", "CALL", "0.95")
        );
        when(symbolMapper.selectById(eq(1L))).thenReturn(symbols.get(0));
        when(symbolMapper.selectList(any())).thenReturn(symbols);
        when(symbolMapper.selectBatchIds(any())).thenReturn(symbols);
        when(dependencyMapper.selectList(any())).thenReturn(deps);

        CodeGraphVO g = service.buildGraph(1L, 1L, 1L, "callees", 2, 0.0);

        // 只连到 com.x.UserService(2)，不连诱饵 com.y.AdminService(4)
        assertThat(g.getNodes()).extracting("id").contains("1", "2", "3");
        assertThat(g.getNodes()).extracting("id").doesNotContain("4");
        assertThat(g.getEdges()).extracting("target").doesNotContain("4");
        assertThat(g.getEdges()).extracting("source", "target")
                .contains(org.assertj.core.groups.Tuple.tuple("1", "2"));
    }

    @Test
    void nodesCarryLayerSignatureAndSummary() {
        CodeGraphVO g = service.buildGraph(1L, 1L, 1L, "callees", 2, 0.0);

        // Controller(API) / Service(类名后缀) / Mapper(类名后缀) 分层
        assertThat(g.getNodes()).filteredOn(n -> n.getId().equals("1"))
                .first().extracting("layer").isEqualTo("Controller");
        assertThat(g.getNodes()).filteredOn(n -> n.getId().equals("2"))
                .first().extracting("layer").isEqualTo("Service");
        assertThat(g.getNodes()).filteredOn(n -> n.getId().equals("3"))
                .first().extracting("layer").isEqualTo("Mapper");

        // signature / summary 透传自实体
        assertThat(g.getNodes()).filteredOn(n -> n.getId().equals("2"))
                .first().extracting("signature").isEqualTo("User getUser(Long)");
        assertThat(g.getNodes()).filteredOn(n -> n.getId().equals("2"))
                .first().extracting("summary").isEqualTo("根据 id 查询用户");
    }

    @Test
    void repositorySuffixMapsToMapperLayer() {
        CodeSymbolEntity repo = sym(2, 11, "com.x.UserRepository", "findById", "METHOD");
        when(symbolMapper.selectById(eq(2L))).thenReturn(repo);
        when(symbolMapper.selectList(any())).thenReturn(List.of(repo));
        when(dependencyMapper.selectList(any())).thenReturn(List.of());

        CodeGraphVO g = service.buildGraph(1L, 1L, 2L, "callees", 2, 0.0);
        assertThat(g.getNodes()).filteredOn(n -> n.getId().equals("2"))
                .first().extracting("layer").isEqualTo("Mapper");
    }

    @Test
    void parseReturnType_normalMethod_returnsType() {
        assertThat(CodeGraphServiceImpl.parseReturnType("User getUser(Long)")).isEqualTo("User");
    }

    @Test
    void parseReturnType_withModifiers_stripsThem() {
        assertThat(CodeGraphServiceImpl.parseReturnType("public static User getUser(Long id)"))
                .isEqualTo("User");
    }

    @Test
    void parseReturnType_generic_keptAsOneToken() {
        // 泛型返回类型不能在内部逗号/尖括号处被切断
        assertThat(CodeGraphServiceImpl.parseReturnType("Map<String,User> get()"))
                .isEqualTo("Map<String,User>");
        assertThat(CodeGraphServiceImpl.parseReturnType("public Map<String, User> get(int x)"))
                .isEqualTo("Map<String, User>");
    }

    @Test
    void parseReturnType_constructor_returnsNull() {
        // 构造器没有返回类型：修饰符剥离后为空 => null（而非旧实现返回 "public"）
        assertThat(CodeGraphServiceImpl.parseReturnType("public UserService(X x)")).isNull();
        assertThat(CodeGraphServiceImpl.parseReturnType("UserService()")).isNull();
    }

    @Test
    void parseReturnType_voidMethod_returnsVoid() {
        assertThat(CodeGraphServiceImpl.parseReturnType("void doThing(int x)")).isEqualTo("void");
        assertThat(CodeGraphServiceImpl.parseReturnType("private void doThing()")).isEqualTo("void");
    }

    @Test
    void parseReturnType_blankOrNull_returnsNull() {
        assertThat(CodeGraphServiceImpl.parseReturnType(null)).isNull();
        assertThat(CodeGraphServiceImpl.parseReturnType("   ")).isNull();
    }

    @Test
    void edgeDataTypeFromTargetReturnType() {
        CodeGraphVO g = service.buildGraph(1L, 1L, 1L, "callees", 2, 0.0);

        // 边 1->2 的 target 符号签名 "User getUser(Long)" => dataType "User"
        assertThat(g.getEdges()).filteredOn(e -> e.getSource().equals("1") && e.getTarget().equals("2"))
                .first().extracting("dataType").isEqualTo("User");
        // 边 2->3 的 target 符号无签名 => dataType null
        assertThat(g.getEdges()).filteredOn(e -> e.getSource().equals("2") && e.getTarget().equals("3"))
                .first().extracting("dataType").isNull();
    }

    @Test
    void truncation_retainsHighestConfidenceNeighbors() {
        final int N_LOW = 151; // root + N_LOW low-conf + 1 high-conf = 153 total; 3 get excluded (MAX_NODES=150)
        List<CodeSymbolEntity> symbols = new ArrayList<>();
        symbols.add(sym(1, 1, "com.x.Root", "run", "API"));
        for (long i = 2; i <= N_LOW + 1; i++) {
            symbols.add(sym(i, i, "com.x.Low" + i, "call", "METHOD"));
        }
        // The one high-confidence callee (id=1000, className="com.x.High")
        symbols.add(sym(1000, 1000, "com.x.High", "call", "METHOD"));

        List<CodeDependencyEntity> deps = new ArrayList<>();
        // Low-confidence deps FIRST (insertion order) — would be processed first without sort
        for (long i = 2; i <= N_LOW + 1; i++) {
            deps.add(dep(i * 10, 1, "com.x.Low" + i + "#call", "CALL", "0.1"));
        }
        // High-confidence dep LAST — would be processed last without sort, excluded on truncation
        deps.add(dep(9999, 1, "com.x.High#call", "CALL", "0.95"));

        when(symbolMapper.selectById(eq(1L))).thenReturn(symbols.get(0));
        when(symbolMapper.selectList(any())).thenReturn(symbols);
        when(dependencyMapper.selectList(any())).thenReturn(deps);
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of());

        CodeGraphVO g = service.buildGraph(1L, 1L, 1L, "callees", 1, 0.0);

        assertThat(g.isTruncated()).isTrue();
        // With confidence-sorted adjacency, high-confidence node must be retained
        assertThat(g.getNodes()).extracting("id").contains("1000");
        // Total node count must be exactly MAX_NODES=150
        assertThat(g.getNodes()).hasSize(150);
    }
}
