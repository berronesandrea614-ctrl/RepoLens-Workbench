package com.repolens.service;

/**
 * repo 级异步索引锁。
 * 这个锁只用于防止同一个 repo 被重复点击 /index/async，不是任务事实状态来源。
 * 真正的任务状态仍以 MySQL index_task 为准，Redis 这里只承担“轻量互斥”的职责。
 */
public interface RepoIndexLockService {

    LockResult tryLock(Long repoId, String traceId);

    boolean unlock(Long repoId, String traceId);

    boolean isLocked(Long repoId);

    record LockResult(boolean proceed, boolean lockAcquired, boolean degraded, String message) {
    }
}
