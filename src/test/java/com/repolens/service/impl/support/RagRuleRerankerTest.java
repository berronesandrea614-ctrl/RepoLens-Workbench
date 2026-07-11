package com.repolens.service.impl.support;

import com.repolens.domain.vo.RagChunkVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RagRuleReranker 单测：验证规则分仅作 tie-break（≤0.01），
 * 以及 matchCreateIntent 的 token 边界匹配修复。
 */
class RagRuleRerankerTest {

    private final RagRuleReranker reranker = new RagRuleReranker();

    private RagChunkVO chunk(String chunkId, String content, String filePath, Float score) {
        return RagChunkVO.builder()
                .chunkId(chunkId).content(content).filePath(filePath).score(score).build();
    }

    @Test
    void createElement_doesNotTriggerCreateIntentBonus() {
        // Both chunks have same base score so only the bonus determines the winner.
        // react's "createElement" must NOT match word-boundary \bcreate\b (letter follows immediately).
        // java's "@PostMapping" MUST trigger the create-intent bonus.
        RagChunkVO reactChunk = chunk("react", "React.createElement('div')", "src/App.jsx", 0.5f);
        RagChunkVO javaChunk = chunk("java", "@PostMapping(\"/users\")\npublic User create() {}", "src/Controller.java", 0.5f);

        List<RagChunkVO> result = reranker.rerank("create user", List.of(reactChunk, javaChunk));

        // Java chunk has @PostMapping bonus; react gets none because createElement has no word boundary after "create"
        assertThat(result.get(0).getChunkId()).isEqualTo("java");
    }

    @Test
    void postMapping_triggersCreateIntentBonus() {
        RagChunkVO postChunk = chunk("post", "@PostMapping \npublic void save() {}", "PostController.java", 0.5f);
        RagChunkVO otherChunk = chunk("other", "some other content", "OtherService.java", 0.5f);

        List<RagChunkVO> result = reranker.rerank("create record", List.of(postChunk, otherChunk));
        assertThat(result.get(0).getChunkId()).isEqualTo("post");
    }

    @Test
    void createdComment_doesNotTriggerCreateIntentBonus() {
        // Both chunks have same base score so only the bonus determines the winner.
        // "Created" (past tense) must NOT match \bcreate\b because 'd' follows, eliminating the word boundary.
        // "@PostMapping" MUST trigger the create-intent bonus.
        RagChunkVO createdChunk = chunk("c1", "// Created by John Doe in 2023", "SomeUtil.java", 0.4f);
        RagChunkVO postChunk = chunk("c2", "@PostMapping \npublic void save() {}", "Ctrl.java", 0.4f);

        List<RagChunkVO> result = reranker.rerank("create", List.of(createdChunk, postChunk));
        // postChunk gets the PostMapping bonus; createdChunk gets none (past tense "created" ≠ word "create")
        assertThat(result.get(0).getChunkId()).isEqualTo("c2");
    }

    @Test
    void bonusesAreTieBreakScale_notDominant() {
        // Base score difference of 0.1 should not be overcome by any single rule bonus (all ≤ 0.01)
        RagChunkVO highBase = chunk("high", "some content", "SomeFile.java", 0.9f);
        RagChunkVO lowBase = chunk("low", "@PostMapping public void create() {}", "Ctrl.java", 0.8f);

        List<RagChunkVO> result = reranker.rerank("create", List.of(highBase, lowBase));
        // High base should still win despite lowBase having create bonus
        assertThat(result.get(0).getChunkId()).isEqualTo("high");
    }
}
