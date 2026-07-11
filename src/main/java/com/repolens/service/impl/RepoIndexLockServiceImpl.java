package com.repolens.service.impl;

import com.repolens.service.RepoIndexLockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/**
 * Redis repo 级索引锁实现。
 *
 * 设计意图：
 * 1. 防止同一个 repo 被重复触发异步索引；
 * 2. 用 TTL 兜底进程宕机后的锁释放；
 * 3. unlock 时做 value 校验，避免误删别的请求持有的锁。
 *
 * 注意：Redis 锁不是事实状态来源。即使 Redis 不可用，系统也仍可退化回 MySQL + MQ 任务链路继续运行。
 */
@Slf4j
@Service
public class RepoIndexLockServiceImpl implements RepoIndexLockService {

    private static final long DEFAULT_TTL_MINUTES = 10L;
    private static final String LOCK_KEY_PREFIX = "repo:index:lock:";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = buildUnlockScript();

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${repolens.index.lock-ttl-minutes:10}")
    private long lockTtlMinutes;

    public RepoIndexLockServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public LockResult tryLock(Long repoId, String traceId) {
        if (repoId == null || !StringUtils.hasText(traceId)) {
            throw new IllegalArgumentException("repoId and traceId are required");
        }

        String key = buildLockKey(repoId);
        Duration ttl = Duration.ofMinutes(Math.max(1L, lockTtlMinutes <= 0 ? DEFAULT_TTL_MINUTES : lockTtlMinutes));
        try {
            Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(key, traceId, ttl);
            if (Boolean.TRUE.equals(locked)) {
                log.info("Repo index lock acquired, repoId={}, traceId={}, ttlMinutes={}",
                        repoId, traceId, ttl.toMinutes());
                return new LockResult(true, true, false, "async index task submitted");
            }
            log.info("Repo index lock rejected duplicate submit, repoId={}", repoId);
            return new LockResult(false, false, false, "repo index is already running");
        } catch (Exception ex) {
            // Redis 不可用时只记录降级，不阻断 MySQL 事实链路。
            log.warn("Repo index lock degraded, repoId={}, reason={}", repoId, trimError(ex.getMessage()));
            return new LockResult(true, false, true, "index lock unavailable, task submitted without lock");
        }
    }

    @Override
    public boolean unlock(Long repoId, String traceId) {
        if (repoId == null || !StringUtils.hasText(traceId)) {
            return false;
        }
        try {
            Long deleted = stringRedisTemplate.execute(UNLOCK_SCRIPT, List.of(buildLockKey(repoId)), traceId);
            boolean unlocked = deleted != null && deleted > 0;
            log.info("Repo index lock release attempted, repoId={}, traceId={}, unlocked={}",
                    repoId, traceId, unlocked);
            return unlocked;
        } catch (Exception ex) {
            log.warn("Repo index lock release failed, repoId={}, reason={}", repoId, trimError(ex.getMessage()));
            return false;
        }
    }

    @Override
    public boolean isLocked(Long repoId) {
        if (repoId == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(buildLockKey(repoId)));
        } catch (Exception ex) {
            log.warn("Repo index lock state degraded, repoId={}, reason={}", repoId, trimError(ex.getMessage()));
            return false;
        }
    }

    private String buildLockKey(Long repoId) {
        return LOCK_KEY_PREFIX + repoId;
    }

    private String trimError(String errorMsg) {
        if (!StringUtils.hasText(errorMsg)) {
            return "unknown";
        }
        String trimmed = errorMsg.trim();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
    }

    private static DefaultRedisScript<Long> buildUnlockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("""
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                end
                return 0
                """);
        script.setResultType(Long.class);
        return script;
    }
}
