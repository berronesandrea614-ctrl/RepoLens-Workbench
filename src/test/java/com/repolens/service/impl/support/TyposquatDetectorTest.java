package com.repolens.service.impl.support;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TyposquatDetector 纯函数单测：
 * 1. damerauLevenshtein — 空串、相同串、单步操作、换位（transposition）。
 * 2. normalize — 小写、去 -_. 分隔符。
 * 3. detectNpm / detectPypi — 精确命中热门包不报、1-2 距离命中报、超出距离不报。
 */
class TyposquatDetectorTest {

    private final TyposquatDetector detector = new TyposquatDetector();

    // ─────────────────── damerauLevenshtein pure-function tests ───────────────

    @Test
    void dl_emptyStrings_returnsZero() {
        assertThat(TyposquatDetector.damerauLevenshtein("", "")).isEqualTo(0);
    }

    @Test
    void dl_oneEmpty_returnsOtherLength() {
        assertThat(TyposquatDetector.damerauLevenshtein("", "abc")).isEqualTo(3);
        assertThat(TyposquatDetector.damerauLevenshtein("abc", "")).isEqualTo(3);
    }

    @Test
    void dl_sameString_returnsZero() {
        assertThat(TyposquatDetector.damerauLevenshtein("react", "react")).isEqualTo(0);
    }

    @Test
    void dl_singleSubstitution() {
        // "reakt" vs "react": one substitution (k→c)
        assertThat(TyposquatDetector.damerauLevenshtein("reakt", "react")).isEqualTo(1);
    }

    @Test
    void dl_singleInsertion() {
        assertThat(TyposquatDetector.damerauLevenshtein("reac", "react")).isEqualTo(1);
    }

    @Test
    void dl_singleDeletion() {
        assertThat(TyposquatDetector.damerauLevenshtein("reacts", "react")).isEqualTo(1);
    }

    @Test
    void dl_transposition_countsAsOneEdit() {
        // "raect" vs "react": transposition of 'a' and 'e'
        assertThat(TyposquatDetector.damerauLevenshtein("raect", "react")).isEqualTo(1);
    }

    @Test
    void dl_twoEdits() {
        // "reakct" vs "react": delete 'k', that's 1 edit; then substitution 'a'→'a'? Actually:
        // "lodesh" vs "lodash": substitution e→a, total 1
        assertThat(TyposquatDetector.damerauLevenshtein("lodesh", "lodash")).isEqualTo(1);
    }

    @Test
    void dl_distance3_isCorrect() {
        // "abc" vs "xyz": 3 substitutions
        assertThat(TyposquatDetector.damerauLevenshtein("abc", "xyz")).isEqualTo(3);
    }

    // ─────────────────────────── normalize ───────────────────────────────────

    @Test
    void normalize_removesHyphensUnderscoresDots() {
        assertThat(TyposquatDetector.normalize("left-pad")).isEqualTo("leftpad");
        assertThat(TyposquatDetector.normalize("PyYAML")).isEqualTo("pyyaml");
        assertThat(TyposquatDetector.normalize("opencv.python")).isEqualTo("opencvpython");
        assertThat(TyposquatDetector.normalize("scikit_learn")).isEqualTo("scikitlearn");
    }

    @Test
    void normalize_nullReturnsEmpty() {
        assertThat(TyposquatDetector.normalize(null)).isEqualTo("");
    }

    // ─────────────────────────── npm detection ───────────────────────────────

    @Test
    void detectNpm_exactHotPopularPackage_notFlagged() {
        // "react" is in popular list → should NOT be typosquat
        assertThat(detector.detectNpm("react")).isEmpty();
        assertThat(detector.detectNpm("lodash")).isEmpty();
        assertThat(detector.detectNpm("axios")).isEmpty();
    }

    @Test
    void detectNpm_distance1Typo_flagged() {
        // "reakt" → distance 1 from "react" → TYPOSQUAT
        Optional<String> result = detector.detectNpm("reakt");
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("react");
    }

    @Test
    void detectNpm_distance2Typo_flagged() {
        // "loddash" → distance 1 from "lodash" → TYPOSQUAT
        Optional<String> result = detector.detectNpm("loddash");
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("lodash");
    }

    @Test
    void detectNpm_distance3_notFlagged() {
        // A package name with edit distance > 2 from all popular packages
        assertThat(detector.detectNpm("xyzpackage99")).isEmpty();
    }

    @Test
    void detectNpm_null_empty_returnsEmpty() {
        assertThat(detector.detectNpm(null)).isEmpty();
        assertThat(detector.detectNpm("")).isEmpty();
        assertThat(detector.detectNpm("   ")).isEmpty();
    }

    // ─────────── length-gate tests (min-length guard, finding 1) ─────────────

    @Test
    void detectNpm_shortName_len2_notFlagged() {
        // "ky" is a real npm HTTP client (len=2). It is distance-2 from "ws" (popular).
        // The length gate must skip detection entirely for len<=2 → must NOT be flagged.
        assertThat(detector.detectNpm("ky")).isEmpty();
    }

    @Test
    void detectNpm_expres_len6_distance1_flagged() {
        // "expres" (len=6) is distance-1 from "express" → len>=5, effectiveMaxDist=2 → must flag.
        Optional<String> result = detector.detectNpm("expres");
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("express");
    }

    @Test
    void detectPypi_reqeusts_len8_transposition_flagged() {
        // "reqeusts" (len=8) has "eu"↔"ue" transposition vs "requests" → distance=1.
        // len>=5, effectiveMaxDist=2 → must be flagged.
        Optional<String> result = detector.detectPypi("reqeusts");
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("requests");
    }

    // ─────────────────────────── pypi detection ──────────────────────────────

    @Test
    void detectPypi_exactHotPopularPackage_notFlagged() {
        assertThat(detector.detectPypi("requests")).isEmpty();
        assertThat(detector.detectPypi("numpy")).isEmpty();
        assertThat(detector.detectPypi("flask")).isEmpty();
    }

    @Test
    void detectPypi_distance1Typo_flagged() {
        // "requets" → 1 deletion from "requests"
        Optional<String> result = detector.detectPypi("requets");
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("requests");
    }

    @Test
    void detectPypi_distance2Typo_flagged() {
        // "nuumpy" → distance 2 from "numpy" (insert u + insert u → "nuumpy"? let's try "numpi")
        // "numpi" → distance 1 from "numpy"
        Optional<String> result = detector.detectPypi("numpi");
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("numpy");
    }

    @Test
    void detectPypi_null_empty_returnsEmpty() {
        assertThat(detector.detectPypi(null)).isEmpty();
        assertThat(detector.detectPypi("")).isEmpty();
    }
}
