package com.repolens.kernel;

import com.repolens.kernel.context.ContextManager;
import com.repolens.kernel.context.LargeOutputStore;
import com.repolens.kernel.loop.Tokenizer;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 上下文 compaction 五层真实行为 E2E（防假实现的硬门）。真 Spring + 真 {@link Tokenizer}（jtokkit BPE）
 * + 真 {@link LargeOutputStore}（真落磁盘）：
 * <ol>
 *   <li><b>L4 全量摘要</b>：喂入逼近窗口的长对话（多轮大 tool_result）→ 触发 L4 →
 *       断言摘要后<b>所有 role=user 消息原文仍在</b>（铁律）、且注入了 8 段模板摘要；</li>
 *   <li><b>L0 大输出转磁盘</b>：超大 tool_result → 真落磁盘、消息里换成预览+ref、磁盘文件真存在；</li>
 *   <li><b>前缀缓存稳定性</b>：未触发 compact 的连续多轮，历史消息（除新增）<b>字节级不变</b>（锁死 KV-cache）。</li>
 * </ol>
 */
@SpringBootTest(classes = KernelTestApplication.class)
class M6CompactionE2ETest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rk_m6compact;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/rk-schema-h2.sql");
        Path shadowRoot = Files.createTempDirectory("rk-m6-root");
        registry.add("repolens.shadow-root", shadowRoot::toString);
    }

    @Autowired ContextManager contextManager;
    @Autowired Tokenizer tokenizer;

    // 大字符串块：制造逼近窗口的长上下文
    private static String bigText(String tag, int repeat) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repeat; i++) {
            sb.append(tag).append(" line ").append(i)
                    .append(": lorem ipsum dolor sit amet consectetur adipiscing elit 中文混排内容第")
                    .append(i).append("段。\n");
        }
        return sb.toString();
    }

    private static LlmMessage user(String c) {
        return LlmMessage.builder().role("user").content(c).build();
    }

    private static LlmMessage assistant(String c) {
        return LlmMessage.builder().role("assistant").content(c).build();
    }

    private static LlmMessage assistantCall(String id, String tool, Map<String, Object> args) {
        return LlmMessage.builder().role("assistant").content("我来调用 " + tool)
                .toolCalls(List.of(ToolCall.builder().id(id).name(tool).arguments(args).build()))
                .build();
    }

    private static LlmMessage tool(String id, String c) {
        return LlmMessage.builder().role("tool").toolCallId(id).content(c).build();
    }

    // ---------------------------------------------------------------- ① L4：保留所有 user 原文

    @Test
    void l4_fullCompaction_preservesAllUserMessagesVerbatim_andInjectsSummary() throws Exception {
        Path repoDir = Files.createTempDirectory("rk-m6-repo");
        ContextManager.State state = new ContextManager.State();

        // 三条独特的 user 原文（铁律：绝不能被摘要吞掉）
        String u1 = "任务：把 Calc.add 改成返回 a+b+10，并加单测。ORIGINAL-USER-INTENT-ALPHA";
        String u2 = "补充要求：不要动 Calc.sub。ORIGINAL-USER-INTENT-BETA";
        String u3 = "再补一条：保留原有注释。ORIGINAL-USER-INTENT-GAMMA";

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(user(u1));
        // 多轮大 tool_result 撑满窗口
        for (int r = 0; r < 8; r++) {
            messages.add(assistantCall("t" + r, "read", Map.of("file_path", "F" + r + ".java")));
            messages.add(tool("t" + r, bigText("READ" + r, 60)));
            if (r == 2) messages.add(user(u2));
            if (r == 5) messages.add(user(u3));
            messages.add(assistant("已读 F" + r + "，" + bigText("THOUGHT" + r, 20)));
        }
        // 尾部保护窗口里再放最近一条 user
        messages.add(user("最近的问题：现在编译过了吗？ORIGINAL-USER-INTENT-DELTA"));

        int usedBefore = tokenizer.estimate(messages);
        // 窗口设到略高于当前用量的 1/0.92，确保 ratio>0.92 触发 L4
        int window = (int) (usedBefore / 0.95);

        boolean restructured = contextManager.compact(messages, "SYS", repoDir,
                ContextManager.Budget.of(window), state);

        assertTrue(restructured, "逼近窗口应触发 L4 重构");

        // 铁律：四条 user 原文全部仍在
        List<String> userContents = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(LlmMessage::getContent).toList();
        for (String marker : List.of("ORIGINAL-USER-INTENT-ALPHA", "ORIGINAL-USER-INTENT-BETA",
                "ORIGINAL-USER-INTENT-GAMMA", "ORIGINAL-USER-INTENT-DELTA")) {
            assertTrue(userContents.stream().anyMatch(c -> c.contains(marker)),
                    "L4 摘要后必须保留 user 原文标记: " + marker);
        }

        // 8 段模板摘要注入了
        assertTrue(messages.stream().anyMatch(m ->
                        m.getContent() != null && m.getContent().contains("上下文摘要")
                                && m.getContent().contains("8. 用户原始意图")),
                "应注入 8 段模板摘要");

        // 真正压小了
        int usedAfter = tokenizer.estimate(messages);
        assertTrue(usedAfter < usedBefore, "压缩后 token 应显著下降: " + usedAfter + " < " + usedBefore);
    }

    // ---------------------------------------------------------------- ② L0：大输出落磁盘

    @Test
    void l0_hugeToolResult_offloadedToDisk_messageBecomesPreviewWithRef() throws Exception {
        Path repoDir = Files.createTempDirectory("rk-m6-repo-l0");
        ContextManager.State state = new ContextManager.State();

        String huge = bigText("HUGE", 800); // 远超 L0 阈值
        assertTrue(tokenizer.estimate(huge) > 2000, "构造的输出应超 L0 阈值");

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(user("跑一下"));
        messages.add(assistantCall("g1", "bash", Map.of("command", "ls")));
        LlmMessage bigTool = tool("g1", huge);
        messages.add(bigTool);

        // window=0 → 只做 L0（不触发五层压缩），验证 L0 独立生效
        contextManager.compact(messages, "SYS", repoDir, ContextManager.Budget.of(0), state);

        // 消息内容变成预览 + ref
        String c = bigTool.getContent();
        assertTrue(c.contains("大输出已转存磁盘 overflowRef="), "tool 消息应换成预览+ref");
        assertTrue(c.contains("--- head ---"), "应保留 head 预览");
        assertTrue(c.length() < huge.length(), "预览应远小于原文");

        // 磁盘文件真存在，且内容 = 原文
        Path storeDir = repoDir.resolve(LargeOutputStore.DIR);
        assertTrue(Files.isDirectory(storeDir), "large-output 目录应被创建: " + storeDir);
        List<Path> files;
        try (var s = Files.list(storeDir)) {
            files = s.toList();
        }
        assertEquals(1, files.size(), "应恰好落一个大输出文件");
        assertEquals(huge, Files.readString(files.get(0)), "磁盘文件内容应等于原始完整输出");
    }

    // ---------------------------------------------------------------- ③ 前缀缓存稳定性

    @Test
    void prefixCache_stable_whenBelowThreshold_historyBytesUnchangedAcrossTurns() throws Exception {
        Path repoDir = Files.createTempDirectory("rk-m6-repo-stable");
        ContextManager.State state = new ContextManager.State();

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(user("小任务，绝不逼近窗口"));
        messages.add(assistant("好的"));
        messages.add(assistantCall("s1", "grep", Map.of("pattern", "x")));
        messages.add(tool("s1", "命中 3 处（短结果，不触发 L0）"));

        // 大窗口 → 永不触发五层压缩
        int bigWindow = 1_000_000;

        // 连续多轮：每轮追加新消息 + compact，断言此前的历史逐条字节不变
        List<String> snapshotBefore = snapshotContents(messages);
        for (int turn = 0; turn < 5; turn++) {
            int prevSize = messages.size();
            contextManager.compact(messages, "SYS", repoDir, ContextManager.Budget.of(bigWindow), state);

            // compact 不得改动已有的任何一条历史（除追加）
            List<String> after = snapshotContents(messages).subList(0, prevSize);
            assertEquals(snapshotBefore, after,
                    "第 " + turn + " 轮 compact 后，历史消息应字节级不变（前缀缓存锁死）");

            // 追加新一轮（模拟 loop 的下一轮增长）
            messages.add(assistantCall("t" + turn, "grep", Map.of("pattern", "y" + turn)));
            messages.add(tool("t" + turn, "命中 " + turn + " 处短结果"));
            snapshotBefore = snapshotContents(messages);
        }

        // 全程未发生任何重构（sealedPrefixLen 未因压缩前移到内容之外），历史里无「已压缩」痕迹
        assertFalse(messages.stream().anyMatch(m ->
                        m.getContent() != null && m.getContent().startsWith("[已压缩")),
                "低于阈值不应发生任何压缩重写");
    }

    private static List<String> snapshotContents(List<LlmMessage> messages) {
        List<String> out = new ArrayList<>();
        for (LlmMessage m : messages) {
            out.add(m.getRole() + "|" + m.getContent() + "|" + m.getToolCallId());
        }
        return out;
    }
}
