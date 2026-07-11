package com.repolens.service.support;

import com.repolens.domain.enums.PermissionMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionEvaluator {

    private final WriteApprovalPolicy approvalPolicy;
    private final RiskClassifier riskClassifier;

    public static final java.util.Set<String> WRITE_TOOLS =
            java.util.Set.of("writeFileContent", "editFileContent",
                    "createFileContent", "deleteFile", "multiEditFile");
    public static final java.util.Set<String> EXEC_TOOLS =
            java.util.Set.of("runVerification", "bashExec", "bashOutput", "killBash");

    public record Verdict(WriteApprovalPolicy.Decision decision, String riskLevel, String reason) {}

    public Verdict evaluate(PermissionMode mode, String toolName, String targetPath) {
        try {
            if (mode == PermissionMode.PLAN && (WRITE_TOOLS.contains(toolName) || EXEC_TOOLS.contains(toolName))) {
                return new Verdict(WriteApprovalPolicy.Decision.BLOCK, null, "PLAN 模式不允许写/执行工具");
            }
            String riskLevel = null;
            if (WRITE_TOOLS.contains(toolName) || EXEC_TOOLS.contains(toolName)) {
                riskLevel = riskClassifier.classify(toolName, targetPath);
            }
            WriteApprovalPolicy.Decision decision = approvalPolicy.decide(mode, toolName, riskLevel);
            return new Verdict(decision, riskLevel, null);
        } catch (Exception e) {
            log.warn("PermissionEvaluator failed (fail-closed STAGE): {}", e.getMessage());
            return new Verdict(WriteApprovalPolicy.Decision.STAGE, "E", "评估异常，保守策略");
        }
    }
}
