package com.repolens.service.impl.support;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存抽取异步执行的轻量级指标。
 * 线程安全：所有计数器使用 AtomicLong。
 * 设计理念：无依赖（无 micrometer/actuator）、极轻量、绝不在主路径抛异常。
 */
@Component
public class MemoryMetrics {

    /** 投递到线程池的总任务数。 */
    private final AtomicLong submitted = new AtomicLong(0);

    /** 成功完成且记忆被实际沉淀（非空、通过注入防护）的任务数。 */
    private final AtomicLong completed = new AtomicLong(0);

    /** 因异常失败的任务数。 */
    private final AtomicLong failed = new AtomicLong(0);

    /** 记忆被过滤掉的任务数（如空提取、注入防护拒绝等）。 */
    private final AtomicLong skipped = new AtomicLong(0);

    /** 向量召回命中次数（Milvus 返回有效候选）。 */
    private final AtomicLong vectorHits = new AtomicLong(0);

    /** 向量召回降级次数（Milvus 不可用，退化为关键词路径）。 */
    private final AtomicLong vectorFallbacks = new AtomicLong(0);

    /** Reconcile 动作 ADD 的次数（新记忆直接插入）。 */
    private final AtomicLong reconcileAdd = new AtomicLong(0);

    /** Reconcile 动作 UPDATE 的次数（用新内容更新旧记忆）。 */
    private final AtomicLong reconcileUpdate = new AtomicLong(0);

    /** Reconcile 动作 DELETE 的次数（删除矛盾旧记忆后新增）。 */
    private final AtomicLong reconcileDelete = new AtomicLong(0);

    /** Reconcile 动作 NOOP 的次数（新记忆与旧重复，丢弃）。 */
    private final AtomicLong reconcileNoop = new AtomicLong(0);

    /**
     * 增加投递计数。在 memoryExecutor.submit() 前后调用。
     */
    public void incrementSubmitted() {
        submitted.incrementAndGet();
    }

    /**
     * 增加成功完成计数。在记忆被成功沉淀时调用。
     */
    public void incrementCompleted() {
        completed.incrementAndGet();
    }

    /**
     * 增加失败计数。在 catch 块中调用。
     */
    public void incrementFailed() {
        failed.incrementAndGet();
    }

    /**
     * 增加跳过计数。在记忆被过滤时调用（如空提取或注入防护拒绝）。
     */
    public void incrementSkipped() {
        skipped.incrementAndGet();
    }

    /**
     * 增加向量召回命中计数。在 Milvus 成功返回候选时调用。
     */
    public void incrementVectorHits() {
        vectorHits.incrementAndGet();
    }

    /**
     * 增加向量召回降级计数。在 Milvus 不可用、退化为关键词路径时调用。
     */
    public void incrementVectorFallbacks() {
        vectorFallbacks.incrementAndGet();
    }

    /** 记录 reconcile ADD 动作。 */
    public void incrementReconcileAdd() {
        reconcileAdd.incrementAndGet();
    }

    /** 记录 reconcile UPDATE 动作。 */
    public void incrementReconcileUpdate() {
        reconcileUpdate.incrementAndGet();
    }

    /** 记录 reconcile DELETE 动作。 */
    public void incrementReconcileDelete() {
        reconcileDelete.incrementAndGet();
    }

    /** 记录 reconcile NOOP 动作。 */
    public void incrementReconcileNoop() {
        reconcileNoop.incrementAndGet();
    }

    /**
     * 快照当前所有计数器的值。
     * 返回不可变的 Map，反映当前时刻的最新数据。
     */
    public Map<String, Long> snapshot() {
        Map<String, Long> snap = new HashMap<>();
        snap.put("submitted", submitted.get());
        snap.put("completed", completed.get());
        snap.put("failed", failed.get());
        snap.put("skipped", skipped.get());
        snap.put("vectorHits", vectorHits.get());
        snap.put("vectorFallbacks", vectorFallbacks.get());
        snap.put("reconcileAdd", reconcileAdd.get());
        snap.put("reconcileUpdate", reconcileUpdate.get());
        snap.put("reconcileDelete", reconcileDelete.get());
        snap.put("reconcileNoop", reconcileNoop.get());
        return snap;
    }
}
