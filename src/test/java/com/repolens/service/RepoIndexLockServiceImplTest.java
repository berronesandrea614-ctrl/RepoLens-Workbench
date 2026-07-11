package com.repolens.service;

import com.repolens.service.impl.RepoIndexLockServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

// LENIENT mode: unlock_shouldUseTraceIdGuard uses stringRedisTemplate.execute() directly
// and does not call opsForValue(), so the setUp() stub for opsForValue() would trigger
// UnnecessaryStubbingException under strict mode.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RepoIndexLockServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RepoIndexLockServiceImpl repoIndexLockService;

    @BeforeEach
    void setUp() {
        repoIndexLockService = new RepoIndexLockServiceImpl(stringRedisTemplate);
        ReflectionTestUtils.setField(repoIndexLockService, "lockTtlMinutes", 10L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void tryLock_shouldAcquireLock() {
        when(valueOperations.setIfAbsent(eq("repo:index:lock:5"), eq("trace-1"), any(Duration.class))).thenReturn(true);

        RepoIndexLockService.LockResult result = repoIndexLockService.tryLock(5L, "trace-1");

        Assertions.assertTrue(result.proceed());
        Assertions.assertTrue(result.lockAcquired());
        Assertions.assertFalse(result.degraded());
    }

    @Test
    void tryLock_shouldDegradeWhenRedisUnavailable() {
        when(valueOperations.setIfAbsent(eq("repo:index:lock:5"), eq("trace-1"), any(Duration.class)))
                .thenThrow(new RuntimeException("redis unavailable"));

        RepoIndexLockService.LockResult result = repoIndexLockService.tryLock(5L, "trace-1");

        Assertions.assertTrue(result.proceed());
        Assertions.assertFalse(result.lockAcquired());
        Assertions.assertTrue(result.degraded());
    }

    @Test
    void unlock_shouldUseTraceIdGuard() {
        when(stringRedisTemplate.execute(any(), eq(List.of("repo:index:lock:5")), eq("trace-1"))).thenReturn(1L);

        boolean unlocked = repoIndexLockService.unlock(5L, "trace-1");

        Assertions.assertTrue(unlocked);
    }
}
