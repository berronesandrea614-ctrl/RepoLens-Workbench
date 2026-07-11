package com.repolens.kernel;

import com.repolens.kernel.rules.HierarchicalRulesLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 记忆层级 {@link HierarchicalRulesLoader} 真实行为 E2E（防假实现的硬门）。造一个真 repoDir：
 * <ul>
 *   <li>嵌套 {@code CLAUDE.md}（根）+ 子模块 {@code AGENTS.md}；</li>
 *   <li>根 CLAUDE.md 里有 {@code @import} 指向另一个文件 → 展开；</li>
 *   <li>{@code .claude/rules/} 下两条带 {@code paths} 的作用域规则 + 一条全局规则。</li>
 * </ul>
 * 断言：产出含各层规则、@import 被展开、paths 作用域正确生效（命中的注入、未命中的排除）。
 */
@SpringBootTest(classes = KernelTestApplication.class)
class M6RulesE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m6rules;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m6rules-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    @Autowired HierarchicalRulesLoader loader;

    private Path buildRepo() throws IOException {
        Path repo = Files.createTempDirectory("rk-m6rules-repo");

        // 根 CLAUDE.md，含 @import
        Files.writeString(repo.resolve("CLAUDE.md"),
                "# 根规则\n用中文回答。\n@import ./shared/style.md\n");
        Path shared = repo.resolve("shared");
        Files.createDirectories(shared);
        Files.writeString(shared.resolve("style.md"),
                "IMPORTED-STYLE：提交信息用祈使句。\n");

        // 子模块 AGENTS.md（向下遍历应收到）
        Path sub = repo.resolve("service").resolve("payment");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("AGENTS.md"),
                "SUBMODULE-PAYMENT-RULE：改支付逻辑必须先跑对账测试。\n");

        // .claude/rules：两条带 paths + 一条全局
        Path rulesDir = repo.resolve(".claude").resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("java.md"),
                "---\npaths: [\"src/main/java/**\"]\n---\nSCOPED-JAVA-RULE：Java 文件遵守 Google 风格。\n");
        Files.writeString(rulesDir.resolve("sql.md"),
                "---\npaths: [\"**/*.sql\"]\n---\nSCOPED-SQL-RULE：SQL 迁移必须可回滚。\n");
        Files.writeString(rulesDir.resolve("global.md"),
                "GLOBAL-RULE：任何改动都写变更说明。\n");

        return repo;
    }

    @Test
    void loadsAllLayers_expandsImport_andScopesByPaths() throws Exception {
        Path repo = buildRepo();

        // 当前 run 只触及 java 文件 → java 作用域规则命中，sql 规则不命中
        String block = loader.load(repo, List.of("src/main/java/com/demo/Calc.java"));

        // 各层规则都在
        assertTrue(block.contains("用中文回答"), "根 CLAUDE.md 规则应在");
        assertTrue(block.contains("SUBMODULE-PAYMENT-RULE"), "子模块 AGENTS.md 规则应被向下遍历收进");

        // @import 被展开（引入文件的正文出现在结果里）
        assertTrue(block.contains("IMPORTED-STYLE"), "@import 应被展开，引入文件内容应出现");

        // paths 作用域：java 命中、sql 未命中
        assertTrue(block.contains("SCOPED-JAVA-RULE"), "触及 java 文件 → java 作用域规则应注入");
        assertFalse(block.contains("SCOPED-SQL-RULE"), "未触及 sql 文件 → sql 作用域规则应被排除");

        // 全局规则（无 paths）始终注入
        assertTrue(block.contains("GLOBAL-RULE"), "无 paths 的全局规则应始终注入");
    }

    @Test
    void scopeSwitches_whenContextTouchesSqlInsteadOfJava() throws Exception {
        Path repo = buildRepo();

        // 这次只触及 sql 文件 → sql 命中、java 不命中
        String block = loader.load(repo, List.of("db/migration/V2__add.sql"));

        assertTrue(block.contains("SCOPED-SQL-RULE"), "触及 sql → sql 作用域规则应注入");
        assertFalse(block.contains("SCOPED-JAVA-RULE"), "未触及 java → java 作用域规则应被排除");
        assertTrue(block.contains("GLOBAL-RULE"), "全局规则仍注入");
    }

    @Test
    void emptyScope_onlyGlobalAndHierarchicalRules_noScopedRules() throws Exception {
        Path repo = buildRepo();

        // 无作用域上下文 → 带 paths 的作用域规则都不注入，只有层级规则 + 全局规则
        String block = loader.load(repo, List.of());

        assertTrue(block.contains("用中文回答"), "层级规则仍在");
        assertTrue(block.contains("GLOBAL-RULE"), "全局规则仍在");
        assertFalse(block.contains("SCOPED-JAVA-RULE"), "空作用域不应注入 paths 规则");
        assertFalse(block.contains("SCOPED-SQL-RULE"), "空作用域不应注入 paths 规则");
    }
}
