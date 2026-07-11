package com.repolens.kernel;

import com.repolens.kernel.mcp.LoopbackMcpClientService;
import com.repolens.kernel.mcp.McpClientService;
import com.repolens.kernel.route.LlmCallKind;
import com.repolens.kernel.route.ModelRouter;
import com.repolens.kernel.settings.SettingsResolver;
import com.repolens.kernel.slash.SlashCommandService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M7.3/7.4 真实行为 E2E（真 Spring bean）：
 * <ol>
 *   <li><b>ModelRouter + Settings</b>：无 settings 时 CHORE 回退主模型；repo 下 {@code .claude/settings.json}
 *       配了 {@code chore-model} → CHORE 真走小模型；MAIN_REASONING 恒用主模型；</li>
 *   <li><b>SettingsResolver 三层合并</b>：项目级覆盖内置默认；</li>
 *   <li><b>SlashCommandService</b>：真读 {@code .claude/commands/foo.md} 并把 {@code $ARGUMENTS} 展开；</li>
 *   <li><b>Loopback MCP</b>：真发现 stub 工具并真调用回显（无出网）。</li>
 * </ol>
 */
@SpringBootTest(classes = KernelTestApplication.class)
class M7RouteSettingsE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m7route;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m7route-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    @Autowired ModelRouter modelRouter;
    @Autowired SettingsResolver settingsResolver;
    @Autowired SlashCommandService slashCommandService;
    @Autowired McpClientService mcpClientService;

    @Test
    void chore_routesToSmallModel_whenSettingsConfigured_elseFallsBackToMain() throws Exception {
        Path noSettingsRepo = Files.createTempDirectory("rk-m7route-nosettings");
        // 无 settings：CHORE 回退主模型（行为不变）
        assertEquals("big-main", modelRouter.modelFor(LlmCallKind.CHORE, "big-main", noSettingsRepo),
                "无 chore-model 配置时 CHORE 应回退主模型");
        assertEquals("big-main", modelRouter.modelFor(LlmCallKind.MAIN_REASONING, "big-main", noSettingsRepo),
                "MAIN_REASONING 恒用主模型");

        // 有 .claude/settings.json 配 chore-model：CHORE 走小模型
        Path repo = Files.createTempDirectory("rk-m7route-repo");
        Path settings = repo.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"chore-model\": \"small-chore\"}");

        assertEquals("small-chore", modelRouter.modelFor(LlmCallKind.CHORE, "big-main", repo),
                "配了 chore-model 时 CHORE 应走小模型");
        assertEquals("big-main", modelRouter.modelFor(LlmCallKind.MAIN_REASONING, "big-main", repo),
                "MAIN_REASONING 仍用主模型（不受 chore-model 影响）");
    }

    @Test
    void settingsResolver_projectOverridesBuiltinDefault() throws Exception {
        Path repo = Files.createTempDirectory("rk-m7route-settings");
        // 内置默认 hooks.enabled=true；无文件时应取默认
        assertEquals("true", settingsResolver.get(repo, "hooks.enabled"),
                "无 settings 文件时应取内置默认 hooks.enabled=true");

        // 项目级覆盖默认
        Path settings = repo.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"hooks.enabled\": \"false\", \"chore-model\": \"m\"}");
        Map<String, String> eff = settingsResolver.resolve(repo);
        assertEquals("false", eff.get("hooks.enabled"), "项目级应覆盖内置默认");
        assertEquals("m", eff.get("chore-model"), "项目级新增键应生效");
    }

    @Test
    void slashCommand_expandsMarkdownWithArguments() throws Exception {
        Path repo = Files.createTempDirectory("rk-m7route-slash");
        Path cmd = repo.resolve(".claude/commands/review.md");
        Files.createDirectories(cmd.getParent());
        Files.writeString(cmd, "请审查以下改动并给结论：$ARGUMENTS");

        String expanded = slashCommandService.expand("/review src/A.java 重点看空指针", repo);
        assertEquals("请审查以下改动并给结论：src/A.java 重点看空指针", expanded,
                "slash 命令应展开 markdown 正文并替换 $ARGUMENTS");

        // 非命令 / 未定义命令 → null
        assertNull(slashCommandService.expand("普通文本不是命令", repo));
        assertNull(slashCommandService.expand("/undefined foo", repo));

        // discover 真扫描到定义的命令
        assertTrue(slashCommandService.discover(repo).containsKey("review"),
                "discover 应扫到 review 命令");
    }

    @Test
    void loopbackMcp_discoversAndCallsStubTool_noEgress() {
        List<McpClientService.McpToolInfo> tools = mcpClientService.discoverTools();
        assertFalse(tools.isEmpty(), "loopback MCP 应发现至少一个 stub 工具");
        assertTrue(tools.stream().anyMatch(t -> LoopbackMcpClientService.ECHO_TOOL.equals(t.name())),
                "应发现 loopback echo stub 工具");

        String out = mcpClientService.callTool(LoopbackMcpClientService.ECHO_TOOL, Map.of("k", "v"));
        assertTrue(out.contains("k=v") || out.contains("k") && out.contains("v"),
                "loopback echo 应回显参数，实际=" + out);
    }
}
