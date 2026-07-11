package com.repolens.service;

import com.repolens.domain.vo.DependencyCheckVO;

import java.util.List;

/**
 * 依赖体检服务。编排提取器 + Registry + Typosquat 检测，并落库审计记录。
 */
public interface DependencyCheckService {

    /**
     * 对指定的 file_change_log 条目执行依赖体检，落库并返回结果。
     *
     * @param repoId    仓库 id（权限边界）
     * @param sessionId 会话 id（用于聚合查询）
     * @param changeIds 要体检的 file_change_log.id 列表
     * @return 体检结果列表
     */
    List<DependencyCheckVO> checkByChangeIds(Long repoId, Long sessionId, List<Long> changeIds);

    /**
     * 查询某会话下已体检的全部结果（供前端轮询/展示）。
     *
     * @param repoId    仓库 id
     * @param sessionId 会话 id
     * @return 该会话的全部体检记录
     */
    List<DependencyCheckVO> queryBySession(Long repoId, Long sessionId);

    /**
     * 对指定会话下的全部 file_change_log 条目执行依赖体检，落库并返回结果。
     * 与 checkByChangeIds 相同逻辑，但 changeIds 由服务层从 DB 查询，简化调用方。
     *
     * @param repoId    仓库 id
     * @param sessionId 会话 id
     * @return 体检结果列表
     */
    List<DependencyCheckVO> checkBySession(Long repoId, Long sessionId);

    /**
     * fire-and-forget 入口：ToolInvokeServiceImpl 三个写工具落库后异步触发。
     * 方法本身不阻塞，内部提交到有界线程池，失败完全静默（日志记录）。
     *
     * @param repoId    仓库 id
     * @param sessionId 会话 id
     * @param changeId  刚落库的 file_change_log.id
     */
    void triggerAsyncCheck(Long repoId, Long sessionId, Long changeId);
}
