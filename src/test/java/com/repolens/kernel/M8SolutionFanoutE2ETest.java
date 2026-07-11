package com.repolens.kernel;

import com.repolens.kernel.persistence.entity.RkSolutionBranchEntity;
import com.repolens.kernel.persistence.mapper.RkSolutionBranchMapper;
import com.repolens.kernel.solution.SolutionFanoutService;
import com.repolens.kernel.solution.SolutionFanoutService.FanoutSpec;
import com.repolens.kernel.solution.SolutionFanoutService.SolutionStrategy;
import com.repolens.kernel.solution.SolutionViews.SolutionBranchView;
import com.repolens.kernel.solution.SolutionViews.SolutionSetView;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8 方案分支多方案对比 · 真实行为 E2E（防假实现的硬门）。
 *
 * <p>LLM 用「按策略路由」的脚本化桩（确定性驱动），但 fanout 引擎、并行 {@link com.repolens.kernel.loop.AgentLoopExecutor}、
 * 每分支独占影子区隔离、真实改动落盘、行级指标统计、打分推荐、select 合并/丢弃<b>全是真的</b>：
 * <ol>
 *   <li>一个任务并行产 3 个方案分支，各自独立影子区（shadowId 互不相同）、各自把改动 stage 到自己影子区；</li>
 *   <li>指标来自真实 staged 改动：改动面小的分支被推荐（打分基于真行数/文件数，不是预设）；</li>
 *   <li>fanout 全程<b>真目录零改动</b>（隔离成立，未选任何方案时无副作用）；</li>
 *   <li>select 选中一个分支 → 只把它的改动合并回真目录，其余分支 DISCARDED、影子区丢弃。</li>
 * </ol>
 */
