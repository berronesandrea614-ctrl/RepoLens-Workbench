package com.repolens.kernel.spi;

import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;

import java.nio.file.Path;

/**
 * 单次工具调用的执行上下文：把「这是哪个 repo/会话/run、真目录在哪、影子区句柄、读记账本、权限模式」
 * 一次性喂给 {@link KernelTool#execute}，让工具本身保持无状态（可作 Spring 单例注入）。
 *
 * <p>写类工具（Write/Edit/MultiEdit）通过 {@link #shadow()} 落盘到影子区（隔离副本），
 * 通过 {@link #tracker()} 校验读后写不变式。只读工具（Read/Grep/Glob）也在影子区读，
 * 保证 agent 看到的是「自己改动后」的一致视图。
 *
 * <p>{@link #mode()} 是本 run 的权限档位（M4）：决定 loop 喂给 LLM 的工具目录过滤
 * （PLAN 只暴露只读工具）与执行侧权限门（deny→ask→allow）的判定。
 *
 * @param repoId   仓库 id
 * @param sessionId 会话 id
 * @param runId    agent run id（改动归属）
 * @param repoDir  真目录根（合并/回滚锚点）
 * @param shadow   本 run 的影子工作区句柄
 * @param tracker  本 run 的读后写记账本
 * @param mode     本 run 的权限档位（DEFAULT/PLAN/ACCEPT_EDITS/AUTO/BYPASS）
 * @param modelName 本 run 使用的模型名（M5：只读子代理 {@code Task} 派生子 run 时复用；可空）
 */
public record ToolContext(
        Long repoId,
        Long sessionId,
        Long runId,
        Path repoDir,
        ShadowHandle shadow,
        ReadTracker tracker,
        PermissionMode mode,
        String modelName) {

    /** 兜底：mode 为空时归一到 DEFAULT，避免下游 NPE。 */
    public ToolContext {
        if (mode == null) {
            mode = PermissionMode.DEFAULT;
        }
    }

    /** 兼容旧调用点（不带 modelName）：默认 null，由子代理侧兜底。 */
    public ToolContext(Long repoId, Long sessionId, Long runId, Path repoDir,
                       ShadowHandle shadow, ReadTracker tracker, PermissionMode mode) {
        this(repoId, sessionId, runId, repoDir, shadow, tracker, mode, null);
    }
}
