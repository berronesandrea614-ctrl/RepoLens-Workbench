package com.repolens.service.support;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SessionFileReadTrackerTest {

    SessionFileReadTracker tracker = new SessionFileReadTracker();

    @Test
    void noRecord_returnsEmpty() {
        assertThat(tracker.getHash(1L, "Foo.java")).isEmpty();
    }

    @Test
    void afterRecord_returnsHash() {
        tracker.record(1L, "Foo.java", "content");
        assertThat(tracker.getHash(1L, "Foo.java")).isPresent();
    }

    @Test
    void differentContent_differentHash() {
        tracker.record(1L, "A.java", "abc");
        tracker.record(2L, "A.java", "xyz");
        assertThat(tracker.getHash(1L, "A.java")).isNotEqualTo(tracker.getHash(2L, "A.java"));
    }

    @Test
    void clearSession_removesEntries() {
        tracker.record(1L, "X.java", "text");
        tracker.clearSession(1L);
        assertThat(tracker.getHash(1L, "X.java")).isEmpty();
    }
}
