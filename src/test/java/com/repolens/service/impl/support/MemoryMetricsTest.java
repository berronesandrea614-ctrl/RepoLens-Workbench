package com.repolens.service.impl.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryMetrics 单元测试。
 * 验证：
 * - 各计数器的初始值为 0
 * - 每个 increment 方法正确增加对应的计数器
 * - snapshot() 返回当前的快照
 * - 线程安全性（并发增量不丢失）
 */
class MemoryMetricsTest {

    private MemoryMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new MemoryMetrics();
    }

    @Test
    void testInitialCountersAreZero() {
        Map<String, Long> snapshot = metrics.snapshot();
        assertEquals(0L, snapshot.get("submitted"));
        assertEquals(0L, snapshot.get("completed"));
        assertEquals(0L, snapshot.get("failed"));
        assertEquals(0L, snapshot.get("skipped"));
    }

    @Test
    void testIncrementSubmitted() {
        metrics.incrementSubmitted();
        metrics.incrementSubmitted();

        Map<String, Long> snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.get("submitted"));
        assertEquals(0L, snapshot.get("completed"));
        assertEquals(0L, snapshot.get("failed"));
        assertEquals(0L, snapshot.get("skipped"));
    }

    @Test
    void testIncrementCompleted() {
        metrics.incrementCompleted();

        Map<String, Long> snapshot = metrics.snapshot();
        assertEquals(0L, snapshot.get("submitted"));
        assertEquals(1L, snapshot.get("completed"));
        assertEquals(0L, snapshot.get("failed"));
        assertEquals(0L, snapshot.get("skipped"));
    }

    @Test
    void testIncrementFailed() {
        metrics.incrementFailed();
        metrics.incrementFailed();
        metrics.incrementFailed();

        Map<String, Long> snapshot = metrics.snapshot();
        assertEquals(0L, snapshot.get("submitted"));
        assertEquals(0L, snapshot.get("completed"));
        assertEquals(3L, snapshot.get("failed"));
        assertEquals(0L, snapshot.get("skipped"));
    }

    @Test
    void testIncrementSkipped() {
        metrics.incrementSkipped();

        Map<String, Long> snapshot = metrics.snapshot();
        assertEquals(0L, snapshot.get("submitted"));
        assertEquals(0L, snapshot.get("completed"));
        assertEquals(0L, snapshot.get("failed"));
        assertEquals(1L, snapshot.get("skipped"));
    }

    @Test
    void testMixedIncrements() {
        metrics.incrementSubmitted();
        metrics.incrementSubmitted();
        metrics.incrementCompleted();
        metrics.incrementFailed();
        metrics.incrementSkipped();
        metrics.incrementSkipped();

        Map<String, Long> snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.get("submitted"));
        assertEquals(1L, snapshot.get("completed"));
        assertEquals(1L, snapshot.get("failed"));
        assertEquals(2L, snapshot.get("skipped"));
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < incrementsPerThread; i++) {
                        if (threadId % 4 == 0) {
                            metrics.incrementSubmitted();
                        } else if (threadId % 4 == 1) {
                            metrics.incrementCompleted();
                        } else if (threadId % 4 == 2) {
                            metrics.incrementFailed();
                        } else {
                            metrics.incrementSkipped();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        Map<String, Long> snapshot = metrics.snapshot();
        // 3 threads for submitted, 2.5 for others, so adjust:
        // threadId % 4 == 0: threads 0, 4, 8 (3 threads)
        // threadId % 4 == 1: threads 1, 5, 9 (3 threads)
        // threadId % 4 == 2: threads 2, 6 (2 threads)
        // threadId % 4 == 3: threads 3, 7 (2 threads)
        assertEquals(300L, snapshot.get("submitted"));  // 3 threads * 100
        assertEquals(300L, snapshot.get("completed")); // 3 threads * 100
        assertEquals(200L, snapshot.get("failed"));    // 2 threads * 100
        assertEquals(200L, snapshot.get("skipped"));   // 2 threads * 100
    }

    @Test
    void testSnapshotReturnsIndependentMap() {
        metrics.incrementSubmitted();
        Map<String, Long> snapshot1 = metrics.snapshot();

        metrics.incrementCompleted();
        Map<String, Long> snapshot2 = metrics.snapshot();

        // snapshot1 should not be affected by later increments
        assertEquals(1L, snapshot1.get("submitted"));
        assertEquals(0L, snapshot1.get("completed"));

        // snapshot2 should reflect the new state
        assertEquals(1L, snapshot2.get("submitted"));
        assertEquals(1L, snapshot2.get("completed"));
    }
}
