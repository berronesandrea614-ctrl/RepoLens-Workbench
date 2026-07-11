package com.repolens.kernel.perm;

import com.repolens.kernel.tools.CommandSafetyChecker;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 把一次工具调用分级 A/B/C/D/E（M4 §权限体系）。分级是权限门 {@link KernelPermissionGate}
 * 的唯一输入，纯函数式、无副作用。
 *
 * <p>规则：
 * <ul>
 *   <li>read/grep/glob → {@link RiskLevel#A}（只读检索）；</li>
 *   <li>runVerification → {@link RiskLevel#B}（只读验证，重但无破坏）；</li>
 *   <li>write/edit/multi_edit → {@link RiskLevel#C}（写落影子区，已隔离）；</li>
 *   <li>bash → 交给 {@link CommandSafetyChecker}：
 *       DENY 前缀 → {@link RiskLevel#E}（破坏性）；
 *       STEER 前缀 → {@link RiskLevel#D}（检索误用，引导改专用工具）；
 *       放行(null) → {@link RiskLevel#C}（普通命令，影子区里跑）。</li>
 * </ul>
 * 未知工具保守当作 {@link RiskLevel#D}（需留意）。
 */
@Component
public class KernelRiskClassifier {

    // read/grep/glob 只读检索；Task 只读子代理（派出的子 agent 只读）；TodoWrite 控制类不碰文件；
    // askUser 只是向用户提问；Skill 只把 skill 说明注入上下文（不碰文件、无破坏）；
    // WebFetch/WebSearch 只读取外界公开信息、不改本地任何东西（出网另由 EgressPolicy 审计+治理）——
    // 均归 A 级：任何模式（含 PLAN）放行，保证反漂移清单、只读子代理、反问、skill 加载、联网研究都不被误当"需审批"拦下。
    private static final Set<String> READ_ONLY = Set.of(
            "read", "grep", "glob", "Task", "TodoWrite", "askUser", "Skill", "WebFetch", "WebSearch");
    private static final Set<String> WRITE_EDIT = Set.of("write", "edit", "multi_edit");

    private final CommandSafetyChecker safetyChecker;

    public KernelRiskClassifier(CommandSafetyChecker safetyChecker) {
        this.safetyChecker = safetyChecker;
    }

    /**
     * @param toolName 工具名
     * @param args     LLM 给出的实参（bash 的分级依赖其中的 command）
     * @return 风险档位
     */
    public RiskLevel classify(String toolName, Map<String, Object> args) {
        if (toolName == null) {
            return RiskLevel.D;
        }
        if (READ_ONLY.contains(toolName)) {
            return RiskLevel.A;
        }
        if ("runVerification".equals(toolName)) {
            return RiskLevel.B;
        }
        if (WRITE_EDIT.contains(toolName)) {
            return RiskLevel.C;
        }
        if ("bash".equals(toolName)) {
            return classifyBash(args);
        }
        // 未知工具：保守当作需人工留意
        return RiskLevel.D;
    }

    /** bash 命令按 {@link CommandSafetyChecker} 的 DENY:/STEER: 前缀映射到 E/D，放行为 C。 */
    private RiskLevel classifyBash(Map<String, Object> args) {
        Object cv = args == null ? null : args.get("command");
        String command = cv == null ? null : String.valueOf(cv);
        String reason = safetyChecker.check(command);
        if (reason == null) {
            return RiskLevel.C;
        }
        if (reason.startsWith("DENY:")) {
            return RiskLevel.E;
        }
        if (reason.startsWith("STEER:")) {
            return RiskLevel.D;
        }
        // 有原因但无已知前缀：保守当破坏性
        return RiskLevel.E;
    }

    /**
     * 透传 bash 安全检查的原因串（供权限门把 reason 讲给前端）。非 bash 工具返回 {@code null}。
     */
    public String bashSafetyReason(String toolName, Map<String, Object> args) {
        if (!"bash".equals(toolName)) {
            return null;
        }
        Object cv = args == null ? null : args.get("command");
        return safetyChecker.check(cv == null ? null : String.valueOf(cv));
    }
}
