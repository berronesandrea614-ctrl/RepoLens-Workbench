package com.repolens.scheduler;

import com.repolens.domain.entity.IndexTaskEntity;
import com.repolens.service.RepoAsyncIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 异步索引补偿调度器。
 * 解决的是“消息系统不可靠”带来的三个典型问题：
 * 1. MQ 发送失败后任务留在 WAIT_RETRY；
 * 2. PENDING 太久说明消息可能根本没发出去；
 * 3. RUNNING 太久说明消费线程可能中断或实例已经挂掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepoIndexRetryScheduler {

    private static final int DEFAULT_LIMIT = 100;

    private final RepoAsyncIndexService repoAsyncIndexService;

    @Value("${repolens.index.running-timeout-minutes:5}")
    private long runningTimeoutMinutes;

    @Value("${repolens.index.pending-timeout-minutes:1}")
    private long pendingTimeoutMinutes;

    @Value("${repolens.index.retry-query-limit:100}")
    private int retryQueryLimit;

    /**
     * 定时扫描三类任务并触发补偿。
     * processedTaskIds 用来防止同一轮扫描里对同一任务重复处理。
     */
    @Scheduled(fixedDelayString = "${repolens.index.retry-scan-interval-ms:30000}")
    public void compensate() {
        int limit = retryQueryLimit <= 0 ? DEFAULT_LIMIT : retryQueryLimit;
        LocalDateTime now = LocalDateTime.now();
        Set<Long> processedTaskIds = new HashSet<>();

        LocalDateTime runningBefore = now.minusMinutes(Math.max(1, runningTimeoutMinutes));
        List<IndexTaskEntity> runningTimeoutTasks = repoAsyncIndexService.findRunningTimeoutTasks(runningBefore, limit);
        for (IndexTaskEntity runningTimeoutTask : runningTimeoutTasks) {
            if (runningTimeoutTask.getId() == null || !processedTaskIds.add(runningTimeoutTask.getId())) {
                continue;
            }
            repoAsyncIndexService.transferRunningTimeoutTask(runningTimeoutTask);
            repoAsyncIndexService.requeueTask(runningTimeoutTask, "running-timeout");
        }

        LocalDateTime pendingBefore = now.minusMinutes(Math.max(1, pendingTimeoutMinutes));
        List<IndexTaskEntity> pendingTimeoutTasks = repoAsyncIndexService.findPendingTimeoutTasks(pendingBefore, limit);
        for (IndexTaskEntity pendingTimeoutTask : pendingTimeoutTasks) {
            if (pendingTimeoutTask.getId() == null || !processedTaskIds.add(pendingTimeoutTask.getId())) {
                continue;
            }
            repoAsyncIndexService.requeueTask(pendingTimeoutTask, "pending-timeout");
        }

        List<IndexTaskEntity> retryTasks = repoAsyncIndexService.findRetryTasks(now, limit);
        for (IndexTaskEntity retryTask : retryTasks) {
            if (retryTask.getId() == null || !processedTaskIds.add(retryTask.getId())) {
                continue;
            }
            repoAsyncIndexService.requeueTask(retryTask, "wait-retry");
        }
        log.debug("RepoIndexRetryScheduler tick, runningTimeout={}, pendingTimeout={}, waitRetry={}",
                runningTimeoutTasks.size(), pendingTimeoutTasks.size(), retryTasks.size());
    }
}
