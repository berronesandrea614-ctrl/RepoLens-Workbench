package com.repolens.service.impl.support;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * PTY 原始输出流 -> 干净的「一个 Claude 回合」文本 + 状态标签。
 *
 * <p>有状态累积器，按 repoId/ptyId 一个实例（prototype 作用域）。
 * 纯逻辑无 IO，不依赖外部系统，可直接 new 用于测试。
 *
 * <p>使用方式：
 * <ol>
 *   <li>每收到 PTY chunk 调用 {@link #feed(String, long)}；</li>
 *   <li>定时或空闲时调用 {@link #flushIfIdle(long)}，
 *       距上次 feed 超 300 ms 且缓冲非空则产出 Turn；</li>
 *   <li>可单独调用 {@link #stripAnsi(String)} 清洗 ANSI 转义。</li>
 * </ol>
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class ClaudeOutputParser {

    // ── 嵌套类型 ──────────────────────────────────────────────────────────────

    public enum TurnState { RUNNING, DONE, WAITING_INPUT, WAITING_PERMISSION }

    public record Turn(String text, TurnState state) {}

    // ── 常量 ──────────────────────────────────────────────────────────────────

    private static final int    MAX_TEXT_LENGTH   = 2000;
    private static final long   IDLE_THRESHOLD_MS = 300L;
    private static final String TRUNCATION_SUFFIX = "(输出过长，在 app 查看完整)";

    /**
     * 匹配 ANSI/VT100 转义序列。使用正则八进制：\\033 = ESC (0x1B)，\\007 = BEL (0x07)。
     * <ul>
     *   <li>CSI: ESC [ params letter  —— 颜色/光标移动等</li>
     *   <li>OSC: ESC ] content (BEL | ESC \)  —— 标题设置等</li>
     *   <li>Other: ESC + 任意非括号单字符</li>
     * </ul>
     */
    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "\\033(?:"
            + "\\[[0-9;?]*[A-Za-z]"                     // CSI: ESC [ params letter
            + "|\\][^\\007\\033]*(?:\\007|\\033\\\\)"    // OSC: ESC ] content (BEL|ESC\)
            + "|[^\\[\\]]"                               // Other: ESC + non-bracket char
            + ")"
    );

    // ── 实例状态 ──────────────────────────────────────────────────────────────

    private final StringBuilder buffer = new StringBuilder();
    private long lastFeedMs = 0L;

    // ── 公开 API ──────────────────────────────────────────────────────────────

    /**
     * 喂入一个 PTY chunk，累积到内部缓冲。
     *
     * @param rawChunk 原始输出片段（可含 ANSI 转义）；null/空则忽略
     * @param nowMs    当前时间戳（ms），用于防抖计时；测试时传入固定值即可
     * @return 通常为 {@link Optional#empty()}；保留扩展空间
     */
    public synchronized Optional<Turn> feed(String rawChunk, long nowMs) {
        if (rawChunk != null && !rawChunk.isEmpty()) {
            buffer.append(rawChunk);
            lastFeedMs = nowMs;
        }
        return Optional.empty();
    }

    /**
     * 防抖检查：距上次 feed 超过 300 ms 且缓冲非空 -> 产出 Turn 并清空缓冲。
     *
     * @param nowMs 当前时间戳（ms）
     * @return 已成型的回合；或 empty（仍在累积 / 缓冲为空 / 未超阈值）
     */
    public synchronized Optional<Turn> flushIfIdle(long nowMs) {
        if (buffer.length() == 0 || lastFeedMs == 0L) {
            return Optional.empty();
        }
        if (nowMs - lastFeedMs <= IDLE_THRESHOLD_MS) {
            return Optional.empty();
        }

        String raw = buffer.toString();
        buffer.setLength(0);
        lastFeedMs = 0L;

        String stripped = stripAnsi(raw);
        String text     = truncateIfNeeded(stripped);
        // Claude 是全屏 TUI，会持续刷新界面产生大量纯 ANSI 控制序列（光标移动/重绘/spinner）。
        // 去 ANSI 后若无实质文本（空白，或仅剩单个装饰字符），不产出回合——否则会向飞书
        // 推送一堆只有状态 emoji 的空消息，造成刷屏。只有 Claude 真正输出文字时才成一个回合。
        if (text == null || text.strip().length() < 2) {
            return Optional.empty();
        }
        TurnState state = detectState(text);

        return Optional.of(new Turn(text, state));
    }

    /**
     * 去除字符串中所有 ANSI/VT 转义序列，并处理 {@code \r} 行覆盖，
     * 返回最终可见文本。纯函数，线程安全。
     *
     * @param s 原始字符串（可为 null）
     * @return 去除转义后的可见文本；null 输入返回空字符串
     */
    public static String stripAnsi(String s) {
        if (s == null) return "";
        // 1. 去除所有 ANSI/VT 转义序列
        String stripped = ANSI_PATTERN.matcher(s).replaceAll("");
        // 2. 逐行处理 \r 覆盖（split -1 保留尾部空串）
        String[] lines = stripped.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(processCarriageReturns(lines[i]));
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────

    /**
     * 模拟终端 {@code \r} 覆盖逻辑：光标回到行首，后续字符覆盖已有内容。
     * 返回该行最终可见文本（长度 = maxPos）。
     */
    private static String processCarriageReturns(String line) {
        if (!line.contains("\r")) return line;
        char[] buf = new char[line.length()];
        int pos    = 0;
        int maxPos = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\r') {
                pos = 0;
            } else {
                buf[pos] = c;
                pos++;
                if (pos > maxPos) maxPos = pos;
            }
        }
        return new String(buf, 0, maxPos);
    }

    /** 超过 {@link #MAX_TEXT_LENGTH} 字符则截断并追加提示后缀。 */
    private static String truncateIfNeeded(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) return text;
        return text.substring(0, MAX_TEXT_LENGTH) + TRUNCATION_SUFFIX;
    }

    /**
     * 基于去 ANSI 后文本尾部的启发式规则识别回合状态。
     * 保守策略：命中明确模式才改变，否则静默 flush 返回 DONE。
     */
    private TurnState detectState(String strippedText) {
        // 仅检查最后 500 字符，避免扫描超长文本
        String tail      = strippedText.length() > 500
                ? strippedText.substring(strippedText.length() - 500)
                : strippedText;
        String tailLower = tail.toLowerCase();

        // ── 权限询问 ──────────────────────────────────────────────────────────
        if (tail.contains("(y/n)") || tail.contains("(Y/n)") ||
                tail.contains("(y/N)") || tail.contains("(Y/N)") ||
                tailLower.contains("do you want") ||
                tail.contains("❯ 1. Yes") ||
                tail.contains("Yes, and don't ask again") ||
                tail.contains("Allow this action") ||
                tailLower.contains("yes/no")) {
            return TurnState.WAITING_PERMISSION;
        }

        // ── 等待用户输入 ──────────────────────────────────────────────────────
        if (tailLower.contains("enter your") ||
                tailLower.contains("type your") ||
                tailLower.contains("waiting for input") ||
                (tail.length() >= 2 &&
                        (tail.endsWith("> ") || tail.endsWith("$ ")))) {
            return TurnState.WAITING_INPUT;
        }

        // 静默 flush 默认 DONE
        return TurnState.DONE;
    }
}
