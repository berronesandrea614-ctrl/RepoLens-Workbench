package com.repolens.service;

import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.CodeReferenceVO;
import com.repolens.domain.vo.RequirementVO;

import java.util.List;
import java.util.Optional;

/**
 * 需求存储层。负责在某 (user, repo) 维度下列出、查看与删除已沉淀的需求条目。
 * 所有方法均先做 repo 权限校验，并在读取 / 删除时校验需求归属 (user, repo)。
 */
public interface RequirementService {

    /**
     * 列出该 (user, repo) 全部需求，最新在前；每条附带去重文件数 fileCount。
     */
    List<RequirementVO> list(Long userId, Long repoId);

    /**
     * 查看单条需求。需求不存在抛 NOT_FOUND，不属于该 (user, repo) 抛 FORBIDDEN。
     */
    RequirementVO get(Long userId, Long repoId, Long requirementId);

    /**
     * 删除单条需求及其关联的 requirement_symbol；同样校验归属。
     */
    void delete(Long userId, Long repoId, Long requirementId);

    /**
     * 从一轮问答的引用中沉淀一条需求（状态 "SUMMARIZED"）。
     * 为每个不同的代码位点 (filePath, startLine) 插入一条 requirement_symbol，
     * 尽力用 repoId + className + methodName 解析出 symbolId（解析不到则留空，仅定位到文件行）。
     * 调用方保证 title 非空；references 为空时仍会创建需求（fileCount 0）。
     * 失败安全由调用方负责，本方法自身保持简洁。
     * 返回刚创建的需求 VO（fileCount 为其关联的去重文件数）。
     *
     * @param agentRunId 关联的 agent_run.id（无 agent run 的路径传 null）
     * @param approach   AI 整体思路一句话（无计划且抽取器未生成时传 null）
     */
    RequirementVO enqueue(Long userId, Long repoId, Long sessionId, String title, String summary,
                          List<CodeReferenceVO> references, Long agentRunId, String approach);

    /**
     * 构建某需求的调用子图：以该需求关联的代码位点为种子（symbolId 优先，缺省时用 filePath 解析出
     * 该文件下的全部符号），对每个种子调用 {@code codeGraphService.buildGraph(callees, depth 2)}，
     * 再按 node.id / edge.id 并集合并为一张图。种子上限 20；合并节点超过上限或任一子图被截断时标记 truncated。
     * 无可解析种子时返回空图（nodes/edges 空，nodeCount 0）。单个子图构建失败会被跳过（失败安全）。
     */
    CodeGraphVO requirementGraph(Long userId, Long repoId, Long requirementId);

    /**
     * 手动沉淀入口：取该会话最新一轮 USER 提问 + ASSISTANT 回答，交由 {@link com.repolens.service.impl.support.RequirementExtractor}
     * 归纳；命中则以空引用列表 enqueue 出一条需求并返回其 VO；未归纳出任何内容时抛 BAD_REQUEST。
     */
    RequirementVO summarize(Long userId, Long repoId, Long sessionId);

    /**
     * 外部改动归纳入口（Claude Code 文件监听路径）。
     *
     * <p>读取 {@code changedFiles} 中各文件的当前内容，通过 RequirementExtractor 归纳成一条需求，
     * 落库时 {@code source="external"}。失败安全：文件读取失败时跳过；归纳无结果时返回 empty。
     *
     * @param changedFiles 变更的相对文件路径列表
     * @param realDir      用户真实项目目录（绝对路径）；null 时降级为工作区快照目录
     * @param sessionHint  可选的上下文提示（Claude 的 session intent 等）
     * @return 成功归纳时返回 RequirementVO，否则返回 empty
     */
    Optional<RequirementVO> summarizeExternal(Long userId, Long repoId,
                                               List<String> changedFiles,
                                               String realDir, String sessionHint);
}
