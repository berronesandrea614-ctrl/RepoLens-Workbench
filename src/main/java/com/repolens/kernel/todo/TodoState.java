package com.repolens.kernel.todo;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次 agent run 的 TodoWrite 清单状态（per-run，<b>非</b> Spring 单例）。
 *
 * <p>M5 反漂移的载体：agent 通过 {@code TodoWrite} 提交/更新任务清单，主循环每轮把当前清单
 * （尤其"下一个 PENDING/唯一的 IN_PROGRESS"）重注入上下文，压制长程任务里的目标漂移。
 *
 * <p>不变式（由 {@link TodoWriteTool} 校验，本类只做纯状态承载）：
 * <ul>
 *   <li>至多一个 {@link Status#IN_PROGRESS}；</li>
 *   <li>标 {@link Status#COMPLETED} 前，本 run 必须有过一次成功的 {@code runVerification}
 *       （测试没绿不许标完成，防"口头声称已完成"）。</li>
 * </ul>
 */
public class TodoState {

    /** 单项任务状态。 */
    public enum Status { PENDING, IN_PROGRESS, COMPLETED }

    /** 单条待办。 */
    public record Item(String content, Status status) {}

    private final List<Item> items = new ArrayList<>();

    /** 用一批新项整体替换当前清单（TodoWrite 语义：每次提交完整清单）。 */
    public synchronized void replace(List<Item> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
    }

    public synchronized List<Item> snapshot() {
        return List.copyOf(items);
    }

    public synchronized boolean isEmpty() {
        return items.isEmpty();
    }

    /** 有几项处于 IN_PROGRESS（不变式校验用：必须 ≤1）。 */
    public synchronized long inProgressCount() {
        return items.stream().filter(i -> i.status() == Status.IN_PROGRESS).count();
    }

    /** 是否含 COMPLETED 项（用于"标完成前须有成功验证"的门）。 */
    public synchronized boolean hasCompleted() {
        return items.stream().anyMatch(i -> i.status() == Status.COMPLETED);
    }

    /**
     * 把当前清单渲染成一条重注入上下文的快照文本（突出下一步，压漂移）。
     */
    public synchronized String renderSnapshot() {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[当前任务清单 TodoWrite]\n");
        Item next = null;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            String mark = switch (it.status()) {
                case COMPLETED -> "[x]";
                case IN_PROGRESS -> "[~]";
                case PENDING -> "[ ]";
            };
            sb.append(mark).append(' ').append(it.content()).append('\n');
            if (next == null && (it.status() == Status.IN_PROGRESS || it.status() == Status.PENDING)) {
                next = it;
            }
        }
        if (next != null) {
            sb.append("→ 下一步应聚焦：").append(next.content());
        } else {
            sb.append("→ 全部完成。");
        }
        return sb.toString();
    }
}
