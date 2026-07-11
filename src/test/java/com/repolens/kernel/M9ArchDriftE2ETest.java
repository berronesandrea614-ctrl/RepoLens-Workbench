package com.repolens.kernel;

import com.repolens.kernel.drift.ArchDriftDetector;
import com.repolens.kernel.drift.DriftViews.DriftItem;
import com.repolens.kernel.drift.DriftViews.DriftReport;
import com.repolens.kernel.drift.DriftViews.EvolutionTimeline;
import com.repolens.kernel.drift.DriftViews.SnapshotView;
import com.repolens.kernel.drift.spi.CallGraphSnapshotProvider;
import com.repolens.kernel.drift.spi.CallGraphView;
import com.repolens.kernel.drift.spi.DependencyEdge;
import com.repolens.kernel.drift.spi.FileFingerprint;
import com.repolens.kernel.drift.spi.SymbolNode;
import com.repolens.kernel.persistence.entity.RkArchDriftEntity;
import com.repolens.kernel.persistence.mapper.RkArchDriftMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.kernel.drift.GraphSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M9 架构漂移时间维度 · 真实行为 E2E（防假实现的硬门）。
 *
 * <p>调用图数据用可控 fake provider（隔壁窗口的真实现另接），但快照捕获、语义稳定 key 归一、确定性图哈希 +
 * prev_hash 审计链、跨快照结构漂移比对、会话/commit 归因<b>全是真的（真 Spring + 真 H2 + 真 SQL）</b>：
 * <ol>
 *   <li>抓两份快照 → 图哈希不同、后一份 prev_hash 严格等于前一份 graph_hash（审计链成立）；</li>
 *   <li>跨快照检出符号/依赖/文件的增删改，每处归因到引入它的会话与 commit；</li>
 *   <li><b>关键诚实性</b>：重索引让符号自增 id 全变、但语义不变时，图哈希不变、<b>零假漂移</b>（认语义 key 不认 id）；</li>
 *   <li>沿时间回放演化：稳定期两快照哈希相同 → changed=false（架构稳定信号）。</li>
 * </ol>
 */
