package com.repolens.kernel.tools;

import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.ToolDefinition;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * bash 工具：把 M2 的 {@link PersistentShell} 接入内核工具注册表（写类，串行调度）。
 *
 * <p>在<b>影子区</b>执行命令（隔离副本，不碰真目录），env/cwd 跨调用存活。命令安全由
 * {@link CommandSafetyChecker} 在 {@code exec} 内部把关：破坏性命令 DENY、bash 里跑 grep/find 被 STEER。
 * 归为写类工具，走串行调度以维持「唯一写路径」。
 */
@Component
public class BashTool implements KernelTool {

    /** 默认超时秒（agent 未指定时）。 */
    private static final long DEFAULT_TIMEOUT_SEC = 120;

    private final PersistentShell shell;

    public BashTool(PersistentShell shell) {
        this.shell = shell;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition d = new ToolDefinition();
        d.setName("bash");
        d.setDescription("在仓库影子副本里执行 shell 命令（env/cwd 跨调用持久）。"
                + "做什么：运行 command，回 stdout/stderr 与退出码。"
                + "何时用：构建、运行脚本、git 只读查询等。"
                + "何时不用：搜索代码用 grep、找文件用 glob、读文件用 read（绝不在 bash 里跑这些）；"
                + "破坏性命令与 git push 会被拒。"
                + "示例：{\"command\":\"mvn -o -q compile\",\"timeout_sec\":300}");
        d.setParameters(Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string", "description", "要执行的 shell 命令"),
                        "timeout_sec", Map.of("type", "integer", "description", "超时秒（默认 120）"),
                        "run_in_background", Map.of("type", "boolean", "description", "后台运行，立即返回 PID（默认 false）")),
                "required", List.of("command")));
        return d;
    }

    @Override
    public String execute(ToolContext ctx, Map<String, Object> args) {
        Object cv = args == null ? null : args.get("command");
        String command = cv == null ? null : String.valueOf(cv);
        if (command == null || command.isBlank()) {
            return "bash 失败：缺少 command。";
        }
        String sessionId = String.valueOf(ctx.sessionId());
        Path workdir = ctx.shadow().root();

        if (bool(args, "run_in_background")) {
            PersistentShell.BackgroundHandle h = shell.runInBackground(sessionId, workdir, command);
            if (h == null) {
                return "bash 后台启动失败（可能被安全检查拒绝）。";
            }
            return "已后台启动：bgId=" + h.bgId() + " pid=" + h.pid() + " 日志=" + h.logFile();
        }

        long timeout = longArg(args, "timeout_sec", DEFAULT_TIMEOUT_SEC);
        PersistentShell.ExecResult r = shell.exec(sessionId, workdir, command, timeout);
        if (r.blocked()) {
            return "bash 被安全检查拦截：" + r.blockReason();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("退出码 ").append(r.exitCode());
        if (r.timedOut()) {
            sb.append("（超时）");
        }
        if (r.overflowPath() != null) {
            sb.append("（输出过大，完整内容见 ").append(r.overflowPath()).append("）");
        }
        sb.append("：\n").append(r.output() == null || r.output().isEmpty() ? "（无输出）" : r.output());
        return sb.toString();
    }

    private static boolean bool(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    private static long longArg(Map<String, Object> a, String k, long dflt) {
        Object v = a == null ? null : a.get(k);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
