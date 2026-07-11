package com.repolens.kernel;

import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.loop.ToolRouter;
import com.repolens.kernel.prompt.KernelPromptBuilder;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.skill.SkillDefinition;
import com.repolens.kernel.skill.SkillRegistry;
import com.repolens.kernel.skill.SkillSlashResolver;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.kernel.tools.SkillTool;
import com.repolens.kernel.tools.WebFetchTool;
import com.repolens.kernel.tools.WebSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 系统 + 联网工具 真实行为 E2E（真 Spring bean，KernelTestApplication 最小上下文）。
 *
 * <p>验的是真机制、不是「方法被调用」：
 * <ol>
 *   <li><b>内置 skill 真从 classpath 载入</b>：SkillRegistry 扫到 deep-research/brainstorm/research 等；</li>
 *   <li><b>项目级 skill 真覆盖/新增</b>：仓库 .claude/skills 下的 SKILL.md 被发现，且能覆盖内置同名；</li>
 *   <li><b>Skill 工具真注入正文</b>：execute(skill=deep-research) 返回该 SKILL.md 的完整 body；未知名给可用清单；</li>
 *   <li><b>系统提示真含 skill 索引</b>：KernelPromptBuilder.build 注入「可用 Skills」name+description；</li>
 *   <li><b>联网默认关（0 出网红线）</b>：WebFetch/WebSearch 默认返回「联网默认关闭」而非真出网；</li>
 *   <li><b>斜杠路由</b>：/skill-name 触发加载该 skill；/cmd 委托 .claude/commands 展开；非斜杠回 null；</li>
 *   <li><b>工具注册</b>：Skill/WebFetch/WebSearch 真进 ToolRouter 目录。</li>
 * </ol>
 */
