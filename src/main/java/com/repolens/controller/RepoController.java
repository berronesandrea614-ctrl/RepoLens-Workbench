package com.repolens.controller;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.result.Result;
import com.repolens.domain.dto.chat.CodeAnswerRequest;
import com.repolens.domain.dto.rag.RagSearchRequest;
import com.repolens.domain.dto.repo.CreateRepoRequest;
import com.repolens.domain.dto.repo.ReindexRequest;
import com.repolens.domain.entity.IndexTaskEntity;
import com.repolens.domain.vo.BuildChunkResultVO;
import com.repolens.domain.vo.CodeAnswerVO;
import com.repolens.domain.vo.ImportRepoResultVO;
import com.repolens.domain.vo.IndexTaskVO;
import com.repolens.domain.vo.AsyncIndexResultVO;
import com.repolens.domain.vo.ParseRepoResultVO;
import com.repolens.domain.vo.RagSearchResultVO;
import com.repolens.domain.vo.RepoVO;
import com.repolens.domain.vo.SyncIndexResultVO;
import com.repolens.domain.vo.VectorizeResultVO;
import com.repolens.service.CodeAnswerService;
import com.repolens.service.ChunkVectorizeService;
import com.repolens.service.CodeChunkBuildService;
import com.repolens.service.GitRepositoryService;
import com.repolens.service.IndexTaskService;
import com.repolens.service.JavaCodeParseService;
import com.repolens.service.RagRetrievalService;
import com.repolens.service.RepoAsyncIndexService;
import com.repolens.service.RepoService;
import com.repolens.service.ToolInvokeService;
import com.repolens.security.AuthUserId;
import com.repolens.service.impl.support.AgentToolCatalog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 仓库维度主控制器。
 * 这里集中暴露 RepoLens 最核心的演示接口：
 * 1. 仓库创建与状态查询；
 * 2. 同步索引调试链路；
 * 3. 异步索引入口与任务查询；
 * 4. RAG 检索、代码问答、只读工具调用。
 *
 * 控制器只负责参数接收和结果包装，
 * 真正的权限校验、状态流转和降级策略都下沉在 service 层。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos")
public class RepoController {

    private final RepoService repoService;
    private final IndexTaskService indexTaskService;
    private final GitRepositoryService gitRepositoryService;
    private final JavaCodeParseService javaCodeParseService;
    private final CodeChunkBuildService codeChunkBuildService;
    private final ChunkVectorizeService chunkVectorizeService;
    private final RagRetrievalService ragRetrievalService;
    private final ToolInvokeService toolInvokeService;
    private final CodeAnswerService codeAnswerService;
    private final RepoAsyncIndexService repoAsyncIndexService;
    private final com.repolens.bridge.KernelAgentService kernelAgentService;

    /** 增量替换开关：开启后 code 模式流式走新内核 agent 主循环，默认关闭走旧路径。 */
    @org.springframework.beans.factory.annotation.Value("${repolens.kernel.agent-enabled:false}")
    private boolean kernelAgentEnabled;

    /**
     * 创建仓库元数据，并初始化第一条 CLONE_REPO 任务。
     */
    @PostMapping
    public Result<RepoVO> createRepo(@AuthUserId Long userId,
                                     @Valid @RequestBody CreateRepoRequest request) {
        return Result.success(repoService.createRepo(userId, request));
    }

    /**
     * 列出当前用户可访问的全部仓库，供桌面端仓库列表 / 切换使用。
     * 访问边界与单仓查询一致：只返回用户所属 workspace 下的仓库。
     */
    @GetMapping
    public Result<List<RepoVO>> listRepos(@AuthUserId Long userId) {
        return Result.success(repoService.listRepos(userId));
    }

    /**
     * 查询仓库当前状态，常用于观察 latestCommitId 与 indexStatus。
     */
    @GetMapping("/{id}")
    public Result<RepoVO> getRepo(@AuthUserId Long userId,
                                  @PathVariable("id") Long repoId) {
        return Result.success(repoService.getRepo(userId, repoId));
    }

