package com.repolens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.ComprehensionDebtFileEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.vo.QuizQuestionVO;
import com.repolens.domain.vo.QuizResultVO;
import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.ComprehensionDebtFileMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.ComprehensionQuizServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 理解测验评分逻辑纯单元测试（mock LLM）。
 *
 * <p>核心断言：
 * <ul>
 *   <li>3/3 答对 → score=100, passed=true</li>
 *   <li>2/3 答对 → score≈67, passed=true（>= 65%）</li>
 *   <li>1/3 答对 → score≈33, passed=false</li>
 *   <li>0/3 答对 → score=0, passed=false</li>
 *   <li>LLM 生成降级（静态题）→ 正常评分，不崩溃</li>
 * </ul>
 */
class ComprehensionQuizGradingTest {

    private ComprehensionQuizServiceImpl quizService;
    private LlmClient llmClient;
    private ComprehensionDebtService debtService;
    private CodeFileMapper codeFileMapper;
    private FileChangeLogMapper fileChangeLogMapper;
    private ComprehensionDebtFileMapper debtFileMapper;
    private PermissionService permissionService;
    private LlmRuntimeConfig llmRuntimeConfig;

    @BeforeEach
    void setup() {
        permissionService    = mock(PermissionService.class);
        codeFileMapper       = mock(CodeFileMapper.class);
        fileChangeLogMapper  = mock(FileChangeLogMapper.class);
        debtFileMapper       = mock(ComprehensionDebtFileMapper.class);
        debtService          = mock(ComprehensionDebtService.class);
        llmClient            = mock(LlmClient.class);
        llmRuntimeConfig     = mock(LlmRuntimeConfig.class);

        when(permissionService.checkRepoPermission(any(), any())).thenReturn(true);
        when(llmRuntimeConfig.getModelName()).thenReturn("mock-model");

        // code file stub
        CodeFileEntity file = new CodeFileEntity();
        file.setId(10L); file.setRepoId(1L); file.setFilePath("src/PaymentService.java");
        when(codeFileMapper.selectById(10L)).thenReturn(file);

        // file_change_log stub (code context)
        FileChangeLogEntity change = new FileChangeLogEntity();
        change.setId(100L); change.setFilePath("src/PaymentService.java");
        change.setNewContent("public class PaymentService { ... }");
        change.setStatus(FileChangeLogEntity.STATUS_APPLIED);
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(change));

        // debtFile stub (for post-quiz read)
        ComprehensionDebtFileEntity debt = new ComprehensionDebtFileEntity();
        debt.setFileId(10L); debt.setScore(55); debt.setDebtBand("YELLOW");
        when(debtFileMapper.selectOne(any())).thenReturn(debt);