@SpringBootTest(classes = KernelTestApplication.class)
class SkillSystemE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_skill;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-skill-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
        // 联网默认开（获取知识≠泄漏数据）；本 E2E 需离线确定性，故显式关掉验「显式关闭」门这条路径。
        registry.add("repolens.kernel.web.enabled", () -> "false");
    }

    @Autowired SkillRegistry skillRegistry;
    @Autowired SkillTool skillTool;
    @Autowired SkillSlashResolver slashResolver;
    @Autowired WebFetchTool webFetchTool;
    @Autowired WebSearchTool webSearchTool;
    @Autowired ToolRouter toolRouter;
    @Autowired KernelPromptBuilder promptBuilder;

    private ToolContext ctx(Path repoDir) {
        return new ToolContext(1L, 1L, 1L, repoDir,
                new ShadowHandle(null, repoDir, "DIRECT"), new ReadTracker(),
                PermissionMode.DEFAULT, "test-model");
    }

    @Test
    void builtinSkills_loadedFromClasspath() {
        Map<String, SkillDefinition> all = skillRegistry.effective(null);
        assertTrue(all.containsKey("deep-research"), "应从 classpath 载入内置 deep-research，实际=" + all.keySet());
        assertTrue(all.containsKey("brainstorm"), "应载入 brainstorm");
        assertTrue(all.containsKey("research"), "应载入 research");
        assertTrue(all.size() >= 10, "内置 skill 应有一批，实际=" + all.size());
        // frontmatter 真解析：name/description 非空
        SkillDefinition dr = all.get("deep-research");
        assertEquals("deep-research", dr.name());
        assertNotNull(dr.description());
        assertFalse(dr.body().isBlank(), "deep-research body 非空");
    }

    @Test
    void skillTool_injectsFullBody_andHandlesUnknown() throws IOException {
        Path repo = Files.createTempDirectory("rk-skill-tool");
        String out = skillTool.execute(ctx(repo), Map.of("skill", "deep-research"));
        assertTrue(out.contains("SKILL: deep-research"), "应注入 deep-research 的完整说明，实际前 120 字=" + head(out));
        // body 里的真实内容片段应出现（该 skill 的核心纪律：引用绑定 URL / citation）
        assertTrue(out.toLowerCase().contains("citation") || out.contains("引用") || out.contains("URL"),
                "应包含 deep-research body 的核心内容");

        String unknown = skillTool.execute(ctx(repo), Map.of("skill", "no-such-skill"));
        assertTrue(unknown.contains("未找到"), "未知 skill 应提示未找到，实际=" + head(unknown));
    }

    @Test
    void projectSkill_overridesAndAdds() throws IOException {
        Path repo = Files.createTempDirectory("rk-skill-project");
        Path skillMd = repo.resolve(".claude/skills/my-team-flow/SKILL.md");
        Files.createDirectories(skillMd.getParent());
        Files.writeString(skillMd, "---\nname: my-team-flow\ndescription: 本团队专用发布流程\n---\n\n# My Team Flow\n先跑 lint 再合并。");

        SkillDefinition found = skillRegistry.get(repo, "my-team-flow");
        assertNotNull(found, "应发现项目级 skill my-team-flow");
        assertEquals("project", found.source());
        assertTrue(found.body().contains("先跑 lint"), "项目级 skill body 应真读到");

        // 项目级能覆盖内置同名：造一个和内置同名的项目 skill，get 应返回项目版
        Path override = repo.resolve(".claude/skills/research/SKILL.md");
        Files.createDirectories(override.getParent());
        Files.writeString(override, "---\nname: research\ndescription: 覆盖版\n---\n\n项目自定义 research 正文标记 XYZ。");
        SkillDefinition ov = skillRegistry.get(repo, "research");
        assertEquals("project", ov.source(), "项目级应覆盖内置 research");
        assertTrue(ov.body().contains("XYZ"), "应取项目版正文");
    }

    @Test
    void systemPrompt_containsSkillIndex() throws IOException {
        Path repo = Files.createTempDirectory("rk-skill-prompt");
        String sp = promptBuilder.build(ctx(repo), "demo-repo", "main", PermissionMode.DEFAULT);
        assertTrue(sp.contains("可用 Skills"), "系统提示应含 skill 索引块");
        assertTrue(sp.contains("deep-research"), "索引应列出 deep-research");
        assertTrue(sp.contains("Skill 工具"), "索引应指引用 Skill 工具加载");
    }

    @Test
    void webTools_whenExplicitlyDisabled_returnClearMessage() throws IOException {
        // 本类 props 里显式关了联网（纯离线部署场景）——此时工具应给清晰说明而非真出网。
        // 联网「默认开」（获取外界知识≠泄漏用户数据）；这里验的是被显式关闭时的降级路径。
        Path repo = Files.createTempDirectory("rk-skill-web");
        String fetch = webFetchTool.execute(ctx(repo), Map.of("url", "https://example.com"));
        assertTrue(fetch.contains("联网工具已被显式关闭"), "WebFetch 被显式关闭时应给清晰说明，实际=" + head(fetch));

        String search = webSearchTool.execute(ctx(repo), Map.of("query", "claude code skills"));
        assertTrue(search.contains("联网工具已被显式关闭"), "WebSearch 被显式关闭时应给清晰说明，实际=" + head(search));
    }

    @Test
    void slashRouting_skillTrigger_commandExpand_andPassthrough() throws IOException {
        Path repo = Files.createTempDirectory("rk-skill-slash");

        // /skill-name → 触发加载该 skill
        String trig = slashResolver.route("/brainstorm 做个待办小工具", repo);
        assertNotNull(trig, "已知 skill 的斜杠应被路由");
        assertTrue(trig.contains("brainstorm"), "应指向 brainstorm skill");
        assertTrue(trig.contains("Skill 工具"), "应指引调用 Skill 工具加载");
        assertTrue(trig.contains("待办小工具"), "应带上用户参数");

        // /cmd → 委托 .claude/commands 展开
        Path cmd = repo.resolve(".claude/commands/mycmd.md");
        Files.createDirectories(cmd.getParent());
        Files.writeString(cmd, "自定义命令正文：$ARGUMENTS");
        String expanded = slashResolver.route("/mycmd 参数A", repo);
        assertEquals("自定义命令正文：参数A", expanded, "斜杠命令应展开并替换 $ARGUMENTS");

        // 非斜杠 → null（原样交给 agent）
        assertNull(slashResolver.route("普通问题不是斜杠", repo));
        // 未知斜杠（既非命令也非 skill）→ null
        assertNull(slashResolver.route("/nonexistent-thing foo", repo));
    }

    @Test
    void toolRouter_registersSkillAndWebTools() {
        assertTrue(toolRouter.has("Skill"), "Skill 工具应进注册表");
        assertTrue(toolRouter.has("WebFetch"), "WebFetch 应进注册表");
        assertTrue(toolRouter.has("WebSearch"), "WebSearch 应进注册表");
        // 只读：应可并发、PLAN 模式亦可用
        assertTrue(toolRouter.isReadOnly("Skill"), "Skill 应为只读");
        assertTrue(toolRouter.isReadOnly("WebSearch"), "WebSearch 应为只读");
    }

    private static String head(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