    /**
     * 删除仓库及其全部衍生数据。
     * 适用于任何 indexStatus（含 FAILED / 重复导入的残留仓库），彻底清理 DB 事实表、
     * Milvus 向量与磁盘工作副本，避免孤儿数据无限堆积。权限在 service 层收口。
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteRepo(@AuthUserId Long userId,
                                   @PathVariable("id") Long repoId) {
        repoService.deleteRepo(userId, repoId);
        return Result.success(null);
    }

    /**
     * 查询某个仓库的任务列表，用于观察异步索引状态机和重试情况。
     */
    @GetMapping("/{id}/tasks")
    public Result<List<IndexTaskVO>> listTasks(@AuthUserId Long userId,
                                               @PathVariable("id") Long repoId) {
        return Result.success(repoService.listRepoTasks(userId, repoId));
    }

    /**
     * 手动创建一条重新索引任务，适合调试幂等键与重复请求。
     */
    @PostMapping("/{id}/reindex")
    public Result<IndexTaskVO> reindex(@AuthUserId Long userId,
                                       @PathVariable("id") Long repoId,
                                       @RequestBody(required = false) ReindexRequest request) {
        return Result.success(repoService.reindexRepo(userId, repoId, request));
    }

    /**
     * 同步导入仓库。
     * 如果还没有可复用的 clone 任务，这里会补建一条手动导入任务。
     */
    @PostMapping("/{id}/import")
    public Result<ImportRepoResultVO> importRepo(@AuthUserId Long userId,
                                                 @PathVariable("id") Long repoId) {
        RepoVO repo = repoService.getRepo(userId, repoId);
        IndexTaskEntity task = indexTaskService.findLatestCloneTaskForImport(repoId);
        if (task == null) {
            task = indexTaskService.createManualImportCloneTask(repoId, repo.getBranchName());
        }
        return Result.success(gitRepositoryService.importRepository(repoId, task.getId(), userId));
    }

    /**
     * 同步解析 Java 代码，生成 class/method/api/dependency 等结构化符号。
     */
    @PostMapping("/{id}/parse")
    public Result<ParseRepoResultVO> parseRepo(@AuthUserId Long userId,
                                               @PathVariable("id") Long repoId) {
        return Result.success(javaCodeParseService.parseRepository(repoId, userId));
    }

    /**
     * 同步构建 code_chunk，为 RAG 检索准备最小召回粒度。
     */
    @PostMapping("/{id}/chunks/build")
    public Result<BuildChunkResultVO> buildChunks(@AuthUserId Long userId,
                                                  @PathVariable("id") Long repoId) {
        return Result.success(codeChunkBuildService.buildChunks(repoId, userId));
    }

    /**
     * 同步完成 embedding 与 Milvus 写入。
     */
    @PostMapping("/{id}/vectors/build")
    public Result<VectorizeResultVO> buildVectors(@AuthUserId Long userId,
                                                  @PathVariable("id") Long repoId) {
        return Result.success(chunkVectorizeService.vectorizeRepoChunks(repoId, userId));
    }

    /**
     * 直接调试 RAG 召回效果，不经过 LLM。
     */
    @PostMapping("/{id}/rag/search")
    public Result<RagSearchResultVO> ragSearch(@AuthUserId Long userId,
                                               @PathVariable("id") Long repoId,
                                               @Valid @RequestBody RagSearchRequest request) {
        return Result.success(ragRetrievalService.retrieve(repoId, userId, request.getQuery(), request.getTopK()));
    }

