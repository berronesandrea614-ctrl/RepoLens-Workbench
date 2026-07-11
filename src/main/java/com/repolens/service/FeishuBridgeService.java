package com.repolens.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.util.RepoUrlValidator;
import com.repolens.domain.entity.FeishuBindingEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.domain.vo.FeishuBindingVO;
import com.repolens.mapper.FeishuBindingMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.mapper.ToolCallLogMapper;
import com.repolens.mcp.McpUiActionBroker;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.support.ClaudeOutputParser;
import com.repolens.service.impl.support.CommandRunner;
import com.repolens.service.support.CryptoService;
import com.repolens.service.support.RepoWorkspaceResolver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Feishu bridge service.
 *
 * <p>Manages the full lifecycle of Feishu bot bindings:
 * <ol>
 *   <li>CRUD — create/list/delete bindings; appSecret stored AES-256-GCM encrypted.</li>
 *   <li>Lifecycle — @PostConstruct reconnects all CONNECTED bindings; @PreDestroy closes all.</li>
 *   <li>Downstream (Feishu → frontend): received text messages are audited then forwarded
 *       to the frontend via {@link McpUiActionBroker#push}.</li>
 *   <li>Upstream (PTY → Feishu): PTY output chunks are accumulated per-repo in a
 *       {@link ClaudeOutputParser}, a background {@link ScheduledExecutorService} flushes
 *       idle buffers and sends the resulting turn back to Feishu.</li>
 * </ol>
 *
 * <p><b>Fail-safe iron rule</b>: all Feishu SDK calls are wrapped in try/catch;
 * failures never propagate to crash the application or block PTY output processing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuBridgeService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STATUS_CONNECTED    = "CONNECTED";
    private static final String STATUS_DISCONNECTED = "DISCONNECTED";
    private static final String STATUS_ERROR        = "ERROR";

    private final FeishuClient          feishuClient;
    private final FeishuBindingMapper   feishuBindingMapper;
    private final CryptoService         cryptoService;
    private final McpUiActionBroker     mcpUiActionBroker;
    private final ToolCallLogMapper     toolCallLogMapper;
    private final PermissionService     permissionService;
    private final CommandRunner         commandRunner;
    private final RepoWorkspaceResolver workspaceResolver;
    private final RepoMapper            repoMapper;
    private final RepoUrlValidator      repoUrlValidator;

    /** claude CLI 可执行路径（默认 "claude" 走 PATH；后端 PATH 可能不含 npm-global，可配绝对路径）。 */
    @Value("${repolens.feishu.claude-bin:claude}")
    private String claudeBin;

    /** headless 模式单条指令超时（毫秒）。 */
    @Value("${repolens.feishu.headless-timeout-ms:180000}")
    private long headlessTimeoutMs;

    /** headless 模式开关：true=飞书指令用 `claude -p` 跑（干净文本，推荐）；false=旧的写进 TUI 模式。 */
    @Value("${repolens.feishu.headless-mode:true}")
    private boolean headlessMode;

    /** 串行执行飞书 headless 指令（一个绑定一次跑一条，避免并发抢同一项目目录）。 */
    private final ExecutorService headlessExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "feishu-headless");
        t.setDaemon(true);
        return t;
    });

    /** Active bindings keyed by repoId. Package-private for test access. */
    final Map<Long, FeishuBindingEntity> activeBindings = new ConcurrentHashMap<>();

    /** Per-repoId ClaudeOutputParser instances. Package-private for test access. */
    final Map<Long, ClaudeOutputParser> parsers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "feishu-flush");
        t.setDaemon(true);
        return t;
    });

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void loadConnectedBindings() {
        // 后端进程的 PATH 常不含 npm-global（claude 的默认安装位置），导致 headless
        // `claude -p` 报 command-not-found。启动时自动探测 claude 绝对路径，无需手动 export。
        resolveClaudeBin();

        try {
            List<FeishuBindingEntity> connected = feishuBindingMapper.selectList(
                    Wrappers.<FeishuBindingEntity>lambdaQuery()
                            .eq(FeishuBindingEntity::getStatus, STATUS_CONNECTED));
            log.info("[Feishu] Loading {} CONNECTED binding(s) at startup", connected.size());
            for (FeishuBindingEntity binding : connected) {
                connectBinding(binding);
            }
        } catch (Exception e) {
            log.error("[Feishu] Failed to load connected bindings at startup: {}", e.getMessage());
        }

        // Periodic flush: every 300 ms check all parsers for idle turns
        flushExecutor.scheduleWithFixedDelay(this::flushAllParsers, 300, 300, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void disconnectAll() {
        flushExecutor.shutdownNow();
        headlessExecutor.shutdownNow();
        for (FeishuBindingEntity binding : activeBindings.values()) {
            try {
                feishuClient.disconnect(binding.getAppId());
            } catch (Exception e) {
                log.warn("[Feishu] Error disconnecting binding id={}: {}", binding.getId(), e.getMessage());
            }
        }
        activeBindings.clear();
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public FeishuBindingVO create(Long userId, Long repoId, String botName, String appId, String appSecret) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        // Uniqueness check: one binding per appId globally
        long count = feishuBindingMapper.selectCount(
                Wrappers.<FeishuBindingEntity>lambdaQuery()
                        .eq(FeishuBindingEntity::getAppId, appId));
        if (count > 0) {
            throw new BizException(ErrorCode.CONFLICT, "appId already bound: " + appId);
        }

        // Encrypt secret — never store plaintext
        String encSecret = cryptoService.encrypt(appSecret);

        FeishuBindingEntity entity = new FeishuBindingEntity();
        entity.setUserId(userId);
        entity.setRepoId(repoId);
        entity.setBotName(botName);
        entity.setAppId(appId);
        entity.setAppSecretEnc(encSecret);
        entity.setStatus(STATUS_DISCONNECTED);
        // 一机器人一个固定 Claude 会话：生成固定 UUID，飞书每条消息 --resume 接着它跑（跨重启记忆不丢）。
        entity.setClaudeSessionId(java.util.UUID.randomUUID().toString());
        entity.setSessionStarted(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        feishuBindingMapper.insert(entity);

        // Attempt connection (fail-safe: failure updates status=ERROR, doesn't throw)
        connectBinding(entity);

        return toVO(entity);
    }

    public List<FeishuBindingVO> list(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return feishuBindingMapper.selectList(
                        Wrappers.<FeishuBindingEntity>lambdaQuery()
                                .eq(FeishuBindingEntity::getRepoId, repoId))
                .stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    public void delete(Long userId, Long repoId, Long bindingId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        FeishuBindingEntity binding = feishuBindingMapper.selectById(bindingId);
        if (binding == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Feishu binding not found: " + bindingId);
        }
        if (!binding.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        if (!binding.getRepoId().equals(repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "binding does not belong to repo: " + repoId);
        }

        try {
            feishuClient.disconnect(binding.getAppId());
        } catch (Exception e) {
            log.warn("[Feishu] Disconnect error during delete bindingId={}: {}", bindingId, e.getMessage());
        }
        activeBindings.remove(binding.getRepoId());
        parsers.remove(binding.getRepoId());
        feishuBindingMapper.deleteById(bindingId);
    }

    public boolean testConnection(Long userId, Long repoId, String appId, String appSecret) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        try {
            feishuClient.connect(appId, appSecret, msg -> {});
            feishuClient.disconnect(appId);
            return true;
        } catch (Exception e) {
            log.warn("[Feishu] Test connection failed for appId={}: {}", appId, e.getMessage());
            return false;
        }
    }

    // ── Upstream: PTY → Feishu ────────────────────────────────────────────────

    /**
     * Called by {@code FeishuPtyController} when PTY output chunk arrives.
     * Feeds the chunk into the per-repo parser. Fail-safe.
     */
    public void onPtyOutput(Long userId, Long repoId, String chunk) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "no permission");
        }
        // headless 模式下不用电脑 TUI 窗口的输出（headless 有自己的干净回复）。
        // 忽略上行 PTY，避免用户在电脑 Claude 窗口按 shift+tab 切模式产生的
        // "accept edits on / auto mode on" 状态行碎片泄露到飞书。
        if (headlessMode) {
            return;
        }
        try {
            FeishuBindingEntity binding = activeBindings.get(repoId);
            if (binding == null) {
                log.debug("[Feishu] No active binding for repoId={}, ignoring PTY output", repoId);
                return;
            }
            ClaudeOutputParser parser = parsers.computeIfAbsent(repoId, k -> new ClaudeOutputParser());
            parser.feed(chunk, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("[Feishu] onPtyOutput error repoId={}: {}", repoId, e.getMessage());
        }
    }

    // ── Downstream: Feishu → frontend ─────────────────────────────────────────

    private void onFeishuMessage(FeishuBindingEntity binding, String text) {
        Long repoId = binding.getRepoId();

        // Audit log (fail-safe)
        auditFeishuCommand(binding.getUserId(), repoId, text);

        if (headlessMode) {
            // ★ headless 模式：每条飞书指令用 `claude -p` 在真实项目目录跑一次，
            // 直接拿到干净的纯文本答复推回飞书——不进 TUI，无屏幕刷新/自动更新噪声，
            // 从根源解决「TUI 碎片刷屏」。串行执行（一次一条）避免抢同一项目目录。
            headlessExecutor.submit(() -> runHeadlessAndReply(binding, text));
            return;
        }

        // 旧行为（TUI 模式，保留兜底）：写进前端活跃的 Claude PTY 窗口
        boolean pushed = mcpUiActionBroker.push("feishu_input",
                Map.of("repoId", repoId,
                        "ptyId", 10000L + repoId,
                        "text",  text));
        if (!pushed) {
            sendReplySafe(binding, "⚠ app 未在前台/未连接");
        }
    }

    /**
     * headless 下行：在绑定 repo 的真实项目目录里跑 {@code claude -p "<指令>"}，
     * 把干净的 stdout 推回飞书。全程 fail-safe，任何失败回一条可读错误、绝不抛。
     */
    private void runHeadlessAndReply(FeishuBindingEntity binding, String text) {
        Long repoId = binding.getRepoId();
        try {
            // 解析真实项目目录（claude -p 的 cwd 必须是用户真实项目，不是快照副本）
            RepoEntity repo = repoMapper.selectById(repoId);
            if (repo == null) { sendReplySafe(binding, "⚠ 仓库不存在，无法执行"); return; }
            // claude -p 的 cwd 必须是用户真实项目目录。本地 file:// 仓库直接解析其真实路径，
            // 不能用 repo-storage-root 下的快照副本——本地导入根本不生成该快照，之前恒报
            // "Local repository not found, import repository first"（飞书回错的真凶）。
            // 非本地（远端 clone）仓库仍回退到快照目录。
            Path cwd;
            try {
                String repoUrl = repo.getRepoUrl();
                if (repoUrl != null && repoUrl.startsWith("file://")) {
                    cwd = repoUrlValidator.resolveLocalRepoPath(repoUrl);
                    if (!java.nio.file.Files.isDirectory(cwd)) {
                        sendReplySafe(binding, "⚠ 本地项目目录不存在：" + cwd
                                + "\n该项目可能已被移动/删除，请在 app 中重新导入，或把机器人改绑到仍存在的项目。");
                        return;
                    }
                } else {
                    cwd = workspaceResolver.resolveRepoDirectory(repo);
                }
            } catch (Exception e) {
                sendReplySafe(binding, "⚠ 无法定位项目目录：" + safeMsg(e));
                return;
            }

            sendReplySafe(binding, "⏳ 正在执行…");

            // claude -p "<prompt>"：非交互 print 模式，输出即干净答复。
            // --permission-mode acceptEdits：自动接受文件编辑（能改代码/写文件/答问/常规操作），
            //   但删库/rm-rf/推送等危险操作仍需授权（飞书上会给提示而非直接执行）——安全与能力的平衡。
            // stdin 由 CommandRunner 重定向自 /dev/null，无 "no stdin" 警告。
            // 一机器人 ↔ 一个持久 Claude 会话：固定 sessionId，首次用 --session-id 建，之后 --resume 续（记忆连续）。
            String sessionId = binding.getClaudeSessionId();
            boolean started = binding.getSessionStarted() != null && binding.getSessionStarted() == 1;
            String[] cmd;
            if (sessionId != null && !sessionId.isBlank()) {
                String sessionFlag = started ? "--resume" : "--session-id";
                cmd = new String[]{ claudeBin, "-p", text, sessionFlag, sessionId, "--permission-mode", "acceptEdits" };
            } else {
                cmd = new String[]{ claudeBin, "-p", text, "--permission-mode", "acceptEdits" };
            }
            CommandRunner.RunResult r = commandRunner.run(cmd, cwd, headlessTimeoutMs);

            // 首次成功建会话后标记 session_started=1，之后走 --resume。
            if (sessionId != null && !sessionId.isBlank() && !started && !r.timedOut() && r.exitCode() == 0) {
                try {
                    binding.setSessionStarted(1);
                    binding.setUpdatedAt(LocalDateTime.now());
                    feishuBindingMapper.updateById(binding);
                } catch (Exception ex) {
                    log.warn("[Feishu] mark session_started failed repoId={}: {}", repoId, ex.getMessage());
                }
            }

            String reply;
            if (r.timedOut()) {
                reply = "⏱ 执行超时（>" + (headlessTimeoutMs / 1000) + "s），已终止。";
            } else if (r.exitCode() != 0) {
                String tail = r.outputTail() == null ? "" : r.outputTail().strip();
                reply = "⚠ 执行失败（exit " + r.exitCode() + "）\n"
                        + (tail.isEmpty() ? "(无输出)" : truncate(tail, 1500));
            } else {
                String out = r.outputTail() == null ? "" : r.outputTail().strip();
                reply = out.isEmpty() ? "✅ 执行完成（无文本输出）" : "✅ " + truncate(out, 3000);
            }
            sendReplySafe(binding, reply);
        } catch (Exception e) {
            log.warn("[Feishu] headless run failed repoId={}: {}", repoId, e.getMessage());
            sendReplySafe(binding, "⚠ 执行出错：" + safeMsg(e));
        }
    }

    /** 给绑定的飞书对话发一条消息，fail-safe。 */
    private void sendReplySafe(FeishuBindingEntity binding, String message) {
        try {
            String secret = cryptoService.decrypt(binding.getAppSecretEnc());
            feishuClient.sendMessage(binding.getAppId(), secret, message);
        } catch (Exception e) {
            log.warn("[Feishu] Failed to send reply for repoId={}: {}",
                    binding.getRepoId(), e.getMessage());
        }
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    /**
     * 启动时解析 claude 可执行路径。若配置的 {@link #claudeBin} 已是可执行的绝对路径则直用；
     * 否则依次探测常见安装位置（npm-global / homebrew / /usr/local / ~/.local），
     * 找到即改用绝对路径，避免后端 PATH 不含 claude 导致 headless 报 command-not-found。
     * 全 fail-safe：探测失败保持原值（仍可靠 REPOLENS_CLAUDE_BIN / PATH）。
     */
    private void resolveClaudeBin() {
        try {
            // 已配绝对路径且可执行 → 直用
            java.io.File configured = new java.io.File(claudeBin);
            if (configured.isAbsolute() && configured.canExecute()) {
                log.info("[Feishu] headless claude bin = {} (configured)", claudeBin);
                return;
            }
            String home = System.getProperty("user.home", "");
            String[] candidates = {
                    home + "/.npm-global/bin/claude",
                    "/opt/homebrew/bin/claude",
                    "/usr/local/bin/claude",
                    home + "/.claude/local/claude",
                    home + "/.local/bin/claude",
            };
            for (String c : candidates) {
                java.io.File f = new java.io.File(c);
                if (f.canExecute()) {
                    claudeBin = c;
                    log.info("[Feishu] headless claude bin auto-detected = {}", c);
                    return;
                }
            }
            log.info("[Feishu] headless claude bin = {} (relying on PATH; set REPOLENS_CLAUDE_BIN if not found)", claudeBin);
        } catch (Exception e) {
            log.warn("[Feishu] resolveClaudeBin failed, keeping '{}': {}", claudeBin, e.getMessage());
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void connectBinding(FeishuBindingEntity binding) {
        try {
            String secret = cryptoService.decrypt(binding.getAppSecretEnc());
            feishuClient.connect(binding.getAppId(), secret,
                    text -> onFeishuMessage(binding, text));

            binding.setStatus(STATUS_CONNECTED);
            binding.setLastError(null);
            binding.setUpdatedAt(LocalDateTime.now());
            feishuBindingMapper.updateById(binding);
            activeBindings.put(binding.getRepoId(), binding);
            log.info("[Feishu] Binding {} connected (repoId={})", binding.getId(), binding.getRepoId());
        } catch (Exception e) {
            log.error("[Feishu] Connect failed for binding id={}: {}", binding.getId(), e.getMessage());
            binding.setStatus(STATUS_ERROR);
            binding.setLastError(truncate(e.getMessage(), 500));
            binding.setUpdatedAt(LocalDateTime.now());
            try {
                feishuBindingMapper.updateById(binding);
            } catch (Exception ex) {
                log.warn("[Feishu] Could not update binding status to ERROR: {}", ex.getMessage());
            }
        }
    }

    /** Periodic flush — called by flushExecutor every 300 ms. Package-private for test access. */
    void flushAllParsers() {
        long now = System.currentTimeMillis();
        parsers.forEach((repoId, parser) -> {
            try {
                Optional<ClaudeOutputParser.Turn> turnOpt = parser.flushIfIdle(now);
                turnOpt.ifPresent(turn -> sendTurnToFeishu(repoId, turn));
            } catch (Exception e) {
                log.warn("[Feishu] flushAllParsers error repoId={}: {}", repoId, e.getMessage());
            }
        });
    }

    private void sendTurnToFeishu(Long repoId, ClaudeOutputParser.Turn turn) {
        FeishuBindingEntity binding = activeBindings.get(repoId);
        if (binding == null) return;
        // 双保险：空/纯装饰内容的回合不推送（防止 Claude TUI 刷新造成的 emoji 刷屏）。
        if (turn == null || turn.text() == null || turn.text().strip().length() < 2) return;

        String emoji = switch (turn.state()) {
            case DONE               -> "✅";
            case RUNNING            -> "⏳";
            case WAITING_PERMISSION -> "⚠";
            case WAITING_INPUT      -> "⚠";
        };
        String message = emoji + " " + turn.text();

        try {
            String secret = cryptoService.decrypt(binding.getAppSecretEnc());
            feishuClient.sendMessage(binding.getAppId(), secret, message);
        } catch (Exception e) {
            log.warn("[Feishu] sendTurnToFeishu failed repoId={}: {}", repoId, e.getMessage());
        }
    }

    private void auditFeishuCommand(Long userId, Long repoId, String text) {
        try {
            ToolCallLogEntity entity = new ToolCallLogEntity();
            entity.setUserId(userId);
            entity.setRepoId(repoId);
            entity.setToolName("feishu_remote_command");
            entity.setInputJson(MAPPER.writeValueAsString(Map.of("text", text == null ? "" : text)));
            entity.setSuccess(true);
            entity.setCostMs(0L);
            toolCallLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[Feishu] Audit log failed repoId={}: {}", repoId, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...(truncated)";
    }

    private FeishuBindingVO toVO(FeishuBindingEntity e) {
        FeishuBindingVO vo = new FeishuBindingVO();
        vo.setId(e.getId());
        vo.setRepoId(e.getRepoId());
        vo.setSessionId(e.getSessionId());
        vo.setBotName(e.getBotName());
        vo.setAppId(e.getAppId());
        vo.setStatus(e.getStatus());
        vo.setLastError(e.getLastError());
        vo.setCreatedAt(e.getCreatedAt());
        return vo;
    }
}