@SpringBootTest(classes = KernelTestApplication.class)
class M8SolutionFanoutE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m8solution;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m8-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    @Autowired SolutionFanoutService fanout;
    // 复用 M3 的共享脚本化 LlmClient（唯一 LlmClient bean），用其 marker 路由喂并行分支各自剧本。
    @Autowired M3AgentLoopE2ETest.ScriptableLlmClient llm;
    @Autowired RkSolutionBranchMapper branchMapper;

    private static final String REL = "src/main/java/com/demo/Calc.java";
    private static final String SRC =
            "package com.demo;\n" +
            "public class Calc {\n" +
            "    public int add(int a, int b) {\n" +
            "        return a + b;\n" +
            "    }\n" +
            "}\n";

    private Path newRepo() throws IOException {
        Path repoDir = Files.createTempDirectory("rk-m8-repo");
        Path f = repoDir.resolve(REL);
        Files.createDirectories(f.getParent());
        Files.writeString(f, SRC);
        return repoDir;
    }

    private static LlmResponse toolTurn(ToolCall... calls) {
        return LlmResponse.builder().success(true).finishReason("tool_calls").toolCalls(List.of(calls)).build();
    }

    private static LlmResponse finalTurn(String text) {
        return LlmResponse.builder().success(true).finishReason("stop").content(text).build();
    }

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return ToolCall.builder().id(id).name(name).arguments(args).build();
    }

    private void loadScripts() {
        llm.reset();
        // 分支A「最小」：读→小改一行→收尾（改动面最小、churn 最小 → 应被推荐）
        llm.route("STRAT_A",
                toolTurn(call("a1", "read", Map.of("file_path", REL))),
                toolTurn(call("a2", "edit", Map.of(
                        "file_path", REL, "old_string", "return a + b;", "new_string", "return a + b + 1;"))),
                finalTurn("方案A done：最小改动"));
        // 分支B「大改」：新建一个文件（改动面=2）+ 改 Calc（churn 更大 → 不被推荐）
        llm.route("STRAT_B",
                toolTurn(call("b1", "write", Map.of(
                        "file_path", "src/main/java/com/demo/RateLimiter.java",
                        "content", "package com.demo;\npublic class RateLimiter {\n    public boolean allow() { return true; }\n}\n"))),
                toolTurn(call("b2", "read", Map.of("file_path", REL))),
                toolTurn(call("b3", "edit", Map.of(
                        "file_path", REL, "old_string", "return a + b;",
                        "new_string", "int limited = a + b;\n        return limited > 100 ? 100 : limited;"))),
                finalTurn("方案B done：装饰器 + 限流类"));
        // 分支C「中等」：读→一行换两行（churn 比 A 大、文件数与 A 同 → 次于 A）
        llm.route("STRAT_C",
                toolTurn(call("c1", "read", Map.of("file_path", REL))),
                toolTurn(call("c2", "edit", Map.of(
                        "file_path", REL, "old_string", "return a + b;",
                        "new_string", "int s = a + b;\n        return s;"))),
                finalTurn("方案C done：中等改动"));
    }

    private FanoutSpec spec(Path repoDir) {
        return new FanoutSpec(900L, 900L, repoDir, "给 add 加个限流/上限保护",
                "你是编码 agent。", "test-model",
                List.of(new SolutionStrategy("最小改动", "STRAT_A 用最小侵入方式"),
                        new SolutionStrategy("装饰器大改", "STRAT_B 引入独立限流类装饰"),
                        new SolutionStrategy("中等内联", "STRAT_C 内联一个中间变量")),
                0, 0);
    }

    @Test
    void fanout_parallelBranches_isolatedShadows_realMetrics_recommendSmallest_realDirUntouched() throws Exception {
        Path repoDir = newRepo();
        loadScripts();

        SolutionSetView set = fanout.fanout(spec(repoDir));

        assertEquals("READY", set.status(), "fanout 完成应为 READY");
        assertEquals(3, set.branches().size(), "应产出 3 个方案分支");

        // 每分支都真跑出了改动 + 各自独立影子区（shadowId 互不相同）
        List<RkSolutionBranchEntity> rows = branchMapper.selectList(
                new LambdaQueryWrapper<RkSolutionBranchEntity>().eq(RkSolutionBranchEntity::getSetId, set.setId()));
        assertEquals(3, rows.size());
        long distinctShadows = rows.stream().map(RkSolutionBranchEntity::getShadowId).distinct().count();
        assertEquals(3, distinctShadows, "3 个分支必须各占一个独立影子区（隔离基石）");
        for (RkSolutionBranchEntity b : rows) {
            assertEquals("STAGED", b.getStatus());
            assertTrue(b.getFilesChanged() >= 1, "每分支应真有改动");
            assertEquals("REAL", b.getMetricKind());
        }

        SolutionBranchView a = byLabel(set, "最小改动");
        SolutionBranchView big = byLabel(set, "装饰器大改");
        assertEquals(1, a.filesChanged(), "方案A 改 1 个文件");
        assertEquals(2, big.filesChanged(), "方案B 改 2 个文件（新建限流类 + 改 Calc）");
        assertTrue(a.linesAdded() >= 1 && a.linesRemoved() >= 1, "方案A 行级增删应真统计出来");

        // 推荐 = 改动面/churn 最小的方案A（打分基于真实指标，非预设）
        assertNotNull(set.recommendedBranchId(), "应打出推荐分支");
        assertEquals(a.branchId(), set.recommendedBranchId(), "改动最小的方案A 应被推荐");
        assertTrue(a.recommended());
        assertFalse(big.recommended());

        // fanout 全程真目录零改动（隔离成立）：Calc 仍是原样，限流类未出现在真目录
        assertEquals(SRC, Files.readString(repoDir.resolve(REL)), "fanout 不应改真目录");
        assertFalse(Files.exists(repoDir.resolve("src/main/java/com/demo/RateLimiter.java")),
                "未选方案的新建文件不应落真目录");
    }

    @Test
    void select_appliesChosenBranch_discardsOthers_realDirReflectsChoice() throws Exception {
        Path repoDir = newRepo();
        loadScripts();
        SolutionSetView set = fanout.fanout(spec(repoDir));

        // 用户不选推荐的A，偏要选「装饰器大改」B → 只落地B
        SolutionBranchView big = byLabel(set, "装饰器大改");
        SolutionSetView after = fanout.select(set.setId(), big.branchId());

        assertEquals("SELECTED", after.status());
        assertEquals(big.branchId(), after.selectedBranchId());

        // 真目录现在真的有了B的改动：限流类落地 + Calc 被B改
        assertTrue(Files.exists(repoDir.resolve("src/main/java/com/demo/RateLimiter.java")),
                "选中B → 其新建限流类应合并回真目录");
        String calc = Files.readString(repoDir.resolve(REL));
        assertTrue(calc.contains("limited > 100"), "选中B → Calc 应是B的改动");
        assertFalse(calc.contains("a + b + 1;"), "不应混入未选中A的改动");

        // 其余分支 DISCARDED，选中分支 SELECTED
        for (SolutionBranchView b : after.branches()) {
            if (b.branchId().equals(big.branchId())) {
                assertEquals("SELECTED", b.status());
            } else {
                assertEquals("DISCARDED", b.status(), "未选中分支应 DISCARDED");
            }
        }
    }

    private static SolutionBranchView byLabel(SolutionSetView set, String label) {
        return set.branches().stream().filter(b -> label.equals(b.label())).findFirst()
                .orElseThrow(() -> new AssertionError("找不到方案: " + label));
    }
}
