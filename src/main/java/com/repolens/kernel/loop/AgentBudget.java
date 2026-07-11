package com.repolens.kernel.loop;

/**
 * 单次 agent run 的双预算：token 预算 + 墙钟预算。
 *
 * <p>为什么不用「迭代次数」当上限（规划 M3 明确纠偏）：迭代计数是伪信号——
 * 一个任务该跑几轮取决于任务本身，硬砍轮数要么误杀正常长任务、要么放过失控循环。
 * 真正的资源约束是「烧了多少 token」和「花了多少墙钟时间」，故以此二者为硬闸。
 *
 * <p>本对象是 per-run 可变状态（非 Spring bean）：主循环每轮把本轮消耗的 token 记进来，
 * 并在轮首检查是否超预算，超则优雅终止（fail-safe，不抛崩 loop）。
 */
public class AgentBudget {

    private final long maxTokens;
    private final long wallClockMs;
    private final long startNanos;
    private long tokensSpent;

    /**
     * @param maxTokens   token 上限（≤0 表示不限）
     * @param wallClockMs 墙钟上限毫秒（≤0 表示不限）
     * @param startNanos  起始 {@code System.nanoTime()}（由调用方传入，便于测试可控）
     */
    public AgentBudget(long maxTokens, long wallClockMs, long startNanos) {
        this.maxTokens = maxTokens;
        this.wallClockMs = wallClockMs;
        this.startNanos = startNanos;
    }

    /** 用当前时钟起步的常规构造。 */
    public static AgentBudget starting(long maxTokens, long wallClockMs) {
        return new AgentBudget(maxTokens, wallClockMs, System.nanoTime());
    }

    /** 累加本轮消耗的 token。 */
    public void consume(int tokens) {
        if (tokens > 0) {
            tokensSpent += tokens;
        }
    }

    public long tokensSpent() {
        return tokensSpent;
    }

    public long elapsedMs(long nowNanos) {
        return (nowNanos - startNanos) / 1_000_000L;
    }

    /** token 是否超预算。 */
    public boolean tokenExhausted() {
        return maxTokens > 0 && tokensSpent >= maxTokens;
    }

    /** 墙钟是否超预算。 */
    public boolean timedOut(long nowNanos) {
        return wallClockMs > 0 && elapsedMs(nowNanos) >= wallClockMs;
    }

    /** 任一预算耗尽即为耗尽。返回耗尽原因串，未耗尽返回 null。 */
    public String exhaustedReason(long nowNanos) {
        if (tokenExhausted()) {
            return "token 预算耗尽（已用 " + tokensSpent + "/" + maxTokens + "）";
        }
        if (timedOut(nowNanos)) {
            return "墙钟预算耗尽（已用 " + elapsedMs(nowNanos) + "ms/" + wallClockMs + "ms）";
        }
        return null;
    }
}
