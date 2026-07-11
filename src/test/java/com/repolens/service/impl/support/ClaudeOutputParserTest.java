package com.repolens.service.impl.support;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD tests for ClaudeOutputParser（P0 Task 2：飞书远程控制 Claude）。
 * 纯 JUnit5，无 Spring context，无 mock。
 */
class ClaudeOutputParserTest {

    // ── stripAnsi: 颜色 / 光标移动序列 ────────────────────────────────────────

    @Test
    void stripAnsi_removesColorAndCursorSequences() {
        // ESC[32m = green, ESC[0m = reset, ESC[1;34m = bold-blue, ESC[2A = cursor up 2
        String raw = "\033[32mHello\033[0m \033[1;34mWorld\033[0m\033[2A";
        assertThat(ClaudeOutputParser.stripAnsi(raw)).isEqualTo("Hello World");
    }

    // ── stripAnsi: \r 行覆盖取最终可见文本 ────────────────────────────────────

    @Test
    void stripAnsi_carriageReturn_lastOverwriteWins() {
        // "abc\rXY" => cursor resets to 0, X overwrites 'a', Y overwrites 'b', 'c' remains
        assertThat(ClaudeOutputParser.stripAnsi("abc\rXY")).isEqualTo("XYc");
    }

    // ── feed + flushIfIdle: 多 chunk 合并后产出 ────────────────────────────────

    @Test
    void feed_multipleChunks_flushIfIdleProducesMergedText() {
        ClaudeOutputParser parser = new ClaudeOutputParser();
        long t0 = 10_000L;

        parser.feed("Hello ", t0);
        parser.feed("World", t0 + 50L);   // 50 ms later — still accumulating

        // 100 ms since last feed — within 300 ms threshold, expect empty
        assertThat(parser.flushIfIdle(t0 + 100L)).isEmpty();

        // 400 ms since last feed — exceeds 300 ms, should flush
        Optional<ClaudeOutputParser.Turn> turn = parser.flushIfIdle(t0 + 450L);
        assertThat(turn).isPresent();
        assertThat(turn.get().text()).isEqualTo("Hello World");
        assertThat(turn.get().state()).isEqualTo(ClaudeOutputParser.TurnState.DONE);
    }

    // ── 权限询问 -> WAITING_PERMISSION ──────────────────────────────────────

    @Test
    void flushIfIdle_permissionText_returnsWaitingPermission() {
        ClaudeOutputParser parser = new ClaudeOutputParser();
        long t0 = 20_000L;

        parser.feed("Do you want to allow this action? (y/n)", t0);

        Optional<ClaudeOutputParser.Turn> turn = parser.flushIfIdle(t0 + 500L);
        assertThat(turn).isPresent();
        assertThat(turn.get().state()).isEqualTo(ClaudeOutputParser.TurnState.WAITING_PERMISSION);
    }

    // ── 静默 flush -> DONE ────────────────────────────────────────────────────

    @Test
    void flushIfIdle_normalText_returnsDone() {
        ClaudeOutputParser parser = new ClaudeOutputParser();
        long t0 = 30_000L;

        parser.feed("Task completed successfully.", t0);

        Optional<ClaudeOutputParser.Turn> turn = parser.flushIfIdle(t0 + 500L);
        assertThat(turn).isPresent();
        assertThat(turn.get().state()).isEqualTo(ClaudeOutputParser.TurnState.DONE);
        assertThat(turn.get().text()).contains("Task completed successfully.");
    }

    // ── 空缓冲 flushIfIdle -> empty ───────────────────────────────────────────

    @Test
    void flushIfIdle_emptyBuffer_returnsEmpty() {
        ClaudeOutputParser parser = new ClaudeOutputParser();
        // Never fed — buffer is empty, lastFeedMs is 0
        assertThat(parser.flushIfIdle(99_999L)).isEmpty();
    }

    // ── 超长输出截断 + 提示 ────────────────────────────────────────────────────

    @Test
    void flushIfIdle_longOutput_truncatesWithSuffix() {
        ClaudeOutputParser parser = new ClaudeOutputParser();
        long t0 = 40_000L;

        String longText = "A".repeat(3000);
        parser.feed(longText, t0);

        Optional<ClaudeOutputParser.Turn> turn = parser.flushIfIdle(t0 + 500L);
        assertThat(turn).isPresent();

        String text = turn.get().text();
        String suffix = "(输出过长，在 app 查看完整)";
        assertThat(text).endsWith(suffix);
        assertThat(text).startsWith("A".repeat(2000));
        assertThat(text).hasSize(2000 + suffix.length());
    }
}
