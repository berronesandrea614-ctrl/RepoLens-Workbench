package com.repolens.service.impl.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for CodeAnswerPromptBuilder — code-mode write prompt (commit 1783b10).
 *
 * Verified behaviours:
 * 1. codeMode=true  → systemPrompt contains write-mode guidance; no RAG grounding/refusal strings.
 * 2. codeMode=false → systemPrompt contains grounding constraints and the refusal instruction.
 * 3. agentRules non-null → appended with the "项目规则" section header in BOTH modes.
 * 4. 4-arg overload (no codeMode) is backward-compatible with codeMode=false.
 */
class CodeAnswerPromptBuilderTest {

    private CodeAnswerPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new CodeAnswerPromptBuilder();
        ReflectionTestUtils.setField(builder, "maxContextChars", 12000);
    }

    // ------------------------------------------------------------------
    // 1. codeMode=true: write-oriented prompt, no grounding/refusal strings
    // ------------------------------------------------------------------

    @Test
    void buildPrompt_codeMode_systemPromptContainsWriteGuidance() {
        CodeAnswerPromptBuilder.PromptPayload payload =
                builder.buildPrompt("新建一个 UserRepository 类", null, null, null, true);

        String sp = payload.systemPrompt();

        // Must announce expert engineer mode
        assertTrue(sp.contains("expert software engineer"),
                "code-mode system prompt must contain 'expert software engineer'");

        // Must reference verification
        assertTrue(sp.contains("runVerification") || sp.contains("verification"),
                "code-mode system prompt must mention verification");

        // Must NOT over-engineer
        assertTrue(sp.contains("Do not over-engineer"),
                "code-mode system prompt must contain 'Do not over-engineer'");
    }

    @Test
    void buildPrompt_codeMode_systemPromptDoesNotContainGroundingOrRefusalStrings() {
        CodeAnswerPromptBuilder.PromptPayload payload =
                builder.buildPrompt("新建一个 UserRepository 类", null, null, null, true);

        String sp = payload.systemPrompt();

        // These strings are the RAG grounding constraint used in ask mode; must be absent in code mode.
        assertFalse(sp.contains("answer only based on the provided code evidence"),
                "code-mode system prompt must NOT contain the RAG grounding constraint");
        assertFalse(sp.contains("refuse with"),
                "code-mode system prompt must NOT contain the refusal instruction 'refuse with'");
    }

    // ------------------------------------------------------------------
    // 2. codeMode=false: grounding constraints present
    // ------------------------------------------------------------------

    @Test
    void buildPrompt_askMode_systemPromptContainsGroundingConstraints() {
        CodeAnswerPromptBuilder.PromptPayload payload =
                builder.buildPrompt("创建用户接口在哪里？", null, null, null, false);

        String sp = payload.systemPrompt();

        assertTrue(sp.contains("answer only based on the provided code evidence"),
                "ask-mode system prompt must contain the RAG grounding constraint");
        assertTrue(sp.contains("refuse with"),
                "ask-mode system prompt must contain the refusal instruction 'refuse with'");
    }

    // ------------------------------------------------------------------
    // 3. agentRules non-null → appended in both modes
    // ------------------------------------------------------------------

    @Test
    void buildPrompt_agentRulesNonNull_appendedInCodeMode() {
        String rules = "# Project Rules\n- Always write tests\n- Use Java 17";

        CodeAnswerPromptBuilder.PromptPayload payload =
                builder.buildPrompt("新建一个类", null, null, rules, true);

        String sp = payload.systemPrompt();
        assertTrue(sp.contains("项目规则（Project Rules, AGENTS.md）"),
                "code-mode systemPrompt must include the agentRules section header");
        assertTrue(sp.contains("Always write tests"),
                "code-mode systemPrompt must include the actual agentRules content");
    }

    @Test
    void buildPrompt_agentRulesNonNull_appendedInAskMode() {
        String rules = "# Project Rules\n- Always write tests\n- Use Java 17";

        CodeAnswerPromptBuilder.PromptPayload payload =
                builder.buildPrompt("查询某方法", null, null, rules, false);

        String sp = payload.systemPrompt();
        assertTrue(sp.contains("项目规则（Project Rules, AGENTS.md）"),
                "ask-mode systemPrompt must include the agentRules section header");
        assertTrue(sp.contains("Always write tests"),
                "ask-mode systemPrompt must include the actual agentRules content");
    }

    @Test
    void buildPrompt_agentRulesNull_noRulesSectionInEitherMode() {
        CodeAnswerPromptBuilder.PromptPayload code =
                builder.buildPrompt("新建", null, null, null, true);
        CodeAnswerPromptBuilder.PromptPayload ask =
                builder.buildPrompt("查询", null, null, null, false);

        assertFalse(code.systemPrompt().contains("项目规则"),
                "code-mode must not inject rules section when agentRules is null");
        assertFalse(ask.systemPrompt().contains("项目规则"),
                "ask-mode must not inject rules section when agentRules is null");
    }

    // ------------------------------------------------------------------
    // 4. Backward compat: 4-arg overload behaves like codeMode=false
    // ------------------------------------------------------------------

    @Test
    void buildPrompt_fourArgOverload_producesIdenticalOutputToCodeModeFalse() {
        String question = "创建用户接口在哪里？";

        CodeAnswerPromptBuilder.PromptPayload fourArg =
                builder.buildPrompt(question, null, null, null);
        CodeAnswerPromptBuilder.PromptPayload explicit =
                builder.buildPrompt(question, null, null, null, false);

        assertEquals(fourArg.systemPrompt(), explicit.systemPrompt(),
                "4-arg buildPrompt must produce the same systemPrompt as codeMode=false");
        assertEquals(fourArg.userPrompt(), explicit.userPrompt(),
                "4-arg buildPrompt must produce the same userPrompt as codeMode=false");
    }

    @Test
    void buildPrompt_fourArgOverload_containsGroundingConstraintsLikeAskMode() {
        CodeAnswerPromptBuilder.PromptPayload payload =
                builder.buildPrompt("查询接口位置", null, null, null);

        String sp = payload.systemPrompt();
        assertTrue(sp.contains("answer only based on the provided code evidence"),
                "4-arg (backward-compat) system prompt must contain grounding constraint");
        assertTrue(sp.contains("refuse with"),
                "4-arg (backward-compat) system prompt must contain the refusal instruction");
        assertFalse(sp.contains("CODE (write) mode"),
                "4-arg (backward-compat) system prompt must NOT contain write-mode strings");
    }
}