@SpringBootTest(classes = {KernelTestApplication.class, M9ArchDriftE2ETest.TestCfg.class})
class M9ArchDriftE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m9drift;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m9-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    /** 可控的调用图只读端口 fake：测试逐次 setGraph 模拟「当前态」随时间变化。 */
    static class FakeCallGraphProvider implements CallGraphSnapshotProvider {
        private volatile CallGraphView current;

        void setGraph(CallGraphView v) {
            this.current = v;
        }

        @Override
        public CallGraphView currentGraph(long repoId) {
            return current != null ? current : CallGraphView.empty(repoId);
        }
    }

    @TestConfiguration
    static class TestCfg {
        @Bean
        FakeCallGraphProvider fakeCallGraphProvider() {
            return new FakeCallGraphProvider();
        }
    }

    @Autowired GraphSnapshotService snapshots;
    @Autowired ArchDriftDetector detector;
    @Autowired FakeCallGraphProvider provider;
    @Autowired RkArchDriftMapper driftMapper;

    private static final String CALC = "src/main/java/com/demo/Calc.java";
    private static final String NEWF = "src/main/java/com/demo/New.java";

    private static SymbolNode sym(long id, String cls, String method, int s, int e) {
        return new SymbolNode(id, "java", "METHOD", cls, method, "(int,int)", CALC, s, e);
    }

    private static DependencyEdge edge(long srcId, String target) {
        return new DependencyEdge(srcId, target, "CALL", 0.9);
    }

    /** v1：Calc#add / Calc#sub；add→Helper.help；Calc.java=hashV1@commit-1。 */
    private CallGraphView v1() {
        return new CallGraphView(7L,
                List.of(sym(1, "com.demo.Calc", "add", 3, 5), sym(2, "com.demo.Calc", "sub", 6, 8)),
                List.of(edge(1, "com.demo.Helper.help")),
                List.of(new FileFingerprint(CALC, "hashV1", "commit-1", 8)));
    }

    /** v2：add 保留(但 id 换)、sub 删、mul 增；边 add→Helper 删、mul→RateLimiter 增；
     *  Calc.java 内容变(hashV2@commit-2)、New.java 增。 */
    private CallGraphView v2() {
        return new CallGraphView(7L,
                List.of(sym(10, "com.demo.Calc", "add", 3, 5), sym(11, "com.demo.Calc", "mul", 9, 11)),
                List.of(edge(11, "com.demo.RateLimiter.check")),
                List.of(new FileFingerprint(CALC, "hashV2", "commit-2", 11),
                        new FileFingerprint(NEWF, "hashNew", "commit-2", 4)));
    }

    /** v2 语义等价但符号 id 全变（模拟重索引 id churn）——图哈希应不变、不应产生假漂移。 */
    private CallGraphView v2ReindexedSameSemantics() {
        return new CallGraphView(7L,
                List.of(sym(20, "com.demo.Calc", "add", 3, 5), sym(21, "com.demo.Calc", "mul", 9, 11)),
                List.of(edge(21, "com.demo.RateLimiter.check")),
                List.of(new FileFingerprint(CALC, "hashV2", "commit-2", 11),
                        new FileFingerprint(NEWF, "hashNew", "commit-2", 4)));
    }

    @Test
    void capture_hashChain_then_detectDrift_withSessionAndCommitAttribution() {
        provider.setGraph(v1());
        SnapshotView s1 = snapshots.capture(7L, 1L, "v1");
        provider.setGraph(v2());
        SnapshotView s2 = snapshots.capture(7L, 2L, "v2");

        // 图哈希审计链：seq 递增 + 后一份 prev_hash 严格等于前一份 graph_hash
        assertEquals(1, s1.seq());
        assertEquals(2, s2.seq());
        assertNotNull(s1.graphHash());
        assertNotEquals(s1.graphHash(), s2.graphHash(), "图变了，图哈希必须变");
        assertEquals(s1.graphHash(), s2.prevHash(), "后一快照 prev_hash 应串上前一份 graph_hash（审计链）");

        DriftReport report = detector.detect(s1.snapshotId(), s2.snapshotId());
        assertTrue(report.changed());
        assertEquals(6, report.drifts().size(), "应检出 6 处漂移");

        // 各类漂移都在，且都归因到引入它的会话 2
        assertTrue(report.drifts().stream().allMatch(d -> Long.valueOf(2L).equals(d.attributedSessionId())),
                "每处漂移应归因到 to 快照的会话 2");
        DriftItem mulAdded = pick(report, "NODE_ADDED");
        assertTrue(mulAdded.entityDesc().contains("Calc#mul"), "新增符号应是 Calc#mul，实际: " + mulAdded.entityDesc());
        assertEquals(CALC, mulAdded.filePath(), "新增符号所在文件应是 Calc.java");
        assertEquals("java", mulAdded.language());
        assertEquals("commit-2", mulAdded.attributedCommit(), "新增符号应归因到其所在文件的 commit-2");
        assertEquals("com.demo.Calc#sub", classHash(pick(report, "NODE_REMOVED").entityDesc()), "删除的应是 Calc#sub");

        // 关键：add 语义未变(虽 id 从 1→10)，不应作为符号增删出现（认语义 key 不认 id）。
        // 注：add 会作为被删边的「源」出现在 EDGE_REMOVED 描述里，那是对的（边确实变了），故只查 NODE_* 漂移。
        assertFalse(report.drifts().stream()
                        .filter(d -> d.driftType().startsWith("NODE_"))
                        .anyMatch(d -> d.entityDesc() != null && d.entityDesc().contains("Calc#add")),
                "add 语义未变（只是 id 变），不该报符号增删漂移——认语义 key 不认 id");

        // 文件漂移的 commit 归因来自该文件的 last_commit_id
        DriftItem calcChanged = report.drifts().stream()
                .filter(d -> "FILE_CHANGED".equals(d.driftType())).findFirst().orElseThrow();
        assertEquals("commit-2", calcChanged.attributedCommit(), "Calc.java 变更应归因到 commit-2");
        assertTrue(report.drifts().stream().anyMatch(d -> "FILE_ADDED".equals(d.driftType())
                && NEWF.equals(d.filePath())), "New.java 应报 FILE_ADDED");
        assertTrue(report.drifts().stream().anyMatch(d -> "EDGE_ADDED".equals(d.driftType())), "应有 EDGE_ADDED");
        assertTrue(report.drifts().stream().anyMatch(d -> "EDGE_REMOVED".equals(d.driftType())), "应有 EDGE_REMOVED");

        // 落库 + 幂等：再比一次不产生重复行
        detector.detect(s1.snapshotId(), s2.snapshotId());
        long rows = driftMapper.selectCount(new LambdaQueryWrapper<RkArchDriftEntity>()
                .eq(RkArchDriftEntity::getFromSnapshotId, s1.snapshotId())
                .eq(RkArchDriftEntity::getToSnapshotId, s2.snapshotId()));
        assertEquals(6, rows, "漂移应落库且对同一 (from,to) 幂等（不重复）");
    }

    @Test
    void reindexIdChurn_sameSemantics_zeroFalseDrift_and_stableEvolution() {
        provider.setGraph(v1());
        SnapshotView s1 = snapshots.capture(8L, 1L, "v1");
        provider.setGraph(v2());
        SnapshotView s2 = snapshots.capture(8L, 2L, "v2");
        // 第三次：v2 语义等价但符号 id 全变（重索引）
        provider.setGraph(v2ReindexedSameSemantics());
        SnapshotView s3 = snapshots.capture(8L, 3L, "v2-reindexed");

        // id 全变但语义不变 → 图哈希与 s2 完全相同（确定性、只认语义）
        assertEquals(s2.graphHash(), s3.graphHash(), "重索引 id churn 不该改变图哈希");

        DriftReport stable = detector.detect(s2.snapshotId(), s3.snapshotId());
        assertFalse(stable.changed(), "语义未变应报架构稳定");
        assertEquals(0, stable.drifts().size(), "重索引不该产生任何假漂移");

        // 沿时间回放演化：3 份快照 → 2 段转移；第 1 段有漂移、第 2 段稳定
        EvolutionTimeline timeline = detector.evolution(8L);
        assertEquals(3, timeline.snapshots().size());
        assertEquals(2, timeline.transitions().size());
        assertTrue(timeline.transitions().get(0).changed(), "v1→v2 段应有漂移");
        assertFalse(timeline.transitions().get(1).changed(), "v2→v2(reindex) 段应稳定");
        // 快照按 seq 有序
        List<SnapshotView> snaps = timeline.snapshots();
        assertEquals(1, snaps.get(0).seq());
        assertEquals(3, snaps.get(2).seq());
    }

    private static DriftItem pick(DriftReport r, String type) {
        return r.drifts().stream().filter(d -> type.equals(d.driftType())).findFirst()
                .orElseThrow(() -> new AssertionError("缺漂移类型: " + type));
    }

    /** 从 entityDesc 里取「类#方法」前缀（去掉签名）用于断言。 */
    private static String classHash(String desc) {
        if (desc == null) {
            return null;
        }
        int p = desc.indexOf('(');
        return p >= 0 ? desc.substring(0, p) : desc;
    }
}
