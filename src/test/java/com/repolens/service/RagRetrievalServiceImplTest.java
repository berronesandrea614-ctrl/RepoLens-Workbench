package com.repolens.service;

import com.repolens.domain.entity.CodeChunkEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.vo.RagChunkVO;
import com.repolens.domain.vo.RagSearchResultVO;
import com.repolens.domain.vo.VectorSearchHitVO;
import com.repolens.mapper.CodeChunkMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.RagRetrievalServiceImpl;
import com.repolens.service.impl.support.RagRuleReranker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceImplTest {

    @Mock
    private RepoMapper repoMapper;
    @Mock
    private CodeChunkMapper codeChunkMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private MilvusService milvusService;
    @Mock
    private RagRuleReranker ragRuleReranker;

    @InjectMocks
    private RagRetrievalServiceImpl ragRetrievalService;

    @Test
    void retrieve_shouldSkipMilvusHitsMissingInMysql() {
        RepoEntity repo = new RepoEntity();
        repo.setId(5L);
        when(repoMapper.selectById(5L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 5L)).thenReturn(true);
        when(milvusService.search("create user", 5L, 5)).thenReturn(List.of(
                VectorSearchHitVO.builder().chunkId("missing-chunk").score(0.95f).build(),
                VectorSearchHitVO.builder().chunkId("chunk-2").score(0.90f).build()
        ));
        when(codeChunkMapper.selectList(any())).thenReturn(List.of(buildChunk("chunk-2")));
        when(ragRuleReranker.rerank(anyString(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));

        RagSearchResultVO result = ragRetrievalService.retrieve(5L, 1L, "create user", 5);

        Assertions.assertEquals(1, result.getHitCount());
        Assertions.assertFalse(result.getDegraded());
        RagChunkVO chunk = result.getResults().get(0);
        Assertions.assertEquals("chunk-2", chunk.getChunkId());
    }

    @Test
    void fallbackKeywordSearch_shouldRankMultiTermHitAboveSingleTermHit() {
        RepoEntity repo = new RepoEntity();
        repo.setId(7L);
        when(repoMapper.selectById(7L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(true);
        // Milvus 抛异常 -> 走 MySQL 关键词降级路径，隔离验证多词打分排序。
        when(milvusService.search(anyString(), any(), any(Integer.class)))
                .thenThrow(new RuntimeException("milvus down"));

        CodeChunkEntity multiTerm = buildChunk("multi");
        multiTerm.setContent("public void createUserService() { return; }");
        multiTerm.setFilePath("src/main/java/CreateUserService.java");
        CodeChunkEntity singleTerm = buildChunk("single");
        singleTerm.setContent("class UserRepository {}");
        singleTerm.setFilePath("src/main/java/UserRepository.java");
        // 返回顺序把单词命中放前面，验证是打分而非数据库自然序决定排名。
        when(codeChunkMapper.selectList(any())).thenReturn(List.of(singleTerm, multiTerm));
        when(ragRuleReranker.rerank(anyString(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));

        RagSearchResultVO result = ragRetrievalService.retrieve(7L, 1L, "create user service", 5);

        Assertions.assertTrue(result.getDegraded());
        Assertions.assertEquals("multi", result.getResults().get(0).getChunkId(),
                "chunk matching multiple terms should outrank single-term match");
        Assertions.assertEquals("single", result.getResults().get(1).getChunkId());
    }

    @Test
    void mergeCandidates_rrfScoresNormalizedToUnitInterval() {
        RepoEntity repo = new RepoEntity(); repo.setId(9L);
        when(repoMapper.selectById(9L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 9L)).thenReturn(true);
        // Vector path: returns chunk-A at rank 1
        when(milvusService.search(anyString(), any(), any(Integer.class))).thenReturn(
                List.of(VectorSearchHitVO.builder().chunkId("chunk-A").score(0.95f).build()));
        CodeChunkEntity entityA = buildChunk("chunk-A"); entityA.setRepoId(9L);
        when(codeChunkMapper.selectList(any())).thenReturn(List.of(entityA));
        // ragRuleReranker passes through
        when(ragRuleReranker.rerank(anyString(), anyList())).thenAnswer(i -> i.getArgument(1));

        RagSearchResultVO result = ragRetrievalService.retrieve(9L, 1L, "test query", 5);

        // Every chunk in results must have a score in [0, 1]
        for (RagChunkVO c : result.getResults()) {
            org.junit.jupiter.api.Assertions.assertNotNull(c.getScore());
            org.junit.jupiter.api.Assertions.assertTrue(c.getScore() >= 0.0f && c.getScore() <= 1.0f,
                    "RRF score must be in [0,1], got " + c.getScore());
        }
    }

    @Test
    void rrfFusion_orderMatchesHandComputedScores() {
        // vector=[A, B, C] keyword=[C, B, D]  (keyword order emerges from multiTermScore)
        // RRF k=60: C(1/63+1/61≈0.03227) > B(2/62≈0.03226) > A(1/61≈0.01639) > D(1/63≈0.01587)
        RepoEntity repo = new RepoEntity(); repo.setId(20L);
        when(repoMapper.selectById(20L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 20L)).thenReturn(true);

        when(milvusService.search(anyString(), any(), any(Integer.class))).thenReturn(List.of(
                VectorSearchHitVO.builder().chunkId("A").score(0.99f).build(),
                VectorSearchHitVO.builder().chunkId("B").score(0.88f).build(),
                VectorSearchHitVO.builder().chunkId("C").score(0.77f).build()
        ));

        CodeChunkEntity entityA = buildChunk("A"); entityA.setContent("test baz qux");
        CodeChunkEntity entityB = buildChunk("B"); entityB.setContent("test foo baz");
        CodeChunkEntity entityC = buildChunk("C"); entityC.setContent("test foo bar");
        CodeChunkEntity entityD = buildChunk("D"); entityD.setContent("bar baz");

        // 1st call: vector chunk fetch by chunkId IN [A, B, C]
        // 2nd call: keyword LIKE fallback (returns C, B, D — sorted by multiTermScore inside the method)
        when(codeChunkMapper.selectList(any()))
                .thenReturn(List.of(entityA, entityB, entityC))
                .thenReturn(List.of(entityC, entityB, entityD));

        when(ragRuleReranker.rerank(anyString(), anyList())).thenAnswer(i -> i.getArgument(1));

        RagSearchResultVO result = ragRetrievalService.retrieve(20L, 1L, "test foo bar", 10);

        List<RagChunkVO> results = result.getResults();
        // Find positions
        int idxC = -1, idxB = -1, idxA = -1, idxD = -1;
        for (int i = 0; i < results.size(); i++) {
            switch (results.get(i).getChunkId()) {
                case "C" -> idxC = i;
                case "B" -> idxB = i;
                case "A" -> idxA = i;
                case "D" -> idxD = i;
            }
        }
        // C appears in both lists with better combined rank → first
        Assertions.assertTrue(idxC < idxB, "C (both lists) should outrank B");
        // B appears in both lists → outranks A (only in vector, better rank there)
        Assertions.assertTrue(idxB < idxA, "B (both lists) should outrank A (single list)");
        Assertions.assertTrue(idxA < idxD, "A (vector rank 0) should outrank D (keyword rank 2)");
    }

    @Test
    void degradedPath_scoresWithinUnitInterval() {
        RepoEntity repo = new RepoEntity(); repo.setId(12L);
        when(repoMapper.selectById(12L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 12L)).thenReturn(true);
        when(milvusService.search(anyString(), any(), any(Integer.class)))
                .thenThrow(new RuntimeException("milvus down"));

        // Chunk matching many terms across content AND path → raw multiTermScore > 1.0
        CodeChunkEntity multiMatch = buildChunk("multi");
        multiMatch.setContent("create user service endpoint");
        multiMatch.setFilePath("src/main/java/CreateUserService.java");
        CodeChunkEntity singleMatch = buildChunk("single");
        singleMatch.setContent("class Something {}");
        singleMatch.setFilePath("src/main/java/Something.java");

        when(codeChunkMapper.selectList(any())).thenReturn(List.of(multiMatch, singleMatch));
        when(ragRuleReranker.rerank(anyString(), anyList())).thenAnswer(i -> i.getArgument(1));

        RagSearchResultVO result = ragRetrievalService.retrieve(12L, 1L, "create user service", 5);

        Assertions.assertTrue(result.getDegraded());
        for (RagChunkVO c : result.getResults()) {
            Assertions.assertNotNull(c.getScore());
            Assertions.assertTrue(c.getScore() >= 0.0f && c.getScore() <= 1.0f,
                    "Degraded path score must be in [0,1], got " + c.getScore());
        }
    }

    private CodeChunkEntity buildChunk(String chunkId) {
        CodeChunkEntity entity = new CodeChunkEntity();
        entity.setChunkId(chunkId);
        entity.setRepoId(5L);
        entity.setFilePath("src/main/java/Demo.java");
        entity.setStartLine(1);
        entity.setEndLine(20);
        entity.setLanguage("Java");
        entity.setContent("class Demo {}");
        return entity;
    }
}
