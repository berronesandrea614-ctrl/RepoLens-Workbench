package com.repolens.service;

import com.repolens.domain.entity.IndexTaskEntity;
import com.repolens.domain.enums.TaskType;
import com.repolens.mapper.IndexTaskMapper;
import com.repolens.service.impl.IndexTaskServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexTaskServiceImplTest {

    @Mock
    private IndexTaskMapper indexTaskMapper;

    @InjectMocks
    private IndexTaskServiceImpl indexTaskService;

    @Test
    void createInitCloneTask_shouldUseInitIdempotentKey() {
        when(indexTaskMapper.selectOne(any())).thenReturn(null);
        when(indexTaskMapper.insert(any(IndexTaskEntity.class))).thenAnswer(invocation -> {
            IndexTaskEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return 1;
        });

        IndexTaskEntity task = indexTaskService.createInitCloneTask(88L, "main");

        Assertions.assertEquals(TaskType.CLONE_REPO, task.getTaskType());
        Assertions.assertEquals("88:main:CLONE_REPO:INIT", task.getIdempotentKey());
    }

    @Test
    void createReindexCloneTask_twice_shouldGenerateDifferentIdempotentKeys() {
        when(indexTaskMapper.selectOne(any())).thenReturn(null);
        when(indexTaskMapper.insert(any(IndexTaskEntity.class))).thenReturn(1);

        indexTaskService.createReindexCloneTask(99L, "develop", null);
        indexTaskService.createReindexCloneTask(99L, "develop", null);

        ArgumentCaptor<IndexTaskEntity> captor = ArgumentCaptor.forClass(IndexTaskEntity.class);
        verify(indexTaskMapper, times(2)).insert(captor.capture());
        String firstKey = captor.getAllValues().get(0).getIdempotentKey();
        String secondKey = captor.getAllValues().get(1).getIdempotentKey();

        Assertions.assertNotEquals(firstKey, secondKey);
        Assertions.assertTrue(firstKey.startsWith("99:develop:CLONE_REPO:REINDEX:"));
        Assertions.assertTrue(secondKey.startsWith("99:develop:CLONE_REPO:REINDEX:"));
    }

    @Test
    void buildCommitStageIdempotentKey_shouldFollowPipelineConvention() {
        Assertions.assertEquals("12:abc123:PARSE_CODE",
                indexTaskService.buildCommitStageIdempotentKey(12L, "abc123", TaskType.PARSE_CODE));
        Assertions.assertEquals("12:abc123:BUILD_CHUNK",
                indexTaskService.buildCommitStageIdempotentKey(12L, "abc123", TaskType.BUILD_CHUNK));
        Assertions.assertEquals("12:abc123:EMBED_CODE",
                indexTaskService.buildCommitStageIdempotentKey(12L, "abc123", TaskType.EMBED_CHUNK));
        Assertions.assertEquals("12:abc123:UPSERT_VECTOR",
                indexTaskService.buildCommitStageIdempotentKey(12L, "abc123", TaskType.UPSERT_VECTOR));
    }
}
