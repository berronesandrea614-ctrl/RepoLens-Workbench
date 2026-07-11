package com.repolens.kernel.context;

import com.repolens.kernel.loop.Tokenizer;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文 compaction 五层 + session-memory（M6 核心）。在 {@link com.repolens.kernel.loop.AgentLoopExecutor}
 * 每轮 LLM 调用<b>之前</b>按需压缩消息历史，把「无界增长的对话」压回窗口内，同时保护关键信息与前缀缓存。
 *
 * <h3>五层（由轻到重，按需触发）</h3>
 * <ul>
 *   <li><b>L0 大输出转磁盘</b>：单条 tool_result 超 {@code L0_TOOL_RESULT_TOKENS} → 完整原文落磁盘
 *       ({@link LargeOutputStore})，消息里换成 head/tail 预览 + overflowRef。<b>每轮对新增 tool 消息即时处理</b>。</li>
 *   <li><b>L1 清旧 tool_result</b>：较旧的 tool 消息 content 截断为摘要占位。<b>入列即定型</b>——
 *       只处理「已滑出保护窗口且尚未定型」的历史，定型后不再回改（保护前缀缓存 KV-cache）。</li>
 *   <li><b>L2 时间衰减</b>：更旧的整段（含 assistant thought）压成一行摘要，随阈值触发、非每轮。</li>
 *   <li><b>L3 session-memory 笔记</b>：把关键事实（改了哪些文件/验证结果/决定）沉淀成一条会话笔记，
 *       压缩时保留、随对话滚动更新。</li>
 *   <li><b>L4 全量摘要</b>：usage 接近窗口（&gt; {@code L4_TRIGGER_RATIO}）时一次性大压缩，套 8 段模板，
 *       <b>显式保留所有 role=user 消息原文</b>（铁律）。</li>
 * </ul>
 *
 * <h3>前缀缓存稳定性（KV-cache 保护）</h3>
 * 未触发任何压缩的连续多轮，{@link #compact} 对历史消息（除新增）<b>字节级不变</b>——它只可能
 * 追加、或在触发阈值时一次性重构，绝不每轮回头改写已定型的历史。定型边界由 {@code sealedPrefixLen}
 * 记录：低于该下标的消息永不再被本管理器触碰。
 */
@Component("kernelContextManager")
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    /** 单条 tool_result 超此 token 即 L0 落磁盘。 */
    static final int L0_TOOL_RESULT_TOKENS = 2000;
    /** L0 预览保留的 head/tail 字符数。 */
    static final int L0_PREVIEW_HEAD = 400;
    static final int L0_PREVIEW_TAIL = 200;

    /** 最近 N 条消息属于「保护窗口」，L1/L2 不动（保留近处细节）。 */
    static final int PROTECT_TAIL = 8;
    /** L1 截断后 tool_result 占位保留的字符数。 */
    static final int L1_KEEP = 240;

    /** usage 占窗口比 &gt; 此值触发 L4 全量摘要。 */
    static final double L4_TRIGGER_RATIO = 0.92;
    /** usage 占窗口比 &gt; 此值触发 L1/L2/L3 中量压缩。 */
    static final double MID_TRIGGER_RATIO = 0.75;

    private final Tokenizer tokenizer;
    private final LargeOutputStore largeOutputStore;

    public ContextManager(Tokenizer tokenizer, LargeOutputStore largeOutputStore) {
        this.tokenizer = tokenizer;
        this.largeOutputStore = largeOutputStore;
    }

    /**
     * per-run 的 compaction 状态（非 Spring bean，随 run 生命周期）。承载定型边界与 session-memory，
     * 使前缀缓存稳定性与记忆沉淀跨轮连续。
     */
    public static final class State {
        /** 已定型前缀长度：下标 &lt; 此值的消息永不被再改（KV-cache 锚）。 */
        int sealedPrefixLen = 0;
        /** L0 已处理过的消息（按身份），避免重复落盘。 */
        final java.util.Set<LlmMessage> l0Done = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        /** L3 会话笔记（关键事实滚动累积）。 */
        final SessionMemory memory = new SessionMemory();
        /** 已发生过一次 L4 全量摘要的下标水位（低于此的都被摘要吞并/保留过）。 */
        int lastFullCompactUpto = 0;

        public SessionMemory memory() {
            return memory;
        }
    }

    /** 窗口预算：模型上下文窗口 token 数（≤0 视为不限，不触发压缩）。 */
    public record Budget(int contextWindowTokens) {
        public static Budget of(int window) {
            return new Budget(window);
        }
    }

    /**
     * 主入口：在一次 LLM 调用前，按需对 {@code messages} 做 compaction。<b>就地修改</b>该 list。
     *
     * @param messages 当前完整消息历史（user/assistant/tool，不含 system）
     * @param systemPrompt 本轮 system prompt（计入 usage）
     * @param repoDir  真目录（L0 落磁盘锚点；可空→临时目录）
     * @param budget   窗口预算
     * @param state    per-run 状态
     * @return 是否发生了会打断前缀缓存的重构（L1/L2/L4）；纯 L0 或未压缩返回 false 不算打断历史前缀
     */
    public boolean compact(List<LlmMessage> messages, String systemPrompt, Path repoDir,
                           Budget budget, State state) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        // L0：始终对新增的大 tool_result 落盘（即时、只碰未处理过的，不动已定型前缀）
        applyL0(messages, repoDir, state);
        // L3：从最近若干消息里抽取关键事实，滚动进 session-memory（不改历史，只沉淀）
        state.memory.observe(messages);

        int window = budget == null ? 0 : budget.contextWindowTokens();
        if (window <= 0) {
            // 不限窗口：仅 L0 生效，历史前缀保持字节稳定
            reseal(messages, state);
            return false;
        }

        int used = tokenizer.estimate(systemPrompt) + tokenizer.estimate(messages);
        double ratio = (double) used / window;

        boolean restructured = false;
        if (ratio > L4_TRIGGER_RATIO) {
            restructured = applyL4FullCompaction(messages, state) || restructured;
        } else if (ratio > MID_TRIGGER_RATIO) {
            restructured = applyL1L2(messages, state) || restructured;
        }
        reseal(messages, state);
        return restructured;
    }

    // ---------------------------------------------------------------- L0

    /** L0：把新增的、超阈值的单条 tool_result 落磁盘，消息内容换预览+ref。只碰未处理过的消息。 */
    private void applyL0(List<LlmMessage> messages, Path repoDir, State state) {
        for (LlmMessage m : messages) {
            if (!"tool".equals(m.getRole())) {
                continue;
            }
            if (state.l0Done.contains(m)) {
                continue;
            }
            String content = m.getContent();
            if (content == null || content.isEmpty()) {
                state.l0Done.add(m);
                continue;
            }
            if (tokenizer.estimate(content) <= L0_TOOL_RESULT_TOKENS) {
                state.l0Done.add(m);
                continue;
            }
            LargeOutputStore.Stored stored = largeOutputStore.store(repoDir, content);
            m.setContent(previewWithRef(content, stored));
            state.l0Done.add(m);
            log.debug("[L0] tool_result 落磁盘 ref={} 原长={}", stored.hash(), content.length());
        }
    }

    private String previewWithRef(String content, LargeOutputStore.Stored stored) {
        int len = content.length();
        String head = content.substring(0, Math.min(L0_PREVIEW_HEAD, len));
        String tail = len > L0_PREVIEW_HEAD + L0_PREVIEW_TAIL
                ? content.substring(len - L0_PREVIEW_TAIL)
                : "";
        StringBuilder sb = new StringBuilder();
        sb.append("[大输出已转存磁盘 overflowRef=").append(stored.hash())
                .append(" path=").append(stored.file()).append(" 原文 ").append(len).append(" 字符]\n");
        sb.append("--- head ---\n").append(head);
        if (!tail.isEmpty()) {
            sb.append("\n...（中间 ").append(len - L0_PREVIEW_HEAD - L0_PREVIEW_TAIL).append(" 字符已略，按 ref 从磁盘取回）...\n");
            sb.append("--- tail ---\n").append(tail);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- L1 + L2

    /**
     * L1/L2 中量压缩：对「已滑出保护窗口 且 尚未定型」的历史做定型压缩。
     * <ul>
     *   <li>L1：旧 tool_result 内容截断为占位摘要（保留头部 {@code L1_KEEP} 字符）；</li>
     *   <li>L2：更旧的 assistant「思考文」压成一行（保留意图、去冗长）。</li>
     * </ul>
     * 只处理 [sealedPrefixLen, size-PROTECT_TAIL) 区间——保护窗口内与已定型前缀都不动。
     * 处理完把这段并入定型前缀（sealedPrefixLen 前移），此后永不再改（KV-cache 稳定）。
     */
    private boolean applyL1L2(List<LlmMessage> messages, State state) {
        int size = messages.size();
        int end = size - PROTECT_TAIL;
        int start = state.sealedPrefixLen;
        if (end <= start) {
            return false;
        }
        boolean changed = false;
        for (int i = start; i < end; i++) {
            LlmMessage m = messages.get(i);
            if ("user".equals(m.getRole())) {
                // 铁律：user 原文不动
                continue;
            }
            if ("tool".equals(m.getRole())) {
                String c = m.getContent();
                if (c != null && c.length() > L1_KEEP && !c.startsWith("[已压缩")) {
                    m.setContent("[已压缩 tool_result] " + c.substring(0, L1_KEEP) + " …(旧输出已截断)");
                    changed = true;
                }
            } else if ("assistant".equals(m.getRole())) {
                String c = m.getContent();
                // L2：旧 assistant 长思考压一行；带 tool_calls 的保留 tool_calls（协议完整性），只压 content
                if (c != null && c.length() > 200 && !c.startsWith("[已压缩")) {
                    m.setContent("[已压缩思考] " + firstLine(c, 120));
                    changed = true;
                }
            }
        }
        return changed;
    }

    // ---------------------------------------------------------------- L4

    /**
     * L4 全量摘要：usage 逼近窗口时一次性大压缩。把 [0, size-PROTECT_TAIL) 的历史替换为
     * <b>一条</b> 8 段模板摘要（system-memory 并入），但<b>显式保留所有 role=user 消息原文</b>。
     * 结构：[所有 user 原文（原序）] + [一条摘要] + [保护窗口尾部原样]。
     * 这是「一次性大失效换长期稳定」——之后 sealedPrefixLen 前移到摘要+user 之后。
     */
    private boolean applyL4FullCompaction(List<LlmMessage> messages, State state) {
        int size = messages.size();
        int end = size - PROTECT_TAIL;
        if (end <= 1) {
            return false;
        }
        List<LlmMessage> head = messages.subList(0, end);

        // 收集要保留的 user 原文（铁律）
        List<LlmMessage> preservedUsers = new ArrayList<>();
        for (LlmMessage m : head) {
            if ("user".equals(m.getRole())) {
                preservedUsers.add(cloneMsg(m));
            }
        }
        String summaryText = buildEightSectionSummary(head, state);
        LlmMessage summary = LlmMessage.builder().role("user").content(summaryText).build();

        List<LlmMessage> tail = new ArrayList<>(messages.subList(end, size));

        messages.clear();
        messages.addAll(preservedUsers);
        messages.add(summary);
        messages.addAll(tail);

        // 摘要+user 段全部定型：此后不再改（新的稳定前缀）
        state.sealedPrefixLen = preservedUsers.size() + 1;
        state.lastFullCompactUpto = state.sealedPrefixLen;
        log.info("[L4] 全量摘要：保留 {} 条 user 原文 + 1 条摘要 + {} 条尾部",
                preservedUsers.size(), tail.size());
        return true;
    }

    /**
     * 8 段模板摘要（任务/已做改动/验证状态/未决问题/关键文件/约束/下一步/用户原始意图）。
     * 事实来源：L3 session-memory + 从被压历史里扫出的信号。用户原始意图段明确指回保留的 user 原文。
     */
    private String buildEightSectionSummary(List<LlmMessage> head, State state) {
        SessionMemory mem = state.memory;
        StringBuilder sb = new StringBuilder();
        sb.append("[上下文摘要 · 因逼近窗口做了一次性压缩，以下为已压历史的结构化提炼；所有用户消息原文已在上方逐条保留]\n");
        sb.append("1. 任务：").append(nz(mem.task(), "（见上方用户消息原文）")).append('\n');
        sb.append("2. 已做改动：").append(nz(mem.changesJoined(), "（暂无记录的文件改动）")).append('\n');
        sb.append("3. 验证状态：").append(nz(mem.verifyStatus(), "（尚未记录验证结果）")).append('\n');
        sb.append("4. 未决问题：").append(nz(mem.openQuestions(), "（无）")).append('\n');
        sb.append("5. 关键文件：").append(nz(mem.filesJoined(), "（无）")).append('\n');
        sb.append("6. 约束：").append(nz(mem.constraints(), "（沿用系统提示词约束）")).append('\n');
        sb.append("7. 下一步：").append(nz(mem.nextStep(), "（据用户原始意图继续）")).append('\n');
        sb.append("8. 用户原始意图：以上方逐条保留的 user 原文为准，不得偏离。\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    /** 把 [sealedPrefixLen, size-PROTECT_TAIL) 里已被 L1/L2 定型的部分并入定型前缀。 */
    private void reseal(List<LlmMessage> messages, State state) {
        int newSeal = Math.max(state.sealedPrefixLen, messages.size() - PROTECT_TAIL);
        if (newSeal > state.sealedPrefixLen) {
            state.sealedPrefixLen = newSeal;
        }
    }

    private static LlmMessage cloneMsg(LlmMessage m) {
        return LlmMessage.builder()
                .role(m.getRole())
                .content(m.getContent())
                .toolCalls(m.getToolCalls())
                .toolCallId(m.getToolCallId())
                .build();
    }

    private static String firstLine(String s, int max) {
        int nl = s.indexOf('\n');
        String line = nl >= 0 ? s.substring(0, nl) : s;
        return line.length() > max ? line.substring(0, max) + "…" : line;
    }

    private static String nz(String s, String dflt) {
        return (s == null || s.isBlank()) ? dflt : s;
    }
}
