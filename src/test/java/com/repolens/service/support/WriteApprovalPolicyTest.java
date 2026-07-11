package com.repolens.service.support;

import com.repolens.domain.enums.PermissionMode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WriteApprovalPolicyTest {
    WriteApprovalPolicy policy = new WriteApprovalPolicy();

    @Test
    void default_isStage() {
        assertThat(policy.decide(PermissionMode.DEFAULT, "writeFileContent", null))
                .isEqualTo(WriteApprovalPolicy.Decision.STAGE);
    }

    @Test
    void acceptEdits_editIsAutoApply() {
        assertThat(policy.decide(PermissionMode.ACCEPT_EDITS, "editFileContent", "B"))
                .isEqualTo(WriteApprovalPolicy.Decision.AUTO_APPLY);
    }

    @Test
    void acceptEdits_deleteIsStage() {
        assertThat(policy.decide(PermissionMode.ACCEPT_EDITS, "deleteFile", "D"))
                .isEqualTo(WriteApprovalPolicy.Decision.STAGE);
    }

    @Test
    void auto_highRiskIsBlock() {
        assertThat(policy.decide(PermissionMode.AUTO, "writeFileContent", "E"))
                .isEqualTo(WriteApprovalPolicy.Decision.BLOCK);
    }

    @Test
    void plan_isBlock() {
        assertThat(policy.decide(PermissionMode.PLAN, "writeFileContent", null))
                .isEqualTo(WriteApprovalPolicy.Decision.BLOCK);
    }
}
