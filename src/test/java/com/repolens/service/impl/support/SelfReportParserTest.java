package com.repolens.service.impl.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SelfReportParser 单测（Feature B P1）。
 */
class SelfReportParserTest {

    @Test
    void parse_detects_claimedVerified_Chinese() {
        SelfReportParser.Result r = SelfReportParser.parse("已测试通过，所有功能正常。");
        assertThat(r.claimedVerified()).isTrue();
    }

    @Test
    void parse_detects_claimedVerified_English() {
        SelfReportParser.Result r = SelfReportParser.parse("All tests pass. The feature is done.");
        assertThat(r.claimedVerified()).isTrue();
    }

    @Test
    void parse_detects_claimedSuccess_Chinese() {
        SelfReportParser.Result r = SelfReportParser.parse("验证码功能已完成，请确认。");
        assertThat(r.claimedSuccess()).isTrue();
    }

    @Test
    void parse_detects_claimedSuccess_English() {
        SelfReportParser.Result r = SelfReportParser.parse("The task is done and fixed.");
        assertThat(r.claimedSuccess()).isTrue();
    }

    @Test
    void parse_noMatch_returnsAllFalse() {
        SelfReportParser.Result r = SelfReportParser.parse("我修改了 CaptchaService 的逻辑。");
        assertThat(r.claimedSuccess()).isFalse();
        assertThat(r.claimedVerified()).isFalse();
        assertThat(r.claimEvidence()).isNull();
    }

    @Test
    void parse_nullInput_returnsEmpty() {
        SelfReportParser.Result r = SelfReportParser.parse(null);
        assertThat(r.claimedSuccess()).isFalse();
        assertThat(r.claimedVerified()).isFalse();
    }

    @Test
    void parse_blankInput_returnsEmpty() {
        SelfReportParser.Result r = SelfReportParser.parse("   ");
        assertThat(r.claimedSuccess()).isFalse();
    }

    @Test
    void parse_capturesEvidence() {
        SelfReportParser.Result r = SelfReportParser.parse("所有改动均已完成，测试通过。");
        assertThat(r.claimEvidence()).isNotNull();
    }

    @Test
    void parse_claimedVerified_testPassChinese() {
        SelfReportParser.Result r = SelfReportParser.parse("测试全部通过，可以合并。");
        assertThat(r.claimedVerified()).isTrue();
    }
}
