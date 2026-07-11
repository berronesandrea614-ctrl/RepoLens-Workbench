package com.repolens.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.util.SsrfGuard;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.domain.entity.VerificationRunEntity;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.mapper.ToolCallLogMapper;
import com.repolens.mapper.VerificationRunMapper;
import com.repolens.service.ChangeRiskService;
import com.repolens.service.DependencyCheckService;
import com.repolens.service.RepoFileWriteService;
import com.repolens.service.ToolInvokeService;
import com.repolens.service.impl.support.CommandRunner;
import com.repolens.service.impl.support.WebFetcher;
import com.repolens.service.support.RepoWorkspaceResolver;
import com.repolens.tool.ReadonlyToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Stream;
import com.repolens.service.support.ShadowWorkspaceManager;
import com.repolens.service.support.VerificationOutputParser;
import com.repolens.service.support.RipgrepRunner;
import com.repolens.service.support.SessionFileReadTracker;
import com.repolens.service.support.SyntaxValidator;
import com.repolens.service.support.FeatureLedgerService;
import com.repolens.service.support.PersistentShell;
import java.nio.file.FileSystems;

/**
 * MVP 调试工具调用分发服务。
 * 作用：把 HTTP 的 toolName + payload 映射到只读工具服务，便于在不接 LLM 的情况下联调工具链路。
 *
 * 写工具（writeFileContent）只作为编码模式 agent 的一环存在：它的可达性由上层两道门把守——
 * (1) 只在 code 模式把该工具注入 agent 工具集；(2) 直连调试端点拒绝写工具名。
 * 这里只负责"真的被调用时"把改动<b>暂存</b>为 status=PROPOSED 的 file_change_log（不写盘），
 * 由人工经 apply 端点审批后才真正落盘——防止静默直写丢失审批门。
 *
 * 所有工具调用（含写工具、失败路径）均在 tool_call_log 中留有审计记录（失败安全）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolInvokeServiceImpl implements ToolInvokeService {

    private static final int MAX_LOG_JSON_CHARS = 2000;

    /** 验证工具超时：120 秒（可通过常量修改）。 */
    static final long VERIFY_TIMEOUT_MS = 120_000L;

    /** testFilter 白名单正则：只允许安全字符，防止命令注入。 */
    private static final java.util.regex.Pattern TEST_FILTER_SAFE =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.*#,]+");

    /** 工具调用日志序列化使用独立的 ObjectMapper（不注入，避免改变已有构造顺序过多）。 */
    private static final ObjectMapper LOG_MAPPER = new ObjectMapper();

    /** 构建文件探测时跳过的目录名（node_modules/target/.git/dist 不含源码）。 */
    private static final Set<String> BUILD_SKIP_DIRS =
            Set.of("node_modules", "target", ".git", "dist");

    /** listDirectory 一次最多返回的子项数。 */
    private static final int LIST_DIR_MAX_ITEMS = 200;

    /** webFetch maxChars 上限。 */
    private static final int WEB_FETCH_MAX_CHARS = 40_000;
    /** webFetch maxChars 默认值。 */
    private static final int WEB_FETCH_DEFAULT_CHARS = 20_000;

    private final ReadonlyToolService readonlyToolService;
    private final RepoFileWriteService repoFileWriteService;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final RepoMapper repoMapper;
    private final RepoWorkspaceResolver repoWorkspaceResolver;
    private final ToolCallLogMapper toolCallLogMapper;
    private final CommandRunner commandRunner;
    private final WebFetcher webFetcher;
    /** 依赖体检服务：三个写工具落库后 fire-and-forget 触发，失败完全静默。 */
    private final DependencyCheckService dependencyCheckService;
    /** 破坏性风险检测：四个写工具落库后 fire-and-forget 触发，失败完全静默。 */
    private final ChangeRiskService changeRiskService;

    private final ShadowWorkspaceManager shadowManager;
    private final VerificationOutputParser verificationOutputParser;
    private final RipgrepRunner ripgrepRunner;
    private final SessionFileReadTracker sessionFileReadTracker;
    private final SyntaxValidator syntaxValidator;
    private final VerificationRunMapper verificationRunMapper;
    private final FeatureLedgerService featureLedgerService;
    private final PersistentShell persistentShell;

    @Value("${repolens.agent.edit.require-read-first:true}")
    private boolean requireReadFirst;

    @Value("${repolens.agent.syntax-guard-enabled:true}")
    private boolean syntaxGuardEnabled;

    @Value("${repolens.agent.web-fetch-enabled:true}")
    private boolean webFetchEnabled;

    /**
     * 向后兼容重载（branchId=null）——所有现有调用方行为逐字节不变。
     */
    @Override
    public Object invoke(Long userId, Long repoId, Long sessionId, String toolName,
                         Map<String, Object> payload, Long llmCallId, String branchId) {
        if (!StringUtils.hasText(toolName)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "toolName is required");
        }
        Map<String, Object> args = payload == null ? Map.of() : payload;
        long startMs = System.currentTimeMillis();
        try {
            Object result = doInvoke(userId, repoId, sessionId, toolName, args, llmCallId, branchId);
            persistToolCallLogSafe(userId, repoId, sessionId, toolName, args, result,
                    System.currentTimeMillis() - startMs, true, null);
            return result;
        } catch (Exception ex) {
            persistToolCallLogSafe(userId, repoId, sessionId, toolName, args, null,
                    System.currentTimeMillis() - startMs, false, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 实际工具分发（原 invoke 中的 switch），由 invoke 包裹计时和日志后调用。
     * llmCallId 仅写工具使用（Feature F P1 溯源链接），只读工具忽略。
     * branchId=null 时行为与之前完全相同（向后兼容）。
     */
    private Object doInvoke(Long userId, Long repoId, Long sessionId, String toolName,
                            Map<String, Object> args, Long llmCallId, String branchId) {
        return switch (toolName) {
            case "searchCodeChunks" -> readonlyToolService.searchCodeChunks(
                    userId,
                    repoId,
                    asString(args, "query"),
                    asInteger(args, "topK"));
            case "getFileContent" -> {
                Object result = readonlyToolService.getFileContent(
                        userId,
                        repoId,
                        asString(args, "filePath"),
                        asInteger(args, "startLine"),
                        asInteger(args, "endLine"));
                try {
                    String fp = asString(args, "filePath");
                    if (fp != null && result instanceof Map<?,?> m) {
                        Object content = m.get("content");
                        if (content instanceof String s) {
                            sessionFileReadTracker.record(sessionId, fp, s);
                        }
                    }
                } catch (Exception ignored) {
                    // fail-safe recording
                }
                yield result;
            }
            case "findApiByPath" -> readonlyToolService.findApiByPath(
                    userId,
                    repoId,
                    asString(args, "apiPath"));
            case "findSymbolByName" -> readonlyToolService.findSymbolByName(
                    userId,
                    repoId,
                    asString(args, "symbolName"));
            case "findMethodCallers" -> readonlyToolService.findMethodCallers(
                    userId,
                    repoId,
                    asString(args, "symbolName"));
            case "findMethodCallees" -> readonlyToolService.findMethodCallees(
                    userId,
                    repoId,
                    asString(args, "symbolName"));
            case "analyzeImpact" -> readonlyToolService.analyzeImpact(
                    userId,
                    repoId,
                    asString(args, "className"),
                    asString(args, "methodName"));
            case "listDirectory" -> listDirectory(userId, repoId, sessionId, args);
            case "webFetch" -> webFetch(args);
            case "writeFileContent" -> writeFileContent(userId, repoId, sessionId, args, llmCallId, branchId);
            case "editFileContent" -> editFileContent(userId, repoId, sessionId, args, llmCallId, branchId);
            case "createFileContent" -> createFileContent(userId, repoId, sessionId, args, llmCallId, branchId);
            case "deleteFile" -> deleteFile(userId, repoId, sessionId, args, llmCallId, branchId);
            case "runVerification" -> runVerification(userId, repoId, sessionId, args);
            case "grepCode" -> grepCode(repoId, sessionId, args);
            case "globFiles" -> globFiles(repoId, sessionId, args);
            case "readFile" -> readFileWithLineNumbers(repoId, sessionId, args);
            case "multiEditFile" -> multiEditFile(userId, repoId, sessionId, args);
            case "planStructuredV2" -> args;
            case "bashExec" -> bashExec(repoId, args);
            case "bashOutput" -> bashOutput(args);
            case "killBash" -> killBash(args);
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "Unsupported toolName: " + toolName);
        };
    }

    /**
     * 落 tool_call_log 审计记录（失败安全：任何异常静默吞掉，不影响工具调用主链路）。
     * inputJson / outputJson 超过 {@link #MAX_LOG_JSON_CHARS} 字符时截断，避免大结果撑爆日志表。
     */
    private void persistToolCallLogSafe(Long userId, Long repoId, Long sessionId,
                                         String toolName, Map<String, Object> args,
                                         Object result, long costMs, boolean success, String errorMsg) {
        try {
            ToolCallLogEntity entity = new ToolCallLogEntity();
            entity.setUserId(userId);
            entity.setRepoId(repoId);
            entity.setSessionId(sessionId);
            entity.setToolName(toolName);
            entity.setInputJson(truncate(serializeQuietly(args), MAX_LOG_JSON_CHARS));
            entity.setOutputJson(success ? truncate(serializeQuietly(result), MAX_LOG_JSON_CHARS) : null);
            entity.setCostMs(costMs);
            entity.setSuccess(success);
            entity.setErrorMsg(success ? null : truncate(errorMsg, 500));
            toolCallLogMapper.insert(entity);
        } catch (Exception ex) {
            log.warn("persist tool_call_log failed, ignore, repoId={}, toolName={}, err={}",
                    repoId, toolName, ex.getMessage());
        }
    }

    private String serializeQuietly(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return LOG_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"serialization failed\"}";
        }
    }

    private String truncate(String s, int maxChars) {
        if (s == null || s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "...(truncated)";
    }

    /**
     * 写工具执行（STAGED，审批门）：<b>不写盘</b>。
     * 先做与 RepoFileWriteService 一致的路径安全校验（必须解析到仓库内一个已存在的常规文件），
     * 读取当前全文作为 oldContent，再落一条 status=PROPOSED 的 file_change_log（newContent=提议内容）。
     * 提议内容只活在 DB 里，直到人工调用 apply 才真正写盘。
     * 返回精简结果 {filePath, changeId, staged:true} 供 agent 观察——agent 只负责提议，人来审批。
     */
    private Object writeFileContent(Long userId, Long repoId, Long sessionId, Map<String, Object> args,
                                    Long llmCallId, String branchId) {
        String filePath = asString(args, "filePath");
        String content = asString(args, "content");
        if (!StringUtils.hasText(filePath)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "filePath is required");
        }
        if (content == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "content is required");
        }

        if (isProtectedPath(filePath)) {
            return Map.of("error", "受保护路径，禁止写入：" + filePath +
                    "。features.json 和 oracle 文件只能由验证系统管理。");
        }

        // 即使不写盘也保留同款路径安全：目标必须解析进仓库、且是一个已存在的常规文件，
        // 保证 PROPOSED 变更指向的是仓内合法路径（与 RepoFileWriteService.writeFile 同守卫）。
        String oldContent = resolveAndReadTarget(repoId, filePath);

        if (syntaxGuardEnabled) {
            SyntaxValidator.ValidationResult vr = syntaxValidator.validate(filePath, content);
            if (!vr.valid()) {
                return Map.of("error", "编辑后语法非法，改动已拒绝。请修正后重试。\n详情：" + vr.errorMessage());
            }
        }

        // 即时写入影子区（fail-safe：失败只 warn，回退到纯 DB 暂存）
        boolean wroteToShadow = false;
        try {
            RepoEntity repoForShadow = repoMapper.selectById(repoId);
            if (repoForShadow != null) {
                Path repoDir = repoWorkspaceResolver.resolveRepoDirectory(repoForShadow);
                Path shadow = shadowManager.resolveOrCreate(repoId, sessionId, repoDir);
                if (shadow != null) {
                    Path shadowTarget = repoWorkspaceResolver.resolveSafeFilePath(shadow, filePath);
                    Files.createDirectories(shadowTarget.getParent());
                    Files.writeString(shadowTarget, content, java.nio.charset.StandardCharsets.UTF_8);
                    wroteToShadow = true;
                }
            }
        } catch (Exception shadowEx) {
            log.warn("shadow write failed (writeFileContent) repoId={} path={}: {}",
                    repoId, filePath, shadowEx.getMessage());
        }

        FileChangeLogEntity change = new FileChangeLogEntity();
        change.setRepoId(repoId);
        change.setSessionId(sessionId);
        change.setFilePath(filePath);
        change.setOldContent(oldContent);
        change.setNewContent(content);
        change.setReverted(0);
        change.setStatus(FileChangeLogEntity.STATUS_PROPOSED);
        change.setOpType(FileChangeLogEntity.OP_TYPE_WRITE);
        change.setLlmCallId(llmCallId); // F P1: trace chain
        change.setBranchId(branchId);  // K P1: branch isolation (null = main-line, behaviour unchanged)
        change.setWrittenToShadow(wroteToShadow ? 1 : 0);
        fileChangeLogMapper.insert(change);
        // fire-and-forget 依赖体检（失败静默，绝不阻塞写工具链路）
        dependencyCheckService.triggerAsyncCheck(repoId, sessionId, change.getId());
        // fire-and-forget 破坏性风险检测（失败静默，绝不阻塞写工具链路）
        changeRiskService.triggerAsyncDetect(repoId, sessionId, change.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filePath", filePath);
        result.put("changeId", change.getId());
        result.put("staged", true);
        return result;
    }

    /**
     * editFileContent：str_replace 精准编辑（STAGED，审批门）。
     * oldString 必须在文件中唯一出现；替换后把全文旧内容和全文新内容一起暂存为 PROPOSED，
     * 复用现有 apply/reject 机制，前端零改动。
     */
    private Object editFileContent(Long userId, Long repoId, Long sessionId, Map<String, Object> args,
                                   Long llmCallId, String branchId) {
        String filePath = asString(args, "filePath");
        String oldString = asString(args, "oldString");
        String newString = asString(args, "newString");
        if (!StringUtils.hasText(filePath)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "filePath is required");
        }
        if (oldString == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "oldString is required");
        }
        if (newString == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "newString is required");
        }

        if (isProtectedPath(filePath)) {
            return Map.of("error", "受保护路径，禁止写入：" + filePath +
                    "。features.json 和 oracle 文件只能由验证系统管理。");
        }

        if (requireReadFirst) {
            java.util.Optional<String> recordedHash = sessionFileReadTracker.getHash(sessionId, filePath);
            if (recordedHash.isEmpty()) {
                return Map.of("error", "MUST_READ_FIRST: 请先用 getFileContent 或 readFile 读取该文件，再编辑。");
            }
            try {
                Path baseDir = getEffectiveBase(repoId, sessionId);
                Path target = repoWorkspaceResolver.resolveSafeFilePath(baseDir, filePath);
                String current = Files.exists(target) ? Files.readString(target) : "";
                String currentHash = sha256(current);
                if (!currentHash.isBlank() && !currentHash.equals(recordedHash.get())) {
                    return Map.of("error", "FILE_CHANGED: 文件自上次读取后已变化，请重新 getFileContent 后再编辑。");
                }
            } catch (Exception e) {
                log.warn("editFileContent: hash check failed (fail-safe, allowing): {}", e.getMessage());
            }
        }

        String oldContent = resolveAndReadTarget(repoId, filePath);

        // 唯一性检验：0 次 → 未找到；>1 次 → 不唯一
        int count = countOccurrences(oldContent, oldString);
        if (count == 0) {
            try {
                String firstLine = oldString.lines().findFirst().orElse("").trim();
                String hintPattern = firstLine.length() > 30 ? firstLine.substring(0, 30) : firstLine;
                RipgrepRunner.GrepResult hint = ripgrepRunner.grep(
                        hintPattern, getEffectiveBase(repoId, sessionId), null, false, RipgrepRunner.Mode.CONTEXT);
                String candidates = hint.output().lines().limit(3)
                        .reduce("", (a, b) -> a + b + "\n");
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "editFileContent 未找到 oldString：文件中不存在该字符串。候选行（供参考）：\n" + candidates);
            } catch (BizException be) {
                throw be;
            } catch (Exception e) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "editFileContent 未找到 oldString：文件中不存在该字符串，请检查内容是否完全匹配（含空白/换行）。");
            }
        }
        if (count > 1) {
            boolean replaceAll = Boolean.TRUE.equals(args.get("replaceAll"));
            if (!replaceAll) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "editFileContent 不唯一：oldString 在文件中出现了 " + count + " 次，请提供更多上下文以唯一定位，或传 replaceAll:true 替换全部。");
            }
        }

        String newContent = oldContent.replace(oldString, newString);

        if (syntaxGuardEnabled) {
            SyntaxValidator.ValidationResult vr = syntaxValidator.validate(filePath, newContent);
            if (!vr.valid()) {
                return Map.of("error", "编辑后语法非法，改动已拒绝。请修正后重试。\n详情：" + vr.errorMessage());
            }
        }

        // 即时写入影子区（fail-safe）
        boolean wroteToShadow = false;
        try {
            RepoEntity repoForShadow = repoMapper.selectById(repoId);
            if (repoForShadow != null) {
                Path repoDir = repoWorkspaceResolver.resolveRepoDirectory(repoForShadow);
                Path shadow = shadowManager.resolveOrCreate(repoId, sessionId, repoDir);
                if (shadow != null) {
                    Path shadowTarget = repoWorkspaceResolver.resolveSafeFilePath(shadow, filePath);
                    Files.createDirectories(shadowTarget.getParent());
                    Files.writeString(shadowTarget, newContent, java.nio.charset.StandardCharsets.UTF_8);
                    wroteToShadow = true;
                }
            }
        } catch (Exception shadowEx) {
            log.warn("shadow write failed (editFileContent) repoId={} path={}: {}",
                    repoId, filePath, shadowEx.getMessage());
        }

        FileChangeLogEntity change = new FileChangeLogEntity();
        change.setRepoId(repoId);
        change.setSessionId(sessionId);
        change.setFilePath(filePath);
        change.setOldContent(oldContent);
        change.setNewContent(newContent);
        change.setReverted(0);
        change.setStatus(FileChangeLogEntity.STATUS_PROPOSED);
        change.setOpType(FileChangeLogEntity.OP_TYPE_WRITE);
        change.setLlmCallId(llmCallId); // F P1: trace chain
        change.setBranchId(branchId);  // K P1: branch isolation (null = main-line, behaviour unchanged)
        change.setWrittenToShadow(wroteToShadow ? 1 : 0);
        fileChangeLogMapper.insert(change);
        // fire-and-forget 依赖体检（失败静默，绝不阻塞写工具链路）
        dependencyCheckService.triggerAsyncCheck(repoId, sessionId, change.getId());
        // fire-and-forget 破坏性风险检测（失败静默，绝不阻塞写工具链路）
        changeRiskService.triggerAsyncDetect(repoId, sessionId, change.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filePath", filePath);
        result.put("changeId", change.getId());
        result.put("staged", true);
        return result;
    }

    /** 统计 needle 在 haystack 中不重叠出现的次数。 */
    private int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * createFileContent：新建文件（STAGED，审批门）。
     * 目标路径必须不存在；父目录安全校验（含 symlink 防逃逸）；
     * old_content=空，new_content=content，PROPOSED；apply 时创建父目录+写文件。
     */
    private Object createFileContent(Long userId, Long repoId, Long sessionId, Map<String, Object> args,
                                     Long llmCallId, String branchId) {
        String filePath = asString(args, "filePath");
        String content = asString(args, "content");
        if (!StringUtils.hasText(filePath)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "filePath is required");
        }
        if (content == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "content is required");
        }

        if (isProtectedPath(filePath)) {
            return Map.of("error", "受保护路径，禁止写入：" + filePath +
                    "。features.json 和 oracle 文件只能由验证系统管理。");
        }

        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found: " + repoId);
        }
        Path repoDir = repoWorkspaceResolver.resolveRepoDirectory(repo);

        // 安全解析（新文件路径，允许不存在，但父目录需在仓库内且无 symlink 逃逸）
        Path target = repoWorkspaceResolver.resolveSafeNewFilePath(repoDir, filePath);

        if (syntaxGuardEnabled) {
            SyntaxValidator.ValidationResult vr = syntaxValidator.validate(filePath, content);
            if (!vr.valid()) {
                return Map.of("error", "编辑后语法非法，改动已拒绝。请修正后重试。\n详情：" + vr.errorMessage());
            }
        }

        // 即时写入影子区（fail-safe）
        boolean wroteToShadow = false;
        try {
            Path shadow = shadowManager.resolveOrCreate(repoId, sessionId, repoDir);
            if (shadow != null) {
                Path shadowTarget = repoWorkspaceResolver.resolveSafeNewFilePath(shadow, filePath);
                if (!Files.exists(shadowTarget)) {
                    Files.createDirectories(shadowTarget.getParent());
                    Files.writeString(shadowTarget, content, java.nio.charset.StandardCharsets.UTF_8);
                    wroteToShadow = true;
                }
            }
        } catch (Exception shadowEx) {
            log.warn("shadow write failed (createFileContent) repoId={} path={}: {}",
                    repoId, filePath, shadowEx.getMessage());
        }

        // 目标文件必须不存在
        if (Files.exists(target)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "文件已存在：" + filePath + "。请使用 editFileContent 或 writeFileContent 修改现有文件。");
        }

        FileChangeLogEntity change = new FileChangeLogEntity();
        change.setRepoId(repoId);
        change.setSessionId(sessionId);
        change.setFilePath(filePath);
        change.setOldContent("");  // 空字符串；opType=CREATE 是权威路由标志
        change.setNewContent(content);
        change.setReverted(0);
        change.setStatus(FileChangeLogEntity.STATUS_PROPOSED);
        change.setOpType(FileChangeLogEntity.OP_TYPE_CREATE);
        change.setLlmCallId(llmCallId); // F P1: trace chain
        change.setBranchId(branchId);  // K P1: branch isolation (null = main-line, behaviour unchanged)
        change.setWrittenToShadow(wroteToShadow ? 1 : 0);
        fileChangeLogMapper.insert(change);
        // fire-and-forget 依赖体检（失败静默，绝不阻塞写工具链路）
        dependencyCheckService.triggerAsyncCheck(repoId, sessionId, change.getId());
        // fire-and-forget 破坏性风险检测（失败静默，绝不阻塞写工具链路）
        changeRiskService.triggerAsyncDetect(repoId, sessionId, change.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filePath", filePath);
        result.put("changeId", change.getId());
        result.put("staged", true);
        return result;
    }

    /**
     * deleteFile：删除仓库内已存在的文件（STAGED，审批门，WRITE_TOOL_NAMES 成员 → code 模式 only）。
     * 不直接删盘：读取旧内容存档，落一条 op_type=DELETE、status=PROPOSED 的 file_change_log，
     * 用户 Accept 后才真正删除磁盘文件；Reject 则取消。
     * 路径安全与写工具一致：resolveSafeFilePath + 必须是已存在的常规文件。
     */
    private Object deleteFile(Long userId, Long repoId, Long sessionId, Map<String, Object> args,
                              Long llmCallId, String branchId) {
        String filePath = asString(args, "filePath");
        if (!StringUtils.hasText(filePath)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "filePath is required");
        }

        if (isProtectedPath(filePath)) {
            return Map.of("error", "受保护路径，禁止删除：" + filePath +
                    "。features.json 和 oracle 文件只能由验证系统管理。");
        }

        // 路径安全 + 读旧内容（作为 revert 时的恢复源）
        String oldContent = resolveAndReadTarget(repoId, filePath);

        // 在影子区删除对应文件（fail-safe）
        boolean wroteToShadow = false;
        try {
            RepoEntity repoForShadow = repoMapper.selectById(repoId);
            if (repoForShadow != null) {
                Path repoDir = repoWorkspaceResolver.resolveRepoDirectory(repoForShadow);
                Path shadow = shadowManager.resolveOrCreate(repoId, sessionId, repoDir);
                if (shadow != null) {
                    Path shadowTarget = repoWorkspaceResolver.resolveSafeFilePath(shadow, filePath);
                    Files.deleteIfExists(shadowTarget);
                    wroteToShadow = true;
                }
            }
        } catch (Exception shadowEx) {
            log.warn("shadow write failed (deleteFile) repoId={} path={}: {}",
                    repoId, filePath, shadowEx.getMessage());
        }

        FileChangeLogEntity change = new FileChangeLogEntity();
        change.setRepoId(repoId);
        change.setSessionId(sessionId);
        change.setFilePath(filePath);
        change.setOldContent(oldContent);
        change.setNewContent(null);
        change.setReverted(0);
        change.setStatus(FileChangeLogEntity.STATUS_PROPOSED);
        change.setOpType(FileChangeLogEntity.OP_TYPE_DELETE);
        change.setLlmCallId(llmCallId); // F P1: trace chain
        change.setBranchId(branchId);  // K P1: branch isolation (null = main-line, behaviour unchanged)
        change.setWrittenToShadow(wroteToShadow ? 1 : 0);
        fileChangeLogMapper.insert(change);
        // fire-and-forget 破坏性风险检测（deleteFile 无依赖体检，单独补 risk trigger，失败静默）
        changeRiskService.triggerAsyncDetect(repoId, sessionId, change.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filePath", filePath);
        result.put("changeId", change.getId());
        result.put("staged", true);
        return result;
    }

    /**
     * listDirectory：列出仓库内目录的直接子项（不递归）。
     * 路径安全：resolveSafeFilePath 防路径穿越 + symlink 逃逸。
     * 返回 {path, items:[{name, type, size?}], truncated}，最多 {@link #LIST_DIR_MAX_ITEMS} 条。
     */
    @SuppressWarnings("unchecked")
    private Object listDirectory(Long userId, Long repoId, Long sessionId, Map<String, Object> args) {
        String path = asString(args, "path");
        if (path == null || path.isBlank() || ".".equals(path.trim())) {
            path = "";
        }

        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found: " + repoId);
        }
        Path repoDir = repoWorkspaceResolver.resolveRepoDirectory(repo);
        // 优先读影子区（自愈时能看到 agent 刚写的内容）
        Path baseDir = shadowManager.resolveActive(repoId, sessionId).orElse(repoDir);

        Path targetDir;
        if (path.isEmpty()) {
            targetDir = baseDir;
        } else {
            targetDir = repoWorkspaceResolver.resolveSafeFilePath(baseDir, path);
        }

        if (!Files.isDirectory(targetDir)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Not a directory: " + (path.isEmpty() ? "." : path));
        }

        List<Path> children;
        try (Stream<Path> stream = Files.list(targetDir)) {
            children = stream
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Cannot list directory: " + e.getMessage());
        }

        boolean truncated = children.size() > LIST_DIR_MAX_ITEMS;
        List<Map<String, Object>> items = children.stream()
                .limit(LIST_DIR_MAX_ITEMS)
                .map(p -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", p.getFileName().toString());
                    boolean isDir = Files.isDirectory(p);
                    item.put("type", isDir ? "dir" : "file");
                    if (!isDir) {
                        try {
                            item.put("size", Files.size(p));
                        } catch (IOException ignore) {
                            // size unavailable: skip
                        }
                    }
                    return item;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path.isEmpty() ? "." : path);
        result.put("items", items);
        result.put("truncated", truncated);
        return result;
    }

    /**
     * webFetch：抓取外部 HTTP/HTTPS URL 内容。
     * 安全门：SSRF 校验在任何网络调用前完成（SsrfGuard.assertHostAllowed）。
     * 失败安全：网络错误/超时/SSRF 拒绝均返回 {status:-1, content:"抓取失败：<原因>"}，不抛 500。
     */
    @SuppressWarnings("unchecked")
    private Object webFetch(Map<String, Object> args) {
        String url = asString(args, "url");
        Integer maxCharsArg = asInteger(args, "maxChars");
        int maxChars = maxCharsArg == null ? WEB_FETCH_DEFAULT_CHARS
                : Math.min(maxCharsArg, WEB_FETCH_MAX_CHARS);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);

        if (!webFetchEnabled) {
            result.put("status", -1);
            result.put("content", "抓取失败：webFetch 工具已被管理员禁用（repolens.agent.web-fetch-enabled=false）");
            return result;
        }
        if (!StringUtils.hasText(url)) {
            result.put("status", -1);
            result.put("content", "抓取失败：url 参数不能为空");
            return result;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            result.put("status", -1);
            result.put("content", "抓取失败：仅支持 http:// 或 https:// 协议");
            return result;
        }

        // SSRF 校验（在发起任何网络请求前完成）
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            SsrfGuard.assertHostAllowed(host);
        } catch (BizException ex) {
            result.put("status", -1);
            result.put("content", "抓取失败：" + ex.getMessage());
            return result;
        } catch (Exception ex) {
            result.put("status", -1);
            result.put("content", "抓取失败：URL 解析错误 - " + ex.getMessage());
            return result;
        }

        // 实际 HTTP 抓取（DefaultWebFetcher 内部已做 15s 超时 + 512KB 限体 + 失败安全）
        WebFetcher.FetchResult fetchResult = webFetcher.fetch(url, maxChars);
        result.put("status", fetchResult.status());
        if (fetchResult.contentType() != null) {
            result.put("contentType", fetchResult.contentType());
        }
        result.put("truncated", fetchResult.truncated());
        result.put("content", fetchResult.content());
        return result;
    }

    /**
     * runVerification：在仓库工作副本执行编译或测试（code 模式 only，双闸保护）。
     * 从 workDir 向下广度优先（BFS）探测最近的 pom.xml 或 package.json（最多 3 层），
     * 用找到的目录作为实际构建 cwd；都没有才报错。
     * kind 枚举映射到固定命令模板，testFilter 只作为 -Dtest 值且须通过白名单正则，
     * ProcessBuilder 显式参数数组不走 shell -c，绝不拼接用户任意命令。
     */
    private Object runVerification(Long userId, Long repoId, Long sessionId, Map<String, Object> args) {
        String kind = asString(args, "kind");
        String testFilter = asString(args, "testFilter");

        if (!"build".equals(kind) && !"test".equals(kind)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "kind 必须为 \"build\" 或 \"test\"");
        }
        if ("test".equals(kind) && StringUtils.hasText(testFilter)
                && !TEST_FILTER_SAFE.matcher(testFilter).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "testFilter 包含不安全字符，只允许 [A-Za-z0-9_.*#,]+");
        }

        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found: " + repoId);
        }
        // 优先在影子区验证（agent 刚写的代码在影子里）
        Path workDir = shadowManager.resolveActive(repoId, sessionId)
                .orElseGet(() -> repoWorkspaceResolver.resolveRepoDirectory(repo));
        Path repoDir = repoWorkspaceResolver.resolveRepoDirectory(repo);

        // BFS 探测最近的构建配置文件（最多下探 3 层，跳过无关目录）
        BuildTarget target = findBuildTarget(workDir);
        if (target == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "不支持的项目类型：在仓库根目录（含 3 层子目录）未检测到 pom.xml 或 package.json，无法执行验证");
        }

        String[] command = buildVerifyCommand(target.type(), kind, testFilter);
        CommandRunner.RunResult runResult = commandRunner.run(command, target.dir(), VERIFY_TIMEOUT_MS);

        // 相对路径（供 agent 观察实际构建目录）
        String buildDirRelative;
        try {
            String rel = workDir.relativize(target.dir()).toString();
            buildDirRelative = rel.isEmpty() ? "." : rel;
        } catch (IllegalArgumentException e) {
            buildDirRelative = target.dir().toString();
        }

        // 解析结构化失败信息，喂回给 agent
        Path shadowForParse = shadowManager.resolveActive(repoId, sessionId).orElse(null);
        List<VerificationOutputParser.Failure> failures =
                verificationOutputParser.parse(runResult.outputTail(), shadowForParse);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exitCode", runResult.exitCode());
        result.put("timedOut", runResult.timedOut());
        result.put("passed", runResult.exitCode() == 0 && !runResult.timedOut());
        result.put("outputTail", runResult.outputTail());
        result.put("buildDir", buildDirRelative);
        result.put("failures", failures);
        result.put("summary", failures.isEmpty()
                ? "验证通过" : "发现 " + failures.size() + " 处失败，读 failures[].context 修正");

        String tamperEvidence = null;
        boolean oracleTampered = false;
        try {
            VerificationRunEntity runEntity = new VerificationRunEntity();
            runEntity.setRepoId(repoId);
            runEntity.setSessionId(sessionId);
            runEntity.setKind(kind);
            int exitCode = runResult.exitCode();
            runEntity.setExitCode(exitCode);
            runEntity.setPassed(exitCode == 0 && !runResult.timedOut());
            String outputTail = runResult.outputTail();
            runEntity.setOutputTail(outputTail != null && outputTail.length() > 4000
                    ? outputTail.substring(outputTail.length() - 4000) : outputTail);
            try {
                runEntity.setFailuresJson(new ObjectMapper().writeValueAsString(failures));
            } catch (Exception ignored) {}
            boolean isolated = false;
            try {
                ProcessBuilder pb = new ProcessBuilder("nslookup", "example.com");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                isolated = !done || p.exitValue() != 0;
            } catch (Exception ignored) {
                isolated = true;
            }
            runEntity.setNetworkIsolated(isolated);

            try {
                Path featuresPath = workDir.resolve(".repolens/oracle");
                if (Files.isDirectory(featuresPath)) {
                    java.nio.file.PathMatcher oracleMatcher =
                            java.nio.file.FileSystems.getDefault().getPathMatcher("glob:**/*Oracle*.java");
                    try (var stream = Files.walk(featuresPath)) {
                        List<Path> oracleFiles = stream
                                .filter(Files::isRegularFile)
                                .filter(p -> oracleMatcher.matches(p.getFileName()))
                                .sorted()
                                .toList();
                        StringBuilder sb = new StringBuilder();
                        for (Path f : oracleFiles) {
                            sb.append(Files.readString(f));
                        }
                        String currentHash = sha256(sb.toString());
                        String storedHash = readOracleHash(repoDir);
                        if (storedHash != null && !storedHash.isEmpty() && !storedHash.equals(currentHash)) {
                            tamperEvidence = "oracle files changed: hash mismatch (stored="
                                    + storedHash.substring(0, 8) + "..., current=" + currentHash.substring(0, 8) + "...)";
                        }
                        writeOracleHash(repoDir, currentHash);
                    }
                }
            } catch (Exception e) {
                log.warn("oracle tampering check failed (fail-safe): {}", e.getMessage());
            }
            oracleTampered = tamperEvidence != null;
            runEntity.setOracleTampered(oracleTampered);
            runEntity.setCreatedAt(java.time.LocalDateTime.now());
            verificationRunMapper.insert(runEntity);

            if (exitCode == 0) {
                try {
                    featureLedgerService.reconcile(repoWorkspaceResolver.resolveRepoDirectory(repo), testFilter);
                } catch (Exception e) {
                    log.warn("reconcile features failed (fail-safe): {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("verification_run insert failed (fail-safe): {}", e.getMessage());
        }

        if (oracleTampered) {
            result.put("oracleTampered", true);
            result.put("tamperEvidence", tamperEvidence);
        }

        return result;
    }

    /**
     * BFS 探测 workDir 树（最多 3 层深）中最近的 pom.xml 或 package.json。
     * 忽略 node_modules/target/.git/dist 目录。返回 null 表示未找到。
     */
    private BuildTarget findBuildTarget(Path workDir) {
        record Entry(Path dir, int depth) {}
        Queue<Entry> queue = new ArrayDeque<>();
        queue.add(new Entry(workDir, 0));

        while (!queue.isEmpty()) {
            Entry entry = queue.poll();
            Path dir = entry.dir();
            int d = entry.depth();

            if (Files.exists(dir.resolve("pom.xml"))) {
                return new BuildTarget(dir, "maven");
            }
            if (Files.exists(dir.resolve("package.json"))) {
                return new BuildTarget(dir, "npm");
            }

            if (d < 3) {
                try (Stream<Path> children = Files.list(dir)) {
                    List<Path> subdirs = children
                            .filter(Files::isDirectory)
                            .filter(p -> !BUILD_SKIP_DIRS.contains(p.getFileName().toString()))
                            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                            .toList();
                    for (Path sub : subdirs) {
                        queue.add(new Entry(sub, d + 1));
                    }
                } catch (IOException ignored) {
                    // unreadable directory: skip
                }
            }
        }
        return null;
    }

    /** 构建目标：目录 + 类型（maven/npm）。 */
    private record BuildTarget(Path dir, String type) {}

    /**
     * 根据项目类型（maven/npm）和 kind 映射到白名单命令数组。
     * filter 已通过白名单正则验证，仅用作 -Dtest 值（非 shell 拼接）。
     * 不使用 -o（离线）标志，避免依赖未缓存时验证必失败。
     */
    private String[] buildVerifyCommand(String type, String kind, String testFilter) {
        if ("maven".equals(type)) {
            if ("build".equals(kind)) {
                return new String[]{"mvn", "-q", "compile"};
            } else { // test
                if (StringUtils.hasText(testFilter)) {
                    return new String[]{"mvn", "-q", "test", "-Dtest=" + testFilter};
                } else {
                    return new String[]{"mvn", "-q", "test"};
                }
            }
        } else { // npm
            if ("build".equals(kind)) {
                return new String[]{"npm", "run", "build", "--silent"};
            } else {
                return new String[]{"npm", "test", "--silent"};
            }
        }
    }

    /**
     * 校验目标路径并读取其当前全文（作为变更前的旧内容）。
     * 路径安全不失败安全——仓库不存在 / 文件不存在 / 越界都直接抛 BizException，
     * 避免把 PROPOSED 变更挂到一个非法或不存在的路径上。
     */
    private String resolveAndReadTarget(Long repoId, String filePath) {
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found: " + repoId);
        }
        Path repoDir = repoWorkspaceResolver.resolveRepoDirectory(repo);
        Path target = repoWorkspaceResolver.resolveSafeFilePath(repoDir, filePath);
        if (!Files.isRegularFile(target)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Not an existing regular file: " + filePath);
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.warn("read current content for staged change failed, repoId={}, path={}, err={}",
                    repoId, filePath, ex.getMessage());
            throw new BizException(ErrorCode.BAD_REQUEST, "Unable to read target file: " + filePath);
        }
    }

    private String asString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Invalid integer for " + key);
        }
    }

    private Map<String, Object> grepCode(Long repoId, Long sessionId, Map<String, Object> p) {
        String pattern = getString(p, "pattern");
        if (!StringUtils.hasText(pattern)) {
            return Map.of("error", "pattern is required");
        }
        String glob = (String) p.get("glob");
        String modeStr = (String) p.getOrDefault("mode", "context");
        boolean ci = Boolean.TRUE.equals(p.get("caseInsensitive"));
        RipgrepRunner.Mode mode = switch (modeStr) {
            case "files" -> RipgrepRunner.Mode.FILES_WITH_MATCHES;
            case "count" -> RipgrepRunner.Mode.COUNT;
            default -> RipgrepRunner.Mode.CONTEXT;
        };
        Path base = getEffectiveBase(repoId, sessionId);
        RipgrepRunner.GrepResult result = ripgrepRunner.grep(pattern, base, glob, ci, mode);
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("output", result.output());
        ret.put("truncated", result.truncated());
        if (result.timedOut()) ret.put("timedOut", true);
        return ret;
    }

    private Map<String, Object> globFiles(Long repoId, Long sessionId, Map<String, Object> p) {
        String pattern = getString(p, "pattern");
        if (!StringUtils.hasText(pattern)) {
            return Map.of("error", "pattern is required");
        }
        Path base = getEffectiveBase(repoId, sessionId);
        java.nio.file.PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        int globMax = 100;
        List<Map<String, Object>> files = new ArrayList<>();
        boolean[] truncated = {false};
        try {
            Files.walkFileTree(base, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(
                        Path dir, java.nio.file.attribute.BasicFileAttributes a) {
                    return BUILD_SKIP_DIRS.contains(dir.getFileName().toString())
                            ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                            : java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path file, java.nio.file.attribute.BasicFileAttributes a) {
                    if (files.size() >= globMax) {
                        truncated[0] = true;
                        return java.nio.file.FileVisitResult.TERMINATE;
                    }
                    Path rel = base.relativize(file);
                    if (matcher.matches(rel) || matcher.matches(file.getFileName())) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("path", rel.toString());
                        entry.put("mtime", a.lastModifiedTime().toMillis());
                        entry.put("size", a.size());
                        files.add(entry);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            log.warn("globFiles walk failed: {}", e.getMessage());
        }
        files.sort(Comparator.comparingLong(m -> -((Long) m.get("mtime"))));
        return Map.of("files", files, "truncated", truncated[0]);
    }

    private Path getEffectiveBase(Long repoId, Long sessionId) {
        try {
            return shadowManager.resolveActive(repoId, sessionId)
                    .orElseGet(() -> {
                        RepoEntity repo = repoMapper.selectById(repoId);
                        return repo != null
                                ? repoWorkspaceResolver.resolveRepoDirectory(repo)
                                : Path.of(".");
                    });
        } catch (Exception e) {
            log.warn("getEffectiveBase failed: {}", e.getMessage());
            RepoEntity repo = repoMapper.selectById(repoId);
            return repo != null
                    ? repoWorkspaceResolver.resolveRepoDirectory(repo)
                    : Path.of(".");
        }
    }

    private static String getString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v instanceof String s ? s : null;
    }

    private Map<String, Object> readFileWithLineNumbers(Long repoId, Long sessionId,
                                                         Map<String, Object> p) {
        String filePath = getString(p, "filePath");
        if (!StringUtils.hasText(filePath)) {
            return Map.of("error", "filePath is required");
        }
        int offset = p.get("offset") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        int limit = p.get("limit") instanceof Number n ? Math.min(5000, Math.max(1, n.intValue())) : 2000;
        try {
            Path base = getEffectiveBase(repoId, sessionId);
            Path target = repoWorkspaceResolver.resolveSafeFilePath(base, filePath);
            if (!Files.exists(target)) {
                return Map.of("error", "文件不存在：" + filePath);
            }
            List<String> lines = Files.readAllLines(target);
            int from = offset - 1;
            int to = Math.min(from + limit, lines.size());
            if (from >= lines.size()) {
                return Map.of("content", "", "totalLines", lines.size());
            }
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                sb.append(String.format("%4d\t%s%n", i + 1, lines.get(i)));
            }
            sessionFileReadTracker.record(sessionId, filePath,
                    String.join("\n", lines));
            return Map.of("content", sb.toString(), "totalLines", lines.size(),
                    "offset", offset, "limit", limit);
        } catch (Exception e) {
            log.warn("readFile failed: {}", e.getMessage());
            return Map.of("error", "读取失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> multiEditFile(Long userId, Long repoId, Long sessionId,
                                               Map<String, Object> p) {
        String filePath = getString(p, "filePath");
        if (isProtectedPath(filePath)) {
            return Map.of("error", "受保护路径，禁止写入：" + filePath +
                    "。features.json 和 oracle 文件只能由验证系统管理。");
        }

        if (requireReadFirst) {
            java.util.Optional<String> recordedHash = sessionFileReadTracker.getHash(sessionId, filePath);
            if (recordedHash.isEmpty()) {
                return Map.of("error", "MUST_READ_FIRST: 请先用 getFileContent 或 readFile 读取该文件，再编辑。");
            }
            try {
                Path baseDir = getEffectiveBase(repoId, sessionId);
                Path targetCheck = repoWorkspaceResolver.resolveSafeFilePath(baseDir, filePath);
                String current = Files.exists(targetCheck) ? Files.readString(targetCheck) : "";
                String currentHash = sha256(current);
                if (!currentHash.isBlank() && !currentHash.equals(recordedHash.get())) {
                    return Map.of("error", "FILE_CHANGED: 文件自上次读取后已变化，请重新 getFileContent 后再编辑。");
                }
            } catch (Exception e) {
                log.warn("multiEditFile: hash check failed (fail-safe, allowing): {}", e.getMessage());
            }
        }

        Object editsObj = p.get("edits");
        if (!StringUtils.hasText(filePath) || !(editsObj instanceof List)) {
            return Map.of("error", "filePath 和 edits 必填");
        }
        List<Map<String, Object>> edits = (List<Map<String, Object>>) editsObj;
        if (edits.isEmpty()) {
            return Map.of("error", "edits 不能为空");
        }
        try {
            Path base = getEffectiveBase(repoId, sessionId);
            Path target = repoWorkspaceResolver.resolveSafeFilePath(base, filePath);
            if (!Files.exists(target)) {
                return Map.of("error", "文件不存在：" + filePath);
            }
            String content = Files.readString(target);
            for (int i = 0; i < edits.size(); i++) {
                Map<String, Object> edit = edits.get(i);
                String oldStr = getString(edit, "oldString");
                String newStr = getString(edit, "newString");
                if (!StringUtils.hasText(oldStr)) {
                    return Map.of("error", "edit[" + i + "].oldString 不能为空");
                }
                if (!content.contains(oldStr)) {
                    return Map.of("error", "edit[" + i + "].oldString 未找到，整体拒绝，无改动已应用。");
                }
                content = content.replace(oldStr, newStr == null ? "" : newStr);
            }
            if (syntaxGuardEnabled) {
                SyntaxValidator.ValidationResult vr = syntaxValidator.validate(filePath, content);
                if (!vr.valid()) {
                    return Map.of("error", "multiEdit 后语法非法，整体拒绝。详情：" + vr.errorMessage());
                }
            }
            try {
                Files.writeString(target, content);
            } catch (Exception e) {
                log.warn("multiEditFile: write to shadow failed (fail-safe): {}", e.getMessage());
            }
            FileChangeLogEntity change = new FileChangeLogEntity();
            change.setRepoId(repoId);
            change.setSessionId(sessionId);
            change.setFilePath(filePath);
            String old = "";
            try {
                Path effectiveTarget = repoWorkspaceResolver.resolveSafeFilePath(
                        getEffectiveBase(repoId, sessionId), filePath);
                if (Files.exists(effectiveTarget)) {
                    old = Files.readString(effectiveTarget);
                }
            } catch (Exception e) {
                log.warn("multiEditFile read old content failed: {}", e.getMessage());
            }
            change.setOldContent(old);
            change.setNewContent(content);
            change.setReverted(0);
            change.setStatus(FileChangeLogEntity.STATUS_PROPOSED);
            change.setOpType(FileChangeLogEntity.OP_TYPE_WRITE);
            change.setLlmCallId(null);
            change.setBranchId(null);
            change.setWrittenToShadow(1);
            fileChangeLogMapper.insert(change);
            dependencyCheckService.triggerAsyncCheck(repoId, sessionId, change.getId());
            changeRiskService.triggerAsyncDetect(repoId, sessionId, change.getId());
            return Map.of("success", true, "appliedEdits", edits.size());
        } catch (Exception e) {
            log.warn("multiEditFile failed: {}", e.getMessage());
            return Map.of("error", "multiEditFile 执行失败：" + e.getMessage());
        }
    }

    private static boolean isProtectedPath(String filePath) {
        if (filePath == null) return false;
        String normalized = filePath.replace('\\', '/');
        if (normalized.startsWith(".git/") || normalized.contains("/.git/")) return true;
        if (normalized.startsWith("node_modules/") || normalized.contains("/node_modules/")) return true;
        if (normalized.startsWith("target/") || normalized.contains("/target/")) return true;
        if (normalized.startsWith("dist/") || normalized.contains("/dist/")) return true;
        if (normalized.startsWith(".repolens/") || normalized.contains("/.repolens/")) return true;
        if (normalized.contains(".env") || normalized.contains("secret") || normalized.contains("credentials")) return true;
        if (normalized.contains("src/test/") && normalized.contains("Oracle")) return true;
        return false;
    }

    private Map<String, Object> bashExec(Long repoId, Map<String, Object> args) {
        String command = asString(args, "command");
        String cwd = asString(args, "cwd");
        if (cwd == null) {
            RepoEntity repo = repoMapper.selectById(repoId);
            cwd = repoWorkspaceResolver.resolveRepoDirectory(repo).toString();
        }
        PersistentShell.ShellResult r = persistentShell.exec(command, cwd);
        return Map.of("shellId", r.shellId() != null ? r.shellId() : "", "output", r.output(), "exitCode", r.exitCode());
    }

    private Map<String, Object> bashOutput(Map<String, Object> args) {
        String shellId = asString(args, "shellId");
        return Map.of("output", persistentShell.getOutput(shellId != null ? shellId : ""));
    }

    private Map<String, Object> killBash(Map<String, Object> args) {
        String shellId = asString(args, "shellId");
        return Map.of("killed", persistentShell.kill(shellId != null ? shellId : ""));
    }

    private static String sha256(byte[] data) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) { return ""; }
    }

    private static String sha256(String content) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }

    private String readOracleHash(Path repoDir) {
        try {
            Path file = repoDir.resolve(".repolens/oracle/_hash");
            if (Files.exists(file)) return Files.readString(file).trim();
        } catch (Exception ignored) {}
        return null;
    }

    private void writeOracleHash(Path repoDir, String hash) {
        try {
            Path oracleDir = repoDir.resolve(".repolens/oracle");
            Files.createDirectories(oracleDir);
            Files.writeString(oracleDir.resolve("_hash"), hash);
        } catch (Exception ignored) {}
    }
}
