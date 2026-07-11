package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.repolens.domain.vo.ComprehensionDebtVO;
import com.repolens.domain.vo.DebtUnitVO;
import com.repolens.domain.vo.QuizQuestionVO;
import com.repolens.domain.vo.QuizResultVO;
import com.repolens.domain.vo.RepayPathVO;
import com.repolens.domain.vo.SignalBreakdownVO;
import com.repolens.service.ComprehensionDebtService;
import com.repolens.service.ComprehensionQuizService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * standaloneSetup 控制器测试（无 Spring 容器、无 DB）。
 */
class ComprehensionDebtControllerTest {

    private ComprehensionDebtService debtService;
    private ComprehensionQuizService quizService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        debtService = mock(ComprehensionDebtService.class);
        quizService = mock(ComprehensionQuizService.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ComprehensionDebtController(debtService, quizService))
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ------------------------------------------------------------------ //
    //  GET /api/repos/{repoId}/comprehension-debt                         //
    // ------------------------------------------------------------------ //

    @Test
    void getDashboard_returnsTopDebt() throws Exception {
        DebtUnitVO unit = DebtUnitVO.builder()
                .fileId(10L).filePath("src/PaymentService.java")
                .score(100).band("RED").lineCount(300)
                .signals(SignalBreakdownVO.builder()
                        .s1Norm(0.7).s2Norm(0.6).s3Norm(1.0).s4Norm(0.88)
                        .s5Norm(0.3).s6Norm(0.5).s6Degraded(true).s7Norm(1.0)
                        .ampFactor(1.5).base(0.681).score(100).band("RED").build())
                .degraded(true).build();

        ComprehensionDebtVO vo = ComprehensionDebtVO.builder()
                .repoId(7L).redCount(1).yellowCount(0).greenCount(0)
                .topDebt(List.of(unit)).stale(false).degraded(true).build();

        when(debtService.getDashboard(eq(7L), eq(1L), eq(40))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/comprehension-debt")
                        .param("minScore", "40")
                        .header("X-User-Id", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.repoId").value(7))
                .andExpect(jsonPath("$.data.redCount").value(1))
                .andExpect(jsonPath("$.data.topDebt[0].score").value(100))
                .andExpect(jsonPath("$.data.topDebt[0].band").value("RED"))
                .andExpect(jsonPath("$.data.topDebt[0].filePath").value("src/PaymentService.java"))
                .andExpect(jsonPath("$.data.topDebt[0].signals.s6Degraded").value(true));
    }

    @Test
    void getDashboard_defaultMinScore() throws Exception {
        when(debtService.getDashboard(eq(7L), eq(1L), eq(40)))
                .thenReturn(ComprehensionDebtVO.builder().repoId(7L)
                        .redCount(0).yellowCount(0).greenCount(0)
                        .topDebt(List.of()).stale(false).degraded(false).build());

        // No minScore param → defaults to 40
        mockMvc.perform(get("/api/repos/7/comprehension-debt")
                        .header("X-User-Id", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topDebt").isArray());

        verify(debtService).getDashboard(7L, 1L, 40);
    }

    // ------------------------------------------------------------------ //
    //  GET /api/repos/{repoId}/comprehension-debt/{fileId}/repay          //
    // ------------------------------------------------------------------ //

    @Test
    void getRepayPath_returnsRationalesAndMemories() throws Exception {
        RepayPathVO repay = RepayPathVO.builder()
                .fileId(10L).filePath("src/PaymentService.java")
                .rationales(List.of("[需求] 支付流程改造"))
                .memories(List.of("PaymentService 在 v2 添加了 Alipay 支持"))
                .canAskClaude(false)
                .suggestedPrompt("请解释 PaymentService 这段 AI 生成的代码…")
                .currentScore(100).currentBand("RED").build();

        when(debtService.getRepayPath(eq(7L), eq(10L), eq(1L))).thenReturn(repay);

        mockMvc.perform(get("/api/repos/7/comprehension-debt/10/repay")
                        .header("X-User-Id", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentScore").value(100))
                .andExpect(jsonPath("$.data.rationales[0]").value("[需求] 支付流程改造"))
                .andExpect(jsonPath("$.data.canAskClaude").value(false));
    }

    // ------------------------------------------------------------------ //
    //  POST /api/repos/{repoId}/changes/{changeId}/mark-reviewed          //
    // ------------------------------------------------------------------ //

    @Test
    void markReviewed_diffViewed_calls_service() throws Exception {
        doNothing().when(debtService).markReviewed(any(), any(), any(), any(), any(), any());

        String body = """
                { "reviewType": "DIFF_VIEWED", "dwellMs": 5000 }
                """;
        mockMvc.perform(post("/api/repos/7/changes/50/mark-reviewed")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(debtService).markReviewed(eq(7L), eq(50L), eq("DIFF_VIEWED"), eq(5000L), eq(null), eq(1L));
    }

    @Test
    void markReviewed_quizzed_withScore_calls_service() throws Exception {
        doNothing().when(debtService).markReviewed(any(), any(), any(), any(), any(), any());

        String body = """
                { "reviewType": "QUIZZED", "quizScore": 70 }
                """;
        mockMvc.perform(post("/api/repos/7/changes/50/mark-reviewed")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(debtService).markReviewed(eq(7L), eq(50L), eq("QUIZZED"), eq(null), eq(70), eq(1L));
    }

    // ------------------------------------------------------------------ //
    //  POST /api/repos/{repoId}/comprehension-debt/recompute              //
    // ------------------------------------------------------------------ //

    @Test
    void recompute_callsServiceAndReturns200() throws Exception {
        doNothing().when(debtService).recompute(eq(7L), eq(1L));

        mockMvc.perform(post("/api/repos/7/comprehension-debt/recompute")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(debtService).recompute(eq(7L), eq(1L));
    }

    // ------------------------------------------------------------------ //
    //  GET /api/repos/{repoId}/comprehension-debt/{fileId}/quiz           //
    // ------------------------------------------------------------------ //

    @Test
    void generateQuiz_returnsQuestions() throws Exception {
        QuizQuestionVO q1 = QuizQuestionVO.builder()
                .id(0).questionText("What does PaymentService do?")
                .choices(List.of("A. Process payments", "B. Send emails", "C. Log events", "D. Store files"))
                .build();
        QuizQuestionVO q2 = QuizQuestionVO.builder()
                .id(1).questionText("What happens on null input?")
                .choices(List.of("A. Returns null", "B. Throws exception", "C. Returns 0", "D. Logs error"))
                .build();

        when(quizService.generateQuiz(eq(7L), eq(10L), eq(1L))).thenReturn(List.of(q1, q2));

        mockMvc.perform(get("/api/repos/7/comprehension-debt/10/quiz")
                        .header("X-User-Id", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].questionText").value("What does PaymentService do?"))
                .andExpect(jsonPath("$.data[0].choices[0]").value("A. Process payments"))
                .andExpect(jsonPath("$.data[1].id").value(1));
    }

    // ------------------------------------------------------------------ //
    //  POST /api/repos/{repoId}/comprehension-debt/{fileId}/quiz/submit   //
    // ------------------------------------------------------------------ //

    @Test
    void submitQuiz_passed_returnsScoreAndPassed() throws Exception {
        QuizResultVO result = QuizResultVO.builder()
                .quizScore(80).passed(true)
                .feedbacks(List.of("第 1 题：✓ 正确", "第 2 题：✓ 正确"))
                .changeId(100L).newDebtScore(55).newDebtBand("YELLOW")
                .build();

        when(quizService.submitQuiz(eq(7L), eq(10L), eq(1L), eq(List.of(0, 1)))).thenReturn(result);

        String body = """
                { "answers": [0, 1] }
                """;
        mockMvc.perform(post("/api/repos/7/comprehension-debt/10/quiz/submit")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.quizScore").value(80))
                .andExpect(jsonPath("$.data.passed").value(true))
                .andExpect(jsonPath("$.data.newDebtBand").value("YELLOW"))
                .andExpect(jsonPath("$.data.feedbacks[0]").value("第 1 题：✓ 正确"));

        verify(quizService).submitQuiz(eq(7L), eq(10L), eq(1L), eq(List.of(0, 1)));
    }

    @Test
    void submitQuiz_failed_returnsNotPassed() throws Exception {
        QuizResultVO result = QuizResultVO.builder()
                .quizScore(33).passed(false)
                .feedbacks(List.of("第 1 题：✓ 正确", "第 2 题：✗ 答错，正确答案是：B. WePay", "第 3 题：✗ 答错，正确答案是：C. 返回 0"))
                .build();

        when(quizService.submitQuiz(eq(7L), eq(10L), eq(1L), eq(List.of(0, 0, 0)))).thenReturn(result);

        String body = """
                { "answers": [0, 0, 0] }
                """;
        mockMvc.perform(post("/api/repos/7/comprehension-debt/10/quiz/submit")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quizScore").value(33))
                .andExpect(jsonPath("$.data.passed").value(false));
    }
}
