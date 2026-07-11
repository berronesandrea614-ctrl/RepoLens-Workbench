package com.repolens.kernel.perm;

import com.repolens.domain.enums.PermissionMode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 五档权限门（M4 核心）：按 {@link PermissionMode} 与风险档位 {@link RiskLevel} 裁决一次工具调用
 * 是 ALLOW / ASK / DENY。语义遵循 <b>deny → ask → allow 首个匹配胜</b>——先看有没有硬拒的理由，
 * 再看要不要人工审批，最后才放行。
 *
 * <p>这是<b>执行侧兜底</b>：PLAN 模式虽已在 loop 的工具目录里不暴露写/exec 工具，
 * 但若 LLM 仍强行发起（或未来别的入口），本门在真正执行前再拦一道。
 *
 * <p>规则表（行=模式，列=风险）：
 * <pre>
 *              A(读)   B(验证)  C(隔离写/普通bash)  D(STEER/未知)  E(破坏性bash)
 *  PLAN        ALLOW   DENY     DENY                DENY           DENY
 *  DEFAULT     ALLOW   ALLOW    ALLOW               ASK            DENY
 *  ACCEPT_EDITS ALLOW  ALLOW    ALLOW               ASK            DENY
 *  AUTO        ALLOW   ALLOW    ALLOW               ASK            DENY
 *  BYPASS      ALLOW   ALLOW    ALLOW               ALLOW          ALLOW
 * </pre>
 * PLAN 的写/exec（B/C/D/E）一律 DENY——计划模式只读；A 仍放行。
 * DEFAULT/ACCEPT_EDITS/AUTO 对隔离写 ALLOW（影子区已隔离，不碰真目录），破坏性 bash(E) 一律 DENY，
 * STEER/未知(D) 需人工审批。BYPASS 全放行（调试用）。
 */
@Component
public class KernelPermissionGate {

    private final KernelRiskClassifier classifier;

    public KernelPermissionGate(KernelRiskClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * 裁决一次工具调用。
     *
     * @param toolName 工具名
     * @param args     LLM 实参
     * @param mode     权限档位（空→DEFAULT）
     */
    public Decision decide(String toolName, Map<String, Object> args, PermissionMode mode) {
        PermissionMode m = mode == null ? PermissionMode.DEFAULT : mode;
        RiskLevel level = classifier.classify(toolName, args);
        String bashReason = classifier.bashSafetyReason(toolName, args);

        // BYPASS：调试旁路，全放行
        if (m == PermissionMode.BYPASS) {
            return Decision.allow(level, "BYPASS 模式：跳过权限检查");
        }

        // PLAN：只读（A）放行，其余（写/exec/验证）一律 DENY——计划模式不落任何改动
        if (m == PermissionMode.PLAN) {
            if (level == RiskLevel.A) {
                return Decision.allow(level, "PLAN 模式：只读工具放行");
            }
            return Decision.deny(level,
                    "PLAN 计划模式禁止写/执行类工具（" + toolName + "，风险 " + level
                            + "）；只能读取与检索代码，改动请切换到 DEFAULT/ACCEPT_EDITS/AUTO 模式。");
        }

        // 以下为 DEFAULT / ACCEPT_EDITS / AUTO：deny → ask → allow 首个匹配
        // E 级破坏性：任何非 BYPASS 模式一律 DENY
        if (level == RiskLevel.E) {
            String why = bashReason != null ? bashReason : "破坏性/系统级操作";
            return Decision.deny(level, "破坏性操作被拒：" + why);
        }
        // D 级：STEER 检索误用 / 语义不明命令 / 未知工具 → 需人工审批
        if (level == RiskLevel.D) {
            String why = bashReason != null ? bashReason : "该操作语义不明或需人工确认";
            return Decision.ask(level, "需人工审批：" + why);
        }
        // A/B/C：只读检索、影子区验证、隔离写 → 放行（写落影子区，已隔离，不碰真目录）
        return Decision.allow(level, allowReason(level, m, toolName));
    }

    private String allowReason(RiskLevel level, PermissionMode mode, String toolName) {
        return switch (level) {
            case A -> "只读工具放行";
            case B -> "影子区验证放行";
            case C -> mode + " 模式：写落隔离影子区，放行（" + toolName + "）";
            default -> mode + " 模式放行";
        };
    }
}
