package com.repolens.service;

import com.repolens.domain.entity.IndexTaskEntity;
import com.repolens.domain.mq.RepoIndexMessage;
import com.repolens.domain.vo.AsyncIndexResultVO;
import com.repolens.domain.vo.SyncIndexResultVO;

import java.time.LocalDateTime;
import java.util.List;

public interface RepoAsyncIndexService {

    AsyncIndexResultVO submitAsyncIndex(Long repoId, Long userId);

    /**
     * 同步一键索引：当前线程内按顺序执行 import → parse → chunks → vectors，
     * 结束时 repo 处于 INDEXED 或 FAILED（绝不残留 INDEXING）。
     */
    SyncIndexResultVO runSyncIndex(Long repoId, Long userId);

    void handleIndexMessage(RepoIndexMessage message);

    List<IndexTaskEntity> findRetryTasks(LocalDateTime beforeTime, int limit);

    List<IndexTaskEntity> findPendingTimeoutTasks(LocalDateTime beforeTime, int limit);

    List<IndexTaskEntity> findRunningTimeoutTasks(LocalDateTime beforeTime, int limit);

    void requeueTask(IndexTaskEntity task, String reason);

    void transferRunningTimeoutTask(IndexTaskEntity task);
}
