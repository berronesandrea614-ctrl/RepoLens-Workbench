package com.repolens.service.impl;

import com.repolens.service.RepoAsyncIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台（非阻塞）索引：把「导入即强制等索引」改成「导入立即可用、索引在后台跑」。
 *
 * <p>在 daemon 线程池里跑同步编排 {@link RepoAsyncIndexService#runSyncIndex}
 * （import→parse→chunk→vector），调用方立即返回、不阻塞。同一 repo 同时只跑一份
 * （in-flight 去重），避免重复触发叠跑。索引状态由编排器自身维护
 * （INDEXING→INDEXED/FAILED），前端按状态展示「索引中/已过期」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoBackgroundIndexService {

    private final RepoAsyncIndexService repoAsyncIndexService;

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "repo-bg-index");
        t.setDaemon(true);
        return t;
    });

    /** 正在后台索引的 repoId，防重复触发。 */
    private final Set<Long> inflight = ConcurrentHashMap.newKeySet();

    /**
     * 触发后台索引。
     *
     * @return true=已排队启动；false=该 repo 已有后台索引在跑（本次忽略）。
     */
    public boolean startBackgroundIndex(Long repoId, Long userId) {
        if (!inflight.add(repoId)) {
            log.info("[BgIndex] repoId={} 已在后台索引中，忽略重复触发", repoId);
            return false;
        }
        pool.submit(() -> {
            try {
                log.info("[BgIndex] 后台索引开始 repoId={}", repoId);
                repoAsyncIndexService.runSyncIndex(repoId, userId);
                log.info("[BgIndex] 后台索引完成 repoId={}", repoId);
            } catch (Exception e) {
                log.warn("[BgIndex] 后台索引失败 repoId={}: {}", repoId, e.getMessage());
            } finally {
                inflight.remove(repoId);
            }
        });
        return true;
    }

    /** 该 repo 是否正在后台索引。 */
    public boolean isIndexing(Long repoId) {
        return inflight.contains(repoId);
    }
}
