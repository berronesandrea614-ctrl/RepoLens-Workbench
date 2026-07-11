package com.repolens.kernel.loop;

/**
 * agent 主循环的过程监听器——把内核的内部过程（工具步、最终答案）以<b>内核原生类型</b>
 * 回调出去，供上层（app bridge）转成 SSE 事件 / 前端可视化（规划 §3.7 可视化外显）。
 *
 * <p>刻意只用原始类型，不依赖任何 app 层 VO——保持内核不反向耦合上层。
 * bridge 负责把回调适配成 {@code AgentStepVO} / SSE。
 */
public interface RunListener {

    /**
     * 一次工具调用完成（观察已拿到）。
     *
     * @param index       全局步序号（从 0 递增）
     * @param thought     本轮 assistant 在发起工具调用前的思考文本（可空）
     * @param toolName    工具名
     * @param toolArgs    工具入参（紧凑 JSON）
     * @param observation 工具结果文本（可能已被工具侧截断）
     */
    void onToolStep(int index, String thought, String toolName, String toolArgs, String observation);

    /**
     * 一次工具调用完成（带权限裁决外显，M4 §3.7）。默认转调不带裁决的重载，保持后向兼容；
     * bridge 覆写本方法即可把 {@code verdict}/{@code riskLevel} 映射进前端 AgentStepVO.permissionVerdict。
     *
     * @param verdict   权限裁决（ALLOW/ASK/DENY），可空
     * @param riskLevel 风险档位（A–E），可空
     */
    default void onToolStep(int index, String thought, String toolName, String toolArgs,
                            String observation, String verdict, String riskLevel) {
        onToolStep(index, thought, toolName, toolArgs, observation);
    }

    /**
     * 实时改动流（§3.7 可视化外显）：agent 每把一个文件写进影子区后实时回调一次，供前端编辑器像 Cursor 那样
     * 边写边高亮 diff。诚实边界：{@code before}/{@code after} 是「真目录基线 vs 影子区当前内容」——即用户看到的
     * 正是<b>影子区相对真目录的 diff</b>，改动仍隔离在影子区、未落真目录（accept 后才合并）。
     *
     * <p>默认空实现：非实时模式或不关心改动流的 listener 无需覆写。
     *
     * @param stepIndex   触发本次改动的步序号
     * @param sessionId   本 run 的会话 id（供前端逐处 accept/reject 时定位活动影子区，可空）
     * @param filePath    改动的文件（仓库相对路径）
     * @param changeType  CREATE（新建）/ WRITE（改已存在文件）
     * @param beforeContent 真目录基线内容（新建时为空串）
     * @param afterContent  影子区当前内容
     */
    default void onFileChange(int stepIndex, Long sessionId, String filePath, String changeType,
                              String beforeContent, String afterContent) {
    }

    /**
     * 会话/run 建立（begin 之后、主循环开跑之前）回调一次。bridge 可借此把「按 sessionId 的 emit 通道」
     * 绑定到本次 SSE emitter——用于 askUser 反问工具：工具挂起等待用户回复时，问题沿这条通道推给前端。
     * 默认空实现：非流式/不关心的 listener 无需覆写。
     */
    default void onSession(Long sessionId, Long runId) {
    }

    /** agent 产出最终答案（无 tool_call 的那轮 content）。 */
    void onFinalText(String text);

    /** 空实现：{@code run(spec)} 不带监听时使用。 */
    RunListener NOOP = new RunListener() {
        @Override
        public void onToolStep(int index, String thought, String toolName, String toolArgs, String observation) {
        }

        @Override
        public void onFinalText(String text) {
        }
    };
}
