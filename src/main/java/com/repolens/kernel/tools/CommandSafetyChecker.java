package com.repolens.kernel.tools;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 受控 Bash 的命令安全闸门。分两类拦截，返回原因串（放行返回 {@code null}）：
 *
 * <ol>
 *   <li><b>DENY（破坏性/系统级）</b>：rm -rf 打到根/家目录/通配、sudo、dd 写块设备、mkfs、fork 炸弹、
 *       curl/wget 管道进 shell、关机/重启、chmod 777 根、写 /dev/sd* 等。命中即拒。</li>
 *   <li><b>STEER（grep 铁律）</b>：在 bash 里跑 grep/rg/find/fd/ag/ack 等检索命令一律拒绝，
 *       引导 agent 改用专用 {@code grepCode}/{@code glob} 工具（结构化、限量、遵守 ignore、可视化）。</li>
 * </ol>
 *
 * <p><b>诚实边界</b>：这是<b>启发式</b>闸门，按操作符粗切成简单命令、看 argv[0] + 少量强模式。
 * 真正的命令级词法解析（引号/转义/别名/变量展开绕过，20+ 绕过用例）是后续 Part7 7.7
 * {@code ShellCommandAnalyzer} 的活；此处不谎称"完备防护"，只挡住常见误伤与检索误用。
 */
@Component("kernelCommandSafetyChecker")
public class CommandSafetyChecker {

    /** 按 shell 控制/管道/重定向操作符粗切出各简单命令，逐段独立判定。 */
    private static final Pattern OPERATOR_SPLIT = Pattern.compile("(&&|\\|\\||[;|&\\n])");

    /** 检索类命令：一律引导到专用工具，不在 bash 里跑（grep 铁律）。 */
    private static final Set<String> SEARCH_STEER = Set.of(
            "grep", "egrep", "fgrep", "rg", "ripgrep", "ag", "ack", "find", "fd", "fdfind");

    /** argv[0] 命中即拒的系统级破坏命令。 */
    private static final Set<String> DANGEROUS_HEAD = Set.of(
            "sudo", "doas", "mkfs", "shutdown", "reboot", "halt", "poweroff", "init", "dd");

    private static final List<Pattern> DENY_PATTERNS = List.of(
            // rm 递归+强制 打到根 / 家目录 / 通配（rm -rf ./sub 这类相对子目录不误伤）
            Pattern.compile("\\brm\\b[^\\n]*\\s-\\S*[rf]\\S*[^\\n]*\\s(/|~|\\$HOME|\\*)(\\s|$|/)"),
            Pattern.compile("\\brm\\b\\s+-[rf]{1,2}\\s+(/|~|\\*)(\\s|$)"),
            // fork 炸弹 :(){ :|:& };:
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{"),
            // 管道进 shell 执行（远程代码执行）
            Pattern.compile("\\b(curl|wget|fetch)\\b[^\\n]*\\|\\s*(sudo\\s+)?(ba|z|k|d|fi)?sh\\b"),
            // 写裸块设备
            Pattern.compile(">\\s*/dev/(sd|nvme|disk|rdisk)\\w*"),
            Pattern.compile("\\bof=/dev/(sd|nvme|disk|rdisk)"),
            // 根目录全递归改权限/属主
            Pattern.compile("\\bchmod\\b\\s+-\\S*R\\S*\\s+[0-7]{3,4}\\s+/(\\s|$)"),
            Pattern.compile("\\bchown\\b\\s+-\\S*R\\S*[^\\n]*\\s/(\\s|$)"),
            // 覆盖磁盘/清空重要挂载
            Pattern.compile("\\bmkfs\\b"),
            // 误伤远端：默认禁 git push（写操作走显式审批，不从 bash 偷跑）
            Pattern.compile("\\bgit\\s+push\\b")
    );

    /**
     * @return 拦截原因串；{@code null} 表示放行。原因前缀 {@code DENY:} / {@code STEER:} 便于上层区分处置。
     */
    public String check(String command) {
        if (command == null || command.isBlank()) {
            return "DENY: 空命令";
        }
        String lower = command.toLowerCase(Locale.ROOT);

        for (Pattern p : DENY_PATTERNS) {
            if (p.matcher(lower).find()) {
                return "DENY: 命中破坏性命令模式 /" + p.pattern() + "/，已拒绝执行";
            }
        }

        for (String segment : OPERATOR_SPLIT.split(command)) {
            String head = firstToken(segment);
            if (head == null) {
                continue;
            }
            String base = baseName(head);
            if (DANGEROUS_HEAD.contains(base)) {
                return "DENY: 系统级危险命令 '" + base + "'，已拒绝执行";
            }
            if (SEARCH_STEER.contains(base)) {
                return "STEER: 禁止在 bash 里跑 '" + base + "'（grep 铁律）。请改用专用 grepCode/glob 工具"
                        + "——它结构化限量、遵守 ignore、结果可进可视化，比裸命令更可靠。";
            }
        }
        return null;
    }

    /** 取一段简单命令的首个有效 token（跳过 env 赋值前缀如 {@code FOO=1 cmd}）。 */
    private String firstToken(String segment) {
        for (String tok : segment.trim().split("\\s+")) {
            if (tok.isEmpty()) {
                continue;
            }
            // 跳过前置环境赋值 VAR=value，取真正的命令名
            if (tok.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
                continue;
            }
            return tok;
        }
        return null;
    }

    /** 去掉路径与 {@code ./} 前缀，拿到命令基名（防 {@code ./rm}、{@code /bin/rm} 绕过 argv[0] 判定）。 */
    private String baseName(String head) {
        String h = head;
        int slash = h.lastIndexOf('/');
        if (slash >= 0) {
            h = h.substring(slash + 1);
        }
        return h;
    }
}
