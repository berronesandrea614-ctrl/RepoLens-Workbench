package com.repolens.service.support;

import com.repolens.domain.enums.PermissionMode;
import org.springframework.stereotype.Component;

@Component
public class WriteApprovalPolicy {

    public enum Decision { AUTO_APPLY, STAGE, BLOCK }

    public Decision decide(PermissionMode mode, String toolName, String riskLevel) {
        boolean isDelete = "deleteFile".equals(toolName);
        boolean isExec = "runVerification".equals(toolName);
        boolean isHighRisk = "E".equals(riskLevel);

        return switch (mode) {
            case DEFAULT      -> Decision.STAGE;
            case PLAN         -> Decision.BLOCK;
            case ACCEPT_EDITS -> (isDelete || isExec) ? Decision.STAGE : Decision.AUTO_APPLY;
            case AUTO         -> {
                if (isHighRisk || isDelete) yield Decision.BLOCK;
                if ("D".equals(riskLevel)) yield Decision.STAGE;
                yield Decision.AUTO_APPLY;
            }
            case BYPASS       -> Decision.AUTO_APPLY;
        };
    }
}
