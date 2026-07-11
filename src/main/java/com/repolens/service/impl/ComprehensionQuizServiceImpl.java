package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.ComprehensionDebtFileEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.vo.FileExplanationVO;
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
import com.repolens.service.ComprehensionDebtService;
import com.repolens.service.ComprehensionQuizService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 理解债务偿还测验服务实现（A-P1 偿债闭环）。
 *
 * <p>设计：
 * <ul>
 *   <li>generateQuiz：向 LLM 请求 JSON 格式的 3 道理解测验；LLM 失败时降级到静态题。</li>
 *   <li>submitQuiz：评分（正确答案从 server 端 session 缓存取，不暴露给前端）；
 *       score≥65% → markReviewed(QUIZZED) → stale → 下次 GET 重算债务分。</li>
 *   <li>sessionCache：内存 ConcurrentHashMap，key = {repoId}_{fileId}_{userId}，
 *       TTL 10 分钟（懒清理）。只有出题后 10 分钟内的提交才被接受，超时视作降级（空题组 = 0分）。</li>
 * </ul>
 *
 * <p>失败安全：所有 LLM 调用均有 try-catch；失败时返回降级结果，不抛异常给调用方。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComprehensionQuizServiceImpl implements ComprehensionQuizService {

    private static final int PASS_THRESHOLD   = 65;   // 通过分数线
    private static final int SESSION_TTL_MS   = 600_000; // 10 分钟
    private static final int MAX_CODE_CHARS   = 4000;  // 传给 LLM 的代码长度上限
    private static final long EXPLAIN_TTL_MS  = 1_800_000L; // 讲解缓存 30 分钟

    private final PermissionService           permissionService;
    private final CodeFileMapper              codeFileMapper;
    private final FileChangeLogMapper         fileChangeLogMapper;
    private final ComprehensionDebtFileMapper debtFileMapper;
    private final ComprehensionDebtService    debtService;
    private final LlmClient                   llmClient;
    private final LlmRuntimeConfig            llmRuntimeConfig;
    private final ObjectMapper                objectMapper;

    // ------------------------------------------------------------------ //
    //  Session cache                                                       //
    // ------------------------------------------------------------------ //

    private record QuizSession(List<QuizQuestionVO> questions,
                               List<Integer>         correctAnswers,
                               long                  createdAt) {
        boolean expired() {
            return System.currentTimeMillis() - createdAt > SESSION_TTL_MS;
        }
    }

    /** key = repoId_fileId_userId */
    private final Map<String, QuizSession> sessionCache = new ConcurrentHashMap<>();

    /** 讲解缓存：key = repoId_fileId，避免每次进页面都调 LLM。 */
    private record ExplainCacheEntry(FileExplanationVO vo, long createdAt) {
        boolean expired() {
            return System.currentTimeMillis() - createdAt > EXPLAIN_TTL_MS;
        }
    }
    private final Map<String, ExplainCacheEntry> explainCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ //
    //  Public API                                                          //
    // ------------------------------------------------------------------ //

    @Override
    public FileExplanationVO explainFile(Long repoId, Long fileId, Long userId) {
        checkPermission(userId, repoId);

        String cacheKey = repoId + "_" + fileId;
        ExplainCacheEntry cached = explainCache.get(cacheKey);
        if (cached != null && !cached.expired()) {
            return cached.vo();
        }

        CodeFileEntity file = loadFile(repoId, fileId);
        String codeContext = loadCodeContext(repoId, file.getFilePath());

        FileExplanationVO vo;
        try {
            vo = callLlmForExplanation(fileId, file.getFilePath(), codeContext);
            log.info("AUDIT file-explained repoId={} fileId={} relatedFiles={}",
                    repoId, fileId, vo.getRelatedFiles() == null ? 0 : vo.getRelatedFiles().size());
        } catch (Exception ex) {
            log.warn("LLM file explanation failed (degraded), repoId={} fileId={} err={}",
                    repoId, fileId, ex.getMessage());
            vo = buildFallbackExplanation(fileId, file.getFilePath());
        }

        explainCache.put(cacheKey, new ExplainCacheEntry(vo, System.currentTimeMillis()));
        evictExpiredExplain();
        return vo;
    }

    @Override
    public List<QuizQuestionVO> generateQuiz(Long repoId, Long fileId, Long userId) {
        checkPermission(userId, repoId);

        CodeFileEntity file = loadFile(repoId, fileId);
        String codeContext = loadCodeContext(repoId, file.getFilePath());

        List<QuizQuestionVO> questions;
        List<Integer> correctAnswers;

        try {
            QuizSession session = callLlmForQuiz(file.getFilePath(), codeContext);
            questions = session.questions();
            correctAnswers = session.correctAnswers();
            log.info("AUDIT quiz-generated repoId={} fileId={} questionCount={}", repoId, fileId, questions.size());
        } catch (Exception ex) {
            log.warn("LLM quiz generation failed (degraded), repoId={} fileId={} err={}", repoId, fileId, ex.getMessage());
            // 降级：返回通用静态题
            String baseName = extractBaseName(file.getFilePath());
            QuizSession fallback = buildFallbackSession(baseName);
            questions = fallback.questions();
            correctAnswers = fallback.correctAnswers();
        }

        // 存 session（正确答案不出去）
        String key = sessionKey(repoId, fileId, userId);
        sessionCache.put(key, new QuizSession(questions, correctAnswers, System.currentTimeMillis()));
        evictExpiredSessions();

        return questions;
    }

    @Override
    public QuizResultVO submitQuiz(Long repoId, Long fileId, Long userId, List<Integer> answers) {
        checkPermission(userId, repoId);

        String key = sessionKey(repoId, fileId, userId);
        QuizSession session = sessionCache.get(key);

        // Session 超时或不存在 → 视作 0 分（失败安全）
        if (session == null || session.expired()) {
            log.warn("quiz submit: no valid session, repoId={} fileId={} userId={}", repoId, fileId, userId);
            return QuizResultVO.builder()
                    .quizScore(0).passed(false)
                    .feedbacks(List.of("测验会话已过期，请重新生成题目后再提交。"))
                    .build();
        }

        // 评分
        List<Integer> correct = session.correctAnswers();
        List<String> feedbacks = new ArrayList<>();
        int correctCount = 0;
        int total = correct.size();

        for (int i = 0; i < total; i++) {
            int userAns = (answers != null && i < answers.size() && answers.get(i) != null)
                    ? answers.get(i) : -1;
            int rightAns = correct.get(i);
            QuizQuestionVO q = session.questions().get(i);
            String rightLabel = (rightAns >= 0 && rightAns < q.getChoices().size())
                    ? q.getChoices().get(rightAns) : "N/A";
            if (userAns == rightAns) {
                correctCount++;
                feedbacks.add("第 " + (i + 1) + " 题：✓ 正确");
            } else {
                feedbacks.add("第 " + (i + 1) + " 题：✗ 答错，正确答案是：" + rightLabel);
            }
        }

        int score = total == 0 ? 0 : (int) Math.round((double) correctCount / total * 100);
        boolean passed = score >= PASS_THRESHOLD;

        log.info("AUDIT quiz-submitted repoId={} fileId={} score={} passed={}", repoId, fileId, score, passed);

        Long markedChangeId = null;
        Integer newDebtScore = null;
        String newDebtBand  = null;

        if (passed) {
            // mark-reviewed(QUIZZED) → 找最新的 APPLIED change for this file
            markedChangeId = markQuizzed(repoId, fileId, userId, score, file(repoId, fileId));
            // 更新后重读债务分（stale → compute）
            try {
                ComprehensionDebtFileEntity debt = debtFileMapper.selectOne(
                        Wrappers.<ComprehensionDebtFileEntity>lambdaQuery()
                                .eq(ComprehensionDebtFileEntity::getRepoId, repoId)
                                .eq(ComprehensionDebtFileEntity::getFileId, fileId));
                if (debt != null) {
                    newDebtScore = debt.getScore();
                    newDebtBand  = debt.getDebtBand();
                }
            } catch (Exception ex) {
                log.debug("quiz post-mark debt read failed (non-fatal), err={}", ex.getMessage());
            }
            // 清理 session（只能用一次）
            sessionCache.remove(key);
        }

        return QuizResultVO.builder()
                .quizScore(score)
                .passed(passed)
                .feedbacks(feedbacks)
                .changeId(markedChangeId)
                .newDebtScore(newDebtScore)
                .newDebtBand(newDebtBand)
                .build();
    }

    // ------------------------------------------------------------------ //
    //  LLM quiz generation                                                 //
    // ------------------------------------------------------------------ //

    private QuizSession callLlmForQuiz(String filePath, String codeContext) {
        String baseName = extractBaseName(filePath);
        String system = """
                你是一个代码理解测验出题专家。请阅读下面的 Java 代码，出 3 道多选一理解测验题（每题 4 个选项），\
                测试读者对代码功能、设计决策、关键边界条件的理解。

                严格按照以下 JSON 格式返回，不要有任何其他文字：
                {"questions":[{"id":0,"q":"问题文字","choices":["A. 选项一","B. 选项二","C. 选项三","D. 选项四"],"correct":0}]}
                correct 是正确答案的 0-based 下标（0=A,1=B,2=C,3=D）。
                """;
        String user = "文件名：" + baseName + "\n\n代码如下：\n```java\n" + codeContext + "\n```\n请出 3 道理解测验题。";

        LlmRequest req = LlmRequest.builder()
                .modelName(llmRuntimeConfig.getModelName())
                .systemPrompt(system)
                .userPrompt(user)
                .temperature(0.3)
                .timeoutMs(30_000)
                .build();

        LlmResponse resp = llmClient.generate(req);
        if (resp == null || !Boolean.TRUE.equals(resp.getSuccess())
                || !StringUtils.hasText(resp.getContent())) {
            throw new RuntimeException("LLM returned empty/failed response");
        }

        return parseQuizJson(resp.getContent());
    }

    /**
     * 解析 LLM 返回的 JSON 题目。
     * 先尝试直接 parse；失败则尝试从文本中提取第一个 JSON 对象；还失败则抛出。
     */
    private QuizSession parseQuizJson(String content) {
        String json = content.trim();

        // 尝试提取嵌在文本中的 JSON（LLM 常加前后缀文字）
        int start = json.indexOf('{');
        int end   = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode questionsNode = root.get("questions");
            if (questionsNode == null || !questionsNode.isArray()) {
                throw new RuntimeException("no 'questions' array in LLM response");
            }

            List<QuizQuestionVO> questions = new ArrayList<>();
            List<Integer> correctAnswers   = new ArrayList<>();

            for (JsonNode qNode : questionsNode) {
                int id = qNode.path("id").asInt(questions.size());
                String q = qNode.path("q").asText("");
                int correct = qNode.path("correct").asInt(0);

                List<String> choices = new ArrayList<>();
                JsonNode choicesNode = qNode.get("choices");
                if (choicesNode != null && choicesNode.isArray()) {
                    for (JsonNode c : choicesNode) {
                        choices.add(c.asText());
                    }
                }
                if (choices.isEmpty()) {
                    choices = List.of("A. 是", "B. 否", "C. 不确定", "D. 都不对");
                }

                questions.add(QuizQuestionVO.builder()
                        .id(id).questionText(q).choices(choices).build());
                correctAnswers.add(Math.max(0, Math.min(correct, choices.size() - 1)));
            }

            if (questions.isEmpty()) {
                throw new RuntimeException("parsed 0 questions");
            }
            return new QuizSession(questions, correctAnswers, System.currentTimeMillis());

        } catch (Exception ex) {
            throw new RuntimeException("Failed to parse quiz JSON: " + ex.getMessage(), ex);
        }
    }

    /**
     * 降级静态题（LLM 不可用时）。题目通用化，不依赖具体代码内容。
     */
    private QuizSession buildFallbackSession(String baseName) {
        List<QuizQuestionVO> questions = List.of(
                QuizQuestionVO.builder().id(0)
                        .questionText(baseName + " 这个类/方法最主要的职责是什么？")
                        .choices(List.of("A. 处理业务逻辑", "B. 数据持久化", "C. 路由请求", "D. 工具类"))
                        .build(),
                QuizQuestionVO.builder().id(1)
                        .questionText("当输入为 null 或空时，" + baseName + " 应该如何处理？")
                        .choices(List.of("A. 直接返回 null", "B. 抛出异常", "C. 返回默认值", "D. 记录日志并跳过"))
                        .build(),
                QuizQuestionVO.builder().id(2)
                        .questionText("理解 " + baseName + " 对于维护和扩展这段代码为什么重要？")
                        .choices(List.of("A. 防止引入 Bug", "B. 提升性能", "C. 减少代码量", "D. 以上都对"))
                        .build()
        );
        List<Integer> correct = List.of(0, 1, 3);  // 通用合理答案
        return new QuizSession(questions, correct, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ //
    //  LLM file explanation (帮助理解，替代考试式出题)                       //
    // ------------------------------------------------------------------ //

    private FileExplanationVO callLlmForExplanation(Long fileId, String filePath, String codeContext) {
        String baseName = extractBaseName(filePath);
        String system = """
                你是一个资深工程师，帮助读者「理解」一个代码文件（不是考试、不要出题）。\
                请阅读下面的代码，用中文把这个文件讲清楚：\
                ①它在整个项目里起什么作用、承担什么职责；\
                ②与它直接相关的业务逻辑、调用链的前因后果（谁调用它、它调用谁、为什么这么设计）；\
                ③列出与它直接相关的其他文件和类/方法/函数。

                严格只返回如下 JSON（不要有任何额外文字、不要 markdown 代码围栏）：
                {"explanation":"markdown 格式的讲解正文（可用标题/列表/加粗，讲清作用+职责+前因后果）",\
                "relatedFiles":[{"path":"相对路径","reason":"一句话说明为什么相关"}],\
                "relatedSymbols":[{"name":"类名或方法名","path":"该符号所在文件的相对路径","reason":"一句话说明"}]}
                relatedFiles / relatedSymbols 的 path 尽量给出项目内的相对路径，猜不到就留空字符串。
                """;
        String user = "文件路径：" + filePath + "\n文件名：" + baseName
                + "\n\n代码如下：\n```\n" + codeContext + "\n```\n请按上面 JSON 结构输出对这个文件的讲解。";

        LlmRequest req = LlmRequest.builder()
                .modelName(llmRuntimeConfig.getModelName())
                .systemPrompt(system)
                .userPrompt(user)
                .temperature(0.4)
                .timeoutMs(45_000)
                .build();

        LlmResponse resp = llmClient.generate(req);
        if (resp == null || !Boolean.TRUE.equals(resp.getSuccess())
                || !StringUtils.hasText(resp.getContent())) {
            throw new RuntimeException("LLM returned empty/failed response");
        }
        return parseExplanationJson(fileId, filePath, resp.getContent());
    }

    private FileExplanationVO parseExplanationJson(Long fileId, String filePath, String content) {
        String json = content.trim();
        int start = json.indexOf('{');
        int end   = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            String explanation = root.path("explanation").asText("");
            if (!StringUtils.hasText(explanation)) {
                throw new RuntimeException("no 'explanation' in LLM response");
            }

            List<FileExplanationVO.RelatedFile> relatedFiles = new ArrayList<>();
            JsonNode rfNode = root.get("relatedFiles");
            if (rfNode != null && rfNode.isArray()) {
                for (JsonNode n : rfNode) {
                    String path = n.path("path").asText("").trim();
                    if (path.isEmpty()) continue;
                    relatedFiles.add(FileExplanationVO.RelatedFile.builder()
                            .path(path)
                            .reason(n.path("reason").asText(""))
                            .build());
                }
            }

            List<FileExplanationVO.RelatedSymbol> relatedSymbols = new ArrayList<>();
            JsonNode rsNode = root.get("relatedSymbols");
            if (rsNode != null && rsNode.isArray()) {
                for (JsonNode n : rsNode) {
                    String name = n.path("name").asText("").trim();
                    if (name.isEmpty()) continue;
                    String path = n.path("path").asText("").trim();
                    relatedSymbols.add(FileExplanationVO.RelatedSymbol.builder()
                            .name(name)
                            .path(path.isEmpty() ? null : path)
                            .reason(n.path("reason").asText(""))
                            .build());
                }
            }

            return FileExplanationVO.builder()
                    .fileId(fileId)
                    .filePath(filePath)
                    .explanation(explanation)
                    .relatedFiles(relatedFiles)
                    .relatedSymbols(relatedSymbols)
                    .degraded(false)
                    .build();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to parse explanation JSON: " + ex.getMessage(), ex);
        }
    }

    /** 降级讲解（LLM 不可用时）。不出题，仍给出可读的通用说明。 */
    private FileExplanationVO buildFallbackExplanation(Long fileId, String filePath) {
        String baseName = extractBaseName(filePath);
        String explanation = "### " + baseName + "\n\n"
                + "当前无法调用模型生成详细讲解（AI 服务不可用），以下为通用说明：\n\n"
                + "- **文件路径**：`" + (filePath == null ? "" : filePath) + "`\n"
                + "- 建议直接打开该文件阅读，重点关注类/方法的职责、它被谁调用、又调用了谁。\n"
                + "- 稍后 AI 服务恢复后重新打开此面板，可获得针对本文件的作用、职责与调用链讲解。\n";
        return FileExplanationVO.builder()
                .fileId(fileId)
                .filePath(filePath)
                .explanation(explanation)
                .relatedFiles(new ArrayList<>())
                .relatedSymbols(new ArrayList<>())
                .degraded(true)
                .build();
    }

    private void evictExpiredExplain() {
        explainCache.entrySet().removeIf(e -> e.getValue().expired());
    }

    // ------------------------------------------------------------------ //
    //  Helper methods                                                      //
    // ------------------------------------------------------------------ //

    private void checkPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }
    }

    private CodeFileEntity loadFile(Long repoId, Long fileId) {
        CodeFileEntity file = codeFileMapper.selectById(fileId);
        if (file == null || !Objects.equals(file.getRepoId(), repoId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "File not found: " + fileId);
        }
        return file;
    }

    /** 懒加载 CodeFileEntity（用于 submitQuiz 内部调用，失败安全）。 */
    private CodeFileEntity file(Long repoId, Long fileId) {
        try {
            return loadFile(repoId, fileId);
        } catch (Exception ex) {
            CodeFileEntity dummy = new CodeFileEntity();
            dummy.setId(fileId);
            dummy.setRepoId(repoId);
            dummy.setFilePath("");
            return dummy;
        }
    }

    /**
     * 从 file_change_log 取最近一次 APPLIED/REVERTED 的 newContent 作为代码上下文。
     * 截断到 MAX_CODE_CHARS，避免超 token 限制。
     */
    private String loadCodeContext(Long repoId, String filePath) {
        try {
            List<FileChangeLogEntity> changes = fileChangeLogMapper.selectList(
                    Wrappers.<FileChangeLogEntity>lambdaQuery()
                            .eq(FileChangeLogEntity::getRepoId, repoId)
                            .eq(FileChangeLogEntity::getFilePath, filePath)
                            .in(FileChangeLogEntity::getStatus,
                                    FileChangeLogEntity.STATUS_APPLIED,
                                    FileChangeLogEntity.STATUS_REVERTED)
                            .orderByDesc(FileChangeLogEntity::getCreatedAt));
            if (!changes.isEmpty()) {
                String content = changes.get(0).getNewContent();
                if (StringUtils.hasText(content)) {
                    return content.length() > MAX_CODE_CHARS
                            ? content.substring(0, MAX_CODE_CHARS) + "\n// ...(截断)"
                            : content;
                }
            }
        } catch (Exception ex) {
            log.debug("loadCodeContext failed (non-fatal), filePath={} err={}", filePath, ex.getMessage());
        }
        return "// 代码内容不可用，请基于文件名出通用理解题。";
    }

    /**
     * 找到该文件最新的 APPLIED change，调用 markReviewed(QUIZZED, score)。
     * 失败安全：不抛异常。
     *
     * @return 被标记的 changeId，null 表示未找到或失败
     */
    private Long markQuizzed(Long repoId, Long fileId, Long userId, int score, CodeFileEntity file) {
        if (!StringUtils.hasText(file.getFilePath())) return null;
        try {
            List<FileChangeLogEntity> changes = fileChangeLogMapper.selectList(
                    Wrappers.<FileChangeLogEntity>lambdaQuery()
                            .eq(FileChangeLogEntity::getRepoId, repoId)
                            .eq(FileChangeLogEntity::getFilePath, file.getFilePath())
                            .eq(FileChangeLogEntity::getStatus, FileChangeLogEntity.STATUS_APPLIED)
                            .orderByDesc(FileChangeLogEntity::getCreatedAt));
            if (changes.isEmpty()) return null;

            Long changeId = changes.get(0).getId();
            debtService.markReviewed(repoId, changeId, "QUIZZED", null, score, userId);
            log.info("AUDIT quiz-mark-reviewed repoId={} fileId={} changeId={} score={}", repoId, fileId, changeId, score);
            return changeId;
        } catch (Exception ex) {
            log.warn("markQuizzed failed (non-fatal) repoId={} fileId={} err={}", repoId, fileId, ex.getMessage());
            return null;
        }
    }

    private String sessionKey(Long repoId, Long fileId, Long userId) {
        return repoId + "_" + fileId + "_" + userId;
    }

    private String extractBaseName(String filePath) {
        if (filePath == null) return "code";
        String name = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;
        return name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
    }

    /** 懒清理过期 session（避免无界增长）。 */
    private void evictExpiredSessions() {
        sessionCache.entrySet().removeIf(e -> e.getValue().expired());
    }
}