    /**
     * 只读工具统一入口（调试用）。
     * 安全边界：此端点只暴露只读工具，明确拒绝写工具（如 writeFileContent）——
     * 写能力只能经由 code 模式的 agent 链路触达，不允许从这里直接调用。
     * 会话无关，故 sessionId 传 null；具体工具白名单、参数解析和审计记录都在 service 层完成。
     */
    @PostMapping("/{id}/tools/{toolName}/invoke")
    public Result<Object> invokeTool(@AuthUserId Long userId,
                                     @PathVariable("id") Long repoId,
                                     @PathVariable("toolName") String toolName,
                                     @RequestBody(required = false) Map<String, Object> payload) {
        if (toolName != null && (AgentToolCatalog.WRITE_TOOL_NAMES.contains(toolName)
                || AgentToolCatalog.EXEC_TOOL_NAMES.contains(toolName))) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "Code-mode-only tools are not allowed via direct tool-invoke endpoint: " + toolName);
        }
        return Result.success(toolInvokeService.invoke(userId, repoId, null, toolName, payload));
    }

    /**
     * 基于 RAG 证据生成带引用的代码问答结果。
     */
    @PostMapping("/{id}/chat/answer")
    public Result<CodeAnswerVO> answer(@AuthUserId Long userId,
                                       @PathVariable("id") Long repoId,
                                       @Valid @RequestBody CodeAnswerRequest request) {
        return Result.success(codeAnswerService.answer(repoId, userId, request));
    }

    /**
     * 流式代码问答：SSE 逐 token 推送最终答案，前端可渐进式渲染。
     * 与非流式 /chat/answer 完全同源（同样的权限/历史/RAG/记忆/降级），只是输出改为流式。
     * emitter 的实际推送在 service 的后台线程完成，本方法拿到 emitter 立即返回。
     */
    @PostMapping(value = "/{id}/chat/answer/stream", produces = "text/event-stream")
    public SseEmitter answerStream(@AuthUserId Long userId,
                                   @PathVariable("id") Long repoId,
                                   @Valid @RequestBody CodeAnswerRequest request) {
        // 自主 agent 一次运行会连续多轮工具调用（可达数分钟），且 askUser 反问要挂起等用户回复——
        // 故超时放到 10min，避免 auto 长跑或提问等待期间容器提前掐断流。
        SseEmitter emitter = new SseEmitter(600_000L);
        // 统一入口：开关打开即全部走新内核 agent 主循环（不再区分问答/编码——五档权限模式已涵盖，
        // PLAN 档=只读问答，其余档可改代码）。开关关闭时才回落旧 CodeAnswerService，保证默认不回归。
        if (kernelAgentEnabled) {
            kernelAgentService.answerStream(repoId, userId, request, emitter);
        } else {
            codeAnswerService.answerStream(repoId, userId, request, emitter);
        }
        return emitter;
    }

    /**
     * 批准 PLAN 模式产出的结构化计划，以 ACCEPT_EDITS 模式重新执行编码。
     */
    @PostMapping("/{id}/chat/approve-plan/{runId}")
    public Result<CodeAnswerVO> approvePlan(@AuthUserId Long userId,
                                            @PathVariable("id") Long repoId,
                                            @PathVariable Long runId) {
        return Result.success(codeAnswerService.approvePlan(repoId, userId, runId));
    }

    /**
     * 提交异步索引入口。
     * 该接口只负责“任务落库 + 尝试发消息”，真正的多阶段执行在 MQ 消费侧。
     */
    @PostMapping("/{id}/index/async")
    public Result<AsyncIndexResultVO> submitAsyncIndex(
            @AuthUserId Long userId,
            @PathVariable("id") Long repoId) {
        return Result.success(repoAsyncIndexService.submitAsyncIndex(repoId, userId));
    }

    /**
     * 同步一键索引：当前线程按顺序执行 import → parse → chunks → vectors 并返回汇总。
     * 给前端/一键脚本提供单次调用入口，替代串行调 4 个同步端点。
     * 权限校验与 repo 锁在 service 层收口；结束时 repo 必为 INDEXED 或 FAILED。
     */
    @PostMapping("/{id}/index/sync")
    public Result<SyncIndexResultVO> syncIndex(
            @AuthUserId Long userId,
            @PathVariable("id") Long repoId) {
        return Result.success(repoAsyncIndexService.runSyncIndex(repoId, userId));
    }
}
