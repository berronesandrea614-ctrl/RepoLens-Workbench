package com.repolens.kernel.spi;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级「读后写不变式」的记账本。
 *
 * <p>铁律（规划 §3.3）：Edit/Write 前，本会话必须先 Read 过该文件，且磁盘（影子区）自读取以来未变。
 * 本类只记录「读到时的内容 hash」，由编辑工具在写前核对：
 * <ul>
 *   <li>没读过 → 拒绝（agent 不得盲改没看过的代码，对齐 Claude Code「NEVER 改没读过的代码」）；</li>
 *   <li>读过但 hash 已变 → 拒绝（文件在读之后被改动，需重读，防覆盖别人/自己的中间修改）。</li>
 * </ul>
 *
 * <p>为什么按会话（run）隔离：每个 agent run 有自己的影子区与读视图，跨 run 共享会串味。
 * 因此 ReadTracker 是 per-run 实例（挂在 {@link ToolContext} 上），不是 Spring 单例。
 */
public class ReadTracker {

    /** relPath → 读到时的内容 sha256。ConcurrentHashMap 因只读工具会并发回填。 */
    private final Map<String, String> readHashes = new ConcurrentHashMap<>();

    /** 记录一次读取：relPath 及其当时的内容 hash。 */
    public void recordRead(String relPath, String contentHash) {
        readHashes.put(relPath, contentHash);
    }

    /** 该文件本会话是否读过。 */
    public boolean wasRead(String relPath) {
        return readHashes.containsKey(relPath);
    }

    /** 读到时的 hash（用于核对磁盘是否自读后变化）。 */
    public Optional<String> readHash(String relPath) {
        return Optional.ofNullable(readHashes.get(relPath));
    }

    /** 写入落盘后刷新读视图（agent 刚写的内容视为「已读」，可连续编辑同一文件）。 */
    public void refreshAfterWrite(String relPath, String newContentHash) {
        readHashes.put(relPath, newContentHash);
    }
}
