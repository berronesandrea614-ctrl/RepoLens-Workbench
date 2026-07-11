package com.repolens.service.support;

import com.repolens.domain.enums.PermissionMode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PermissionEvaluatorTest {

    @Test
    void planMode_blocksWriteTool() {
        WriteApprovalPolicy approvalPolicy = new WriteApprovalPolicy();
        RiskClassifier riskClassifier = new RiskClassifier();
        PermissionEvaluator evaluator = new PermissionEvaluator(approvalPolicy, riskClassifier);
        var verdict = evaluator.evaluate(PermissionMode.PLAN, "writeFileContent", "src/Foo.java");
        assertThat(verdict.decision()).isEqualTo(WriteApprovalPolicy.Decision.BLOCK);
    }

    @Test
    void defaultMode_stagesEdit() {
        WriteApprovalPolicy approvalPolicy = new WriteApprovalPolicy();
        RiskClassifier riskClassifier = new RiskClassifier();
        PermissionEvaluator evaluator = new PermissionEvaluator(approvalPolicy, riskClassifier);
        var verdict = evaluator.evaluate(PermissionMode.DEFAULT, "editFileContent", "src/Foo.java");
        assertThat(verdict.decision()).isEqualTo(WriteApprovalPolicy.Decision.STAGE);
    }

    @Test
    void acceptEdits_autoAppliesEdit() {
        WriteApprovalPolicy approvalPolicy = new WriteApprovalPolicy();
        RiskClassifier riskClassifier = new RiskClassifier();
        PermissionEvaluator evaluator = new PermissionEvaluator(approvalPolicy, riskClassifier);
        var verdict = evaluator.evaluate(PermissionMode.ACCEPT_EDITS, "editFileContent", "src/Foo.java");
        assertThat(verdict.decision()).isEqualTo(WriteApprovalPolicy.Decision.AUTO_APPLY);
    }
}