        quizService = new ComprehensionQuizServiceImpl(
                permissionService, codeFileMapper, fileChangeLogMapper,
                debtFileMapper, debtService, llmClient, llmRuntimeConfig,
                new ObjectMapper());
    }

    // ------------------------------------------------------------------ //
    //  LLM 成功路径                                                        //
    // ------------------------------------------------------------------ //

    @Test
    void allCorrect_score100_passed() {
        stubLlmWithThreeQuestions("[0,1,2]"); // correct answers

        List<QuizQuestionVO> qs = quizService.generateQuiz(1L, 10L, 1L);
        assertThat(qs).hasSize(3);

        QuizResultVO result = quizService.submitQuiz(1L, 10L, 1L, List.of(0, 1, 2));
        assertThat(result.getQuizScore()).isEqualTo(100);
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getFeedbacks()).allMatch(f -> f.contains("✓"));
    }

    @Test
    void twoOfThreeCorrect_score67_passed() {
        stubLlmWithThreeQuestions("[0,1,2]");

        quizService.generateQuiz(1L, 10L, 1L);
        // answer 2 correct + 1 wrong (question 2 answers with index 3 but correct is 2)
        QuizResultVO result = quizService.submitQuiz(1L, 10L, 1L, List.of(0, 1, 3));
        assertThat(result.getQuizScore()).isEqualTo(67);
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    void oneOfThreeCorrect_score33_notPassed() {
        stubLlmWithThreeQuestions("[0,1,2]");

        quizService.generateQuiz(1L, 10L, 1L);
        QuizResultVO result = quizService.submitQuiz(1L, 10L, 1L, List.of(0, 3, 3));
        assertThat(result.getQuizScore()).isEqualTo(33);
        assertThat(result.isPassed()).isFalse();
    }

    @Test
    void noneCorrect_score0_notPassed() {
        stubLlmWithThreeQuestions("[0,1,2]");

        quizService.generateQuiz(1L, 10L, 1L);
        QuizResultVO result = quizService.submitQuiz(1L, 10L, 1L, List.of(1, 0, 0));
        assertThat(result.getQuizScore()).isEqualTo(0);
        assertThat(result.isPassed()).isFalse();
    }

    // ------------------------------------------------------------------ //
    //  反馈条目                                                             //
    // ------------------------------------------------------------------ //

    @Test
    void feedbacks_correctCount_matchesRightWrong() {
        stubLlmWithThreeQuestions("[0,1,2]");

        quizService.generateQuiz(1L, 10L, 1L);
        QuizResultVO result = quizService.submitQuiz(1L, 10L, 1L, List.of(0, 3, 2));
        // Q1 correct, Q2 wrong, Q3 correct
        assertThat(result.getFeedbacks().get(0)).contains("✓");
        assertThat(result.getFeedbacks().get(1)).contains("✗");
        assertThat(result.getFeedbacks().get(2)).contains("✓");
    }

    // ------------------------------------------------------------------ //
    //  LLM 失败降级                                                         //
    // ------------------------------------------------------------------ //

    @Test
    void llmFails_fallbackStaticQuestions_gradeNormally() {
        // LLM returns null → service falls back to static questions
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(null);

        List<QuizQuestionVO> qs = quizService.generateQuiz(1L, 10L, 1L);
        assertThat(qs).isNotEmpty();  // 降级静态题非空

        // submit — even with wrong answers it should not crash
        List<Integer> wrongAll = qs.stream().map(q -> 3).toList(); // pick last choice for all
        QuizResultVO result = quizService.submitQuiz(1L, 10L, 1L, wrongAll);
        assertThat(result).isNotNull();
        assertThat(result.getQuizScore()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void llmInvalidJson_fallbackStaticQuestions() {
        LlmResponse badJson = LlmResponse.builder()
                .success(true).content("This is not JSON at all!").build();
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(badJson);

        List<QuizQuestionVO> qs = quizService.generateQuiz(1L, 10L, 1L);
        assertThat(qs).isNotEmpty();

        // grading should still work
        List<Integer> answers = qs.stream().map(q -> 0).toList();
        QuizResultVO result = quizService.submitQuiz(1L, 10L, 1L, answers);
        assertThat(result).isNotNull();
    }

    // ------------------------------------------------------------------ //
    //  Session 过期                                                         //
    // ------------------------------------------------------------------ //

    @Test
    void submitWithoutGenerating_returns0Score_noException() {
        // Never call generateQuiz → no session → graceful degradation
        QuizResultVO result = quizService.submitQuiz(1L, 10L, 1L, List.of(0, 1, 2));
        assertThat(result.getQuizScore()).isEqualTo(0);
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getFeedbacks()).isNotEmpty();
    }

    // ------------------------------------------------------------------ //
    //  Helper                                                              //
    // ------------------------------------------------------------------ //

    /**
     * Stub LLM to return 3 questions with given correct answers array (e.g., "[0,1,2]").
     * Each question has 4 choices.
     */
    private void stubLlmWithThreeQuestions(String correctsJson) {
        String llmJson = """
                {"questions":[
                  {"id":0,"q":"What does PaymentService do?","choices":["A. Process payments","B. Send emails","C. Log events","D. Store files"],"correct":0},
                  {"id":1,"q":"Which payment method is NOT supported?","choices":["A. Alipay","B. WePay","C. Bitcoin","D. Card"],"correct":1},
                  {"id":2,"q":"What happens on null input?","choices":["A. Returns null","B. Throws exception","C. Returns 0","D. Logs error"],"correct":2}
                ]}""";
        LlmResponse resp = LlmResponse.builder()
                .success(true)
                .content(llmJson)
                .build();
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(resp);
    }
}
