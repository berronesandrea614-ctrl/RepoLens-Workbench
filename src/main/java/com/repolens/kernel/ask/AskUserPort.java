package com.repolens.kernel.ask;

/**
 * 反问用户的端口（内核 zone 定义，app bridge 实现）。
 *
 * <p>让自主 agent 能在「需要用户拍板方案 / 补充关键需求」时主动停下来问一句，而不是要么闷头按臆测继续、
 * 要么整体停摆。内核只声明契约；真正的 SSE 挂起、回复回传、上限/超时保守策略由 bridge 侧实现
 * （因为要触碰 web 层的 emitter 与请求-回复配对）。
 *
 * <p>软依赖：无实现（如非流式/纯内核单测环境）时，{@link AskUserTool} 直接降级为「按最合理方案继续」，
 * 不阻塞、不报错——保持 fail-safe。
 */
public interface AskUserPort {

    /**
     * 向用户提一到多个问题（结构化，前端做成多选卡片）并阻塞等待回复。
     *
     * @param sessionId 当前会话 id（emit 通道按它绑定）
     * @param spec      结构化反问规格（问题 + 选项）
     * @return 用户回复；若达提问上限/超时/无可用通道，返回一段引导 agent 自主继续的保守文本（绝不返回 null）
     */
    String ask(Long sessionId, AskSpec spec);
}
