package com.repolens.service.impl.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VerdictPriority 纯函数单测。验证优先级顺序：MALICIOUS > TYPOSQUAT > NOT_FOUND > VULNERABLE > OK > UNKNOWN。
 */
class VerdictPriorityTest {

    @Test
    void malicious_beats_typosquat() {
        assertThat(VerdictPriority.merge("MALICIOUS", "TYPOSQUAT")).isEqualTo("MALICIOUS");
    }

    @Test
    void typosquat_beats_notFound() {
        assertThat(VerdictPriority.merge("TYPOSQUAT", "NOT_FOUND")).isEqualTo("TYPOSQUAT");
    }

    @Test
    void notFound_beats_vulnerable() {
        assertThat(VerdictPriority.merge("NOT_FOUND", "VULNERABLE")).isEqualTo("NOT_FOUND");
    }

    @Test
    void vulnerable_beats_ok() {
        assertThat(VerdictPriority.merge("VULNERABLE", "OK")).isEqualTo("VULNERABLE");
    }

    @Test
    void ok_beats_unknown() {
        assertThat(VerdictPriority.merge("OK", "UNKNOWN")).isEqualTo("OK");
    }

    @Test
    void null_first_returns_second() {
        assertThat(VerdictPriority.merge(null, "OK")).isEqualTo("OK");
    }

    @Test
    void null_second_returns_first() {
        assertThat(VerdictPriority.merge("VULNERABLE", null)).isEqualTo("VULNERABLE");
    }

    @Test
    void both_null_returns_null() {
        assertThat(VerdictPriority.merge(null, null)).isNull();
    }

    @Test
    void priorityOf_malicious_is_1() {
        assertThat(VerdictPriority.priorityOf("MALICIOUS")).isEqualTo(1);
    }

    @Test
    void priorityOf_unknown_greater_than_ok() {
        assertThat(VerdictPriority.priorityOf("UNKNOWN"))
                .isGreaterThan(VerdictPriority.priorityOf("OK"));
    }

    @Test
    void merge_is_commutative() {
        assertThat(VerdictPriority.merge("VULNERABLE", "NOT_FOUND"))
                .isEqualTo(VerdictPriority.merge("NOT_FOUND", "VULNERABLE"));
    }

    @Test
    void merge_same_verdict_returns_same() {
        assertThat(VerdictPriority.merge("OK", "OK")).isEqualTo("OK");
    }
}
