package com.repolens.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.CodeChunkEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.vo.VectorizeResultVO;
import com.repolens.mapper.CodeChunkMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.ChunkVectorizeServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChunkVectorizeServiceImplTest {

    @Mock
    private RepoMapper repoMapper;
    @Mock
    private CodeChunkMapper codeChunkMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private MilvusService milvusService;
    @Mock
    private org.springframework.transaction.PlatformTransactionManager txManager;

    @InjectMocks
    private ChunkVectorizeServiceImpl chunkVectorizeService;

    /**
     * MybatisPlus lambda cache is not initialized in unit-test context (no Spring context).
     * Wrappers.lambdaUpdate(CodeChunkEntity::getXxx) throws "can not find lambda cache".
     * Registering TableInfo manually here so the test class can run standalone.
     * (Re-registration in a full suite run is a no-op.)
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), CodeChunkEntity.class);
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(chunkVectorizeService, "batchSize", 2);
    }

    @Test
    void vectorizeRepoChunks_shouldDeleteOldVectorsThenRebuildAllCurrentChunks() {
        RepoEntity repo = new RepoEntity();
        repo.setId(5L);
        when(repoMapper.selectById(5L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 5L)).thenReturn(true);

        List<CodeChunkEntity> chunks = List.of(
                buildChunk(1L, "chunk-1"),
                buildChunk(2L, "chunk-2"),
                buildChunk(3L, "chunk-3")
        );
        when(codeChunkMapper.selectList(any()))
                .thenReturn(chunks)
                .thenReturn(chunks);
        when(embeddingService.embedBatch(anyList()))
                .thenReturn(List.of(vector(), vector()))
                .thenReturn(List.of(vector()));

        VectorizeResultVO result = chunkVectorizeService.vectorizeRepoChunks(5L, 1L);

        Assertions.assertEquals(TaskStatus.SUCCESS, result.getStatus());
        Assertions.assertEquals(3, result.getPendingChunkCount());
        Assertions.assertEquals(3, result.getEmbeddedChunkCount());
        Assertions.assertEquals(0, result.getFailedChunkCount());
        verify(milvusService).deleteByRepoId(5L);
        verify(milvusService, times(2)).upsertCodeChunkVectors(anyList(), anyList());
    }

    @Test
    void vectorizeRepoChunks_shouldReturnFailedWhenDeleteByRepoIdFails() {
        RepoEntity repo = new RepoEntity();
        repo.setId(5L);
        when(repoMapper.selectById(5L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 5L)).thenReturn(true);

        List<CodeChunkEntity> chunks = List.of(
                buildChunk(1L, "chunk-1"),
                buildChunk(2L, "chunk-2")
        );
        when(codeChunkMapper.selectList(any())).thenReturn(chunks);
        doThrow(new BizException(50020, "Milvus delete failed")).when(milvusService).deleteByRepoId(5L);

        VectorizeResultVO result = chunkVectorizeService.vectorizeRepoChunks(5L, 1L);

        Assertions.assertEquals(TaskStatus.FAILED, result.getStatus());
        Assertions.assertEquals(2, result.getPendingChunkCount());
        Assertions.assertEquals(0, result.getEmbeddedChunkCount());
        Assertions.assertEquals(2, result.getFailedChunkCount());
        verify(milvusService).deleteByRepoId(5L);
        verify(milvusService, never()).upsertCodeChunkVectors(anyList(), anyList());
    }

    private CodeChunkEntity buildChunk(Long id, String chunkId) {
        CodeChunkEntity entity = new CodeChunkEntity();
        entity.setId(id);
        entity.setRepoId(5L);
        entity.setFileId(10L);
        entity.setChunkId(chunkId);
        entity.setContent("class Demo {}");
        return entity;
    }

    private float[] vector() {
        return new float[384];
    }
}
