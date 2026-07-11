package com.repolens.service;

import com.repolens.service.impl.MockEmbeddingService;
import com.repolens.service.impl.OpenAiCompatibleEmbeddingService;
import com.repolens.service.impl.RoutingEmbeddingService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutingEmbeddingServiceTest {

    private static final int DIMENSION = 384;

    @Mock
    private MockEmbeddingService mockEmbeddingService;
    @Mock
    private OpenAiCompatibleEmbeddingService openAiCompatibleEmbeddingService;

    @InjectMocks
    private RoutingEmbeddingService routingEmbeddingService;

    @Test
    void embedBatch_shouldRouteToMockProvider() {
        ReflectionTestUtils.setField(routingEmbeddingService, "provider", "mock");
        when(mockEmbeddingService.embedBatch(List.of("alpha"))).thenReturn(List.of(new float[4]));

        routingEmbeddingService.embedBatch(List.of("alpha"));

        verify(mockEmbeddingService).embedBatch(List.of("alpha"));
        verifyNoInteractions(openAiCompatibleEmbeddingService);
    }

    @Test
    void embedBatch_shouldRouteToOpenAiCompatibleIgnoringCase() {
        ReflectionTestUtils.setField(routingEmbeddingService, "provider", "OpenAI-Compatible");
        when(openAiCompatibleEmbeddingService.embedBatch(List.of("alpha"))).thenReturn(List.of(new float[4]));

        routingEmbeddingService.embedBatch(List.of("alpha"));

        verify(openAiCompatibleEmbeddingService).embedBatch(List.of("alpha"));
    }

    @Test
    void embedBatch_shouldThrowForUnsupportedProvider() {
        ReflectionTestUtils.setField(routingEmbeddingService, "provider", "unknown-provider");

        EmbeddingClientException ex = Assertions.assertThrows(
                EmbeddingClientException.class,
                () -> routingEmbeddingService.embedBatch(List.of("alpha"))
        );

        Assertions.assertEquals("EMBEDDING_CONFIG_MISSING", ex.getErrorCode());
        Assertions.assertTrue(ex.getMessage().contains("Unsupported"));
    }

    /**
     * Fail-safe：真实 provider 抛异常时应回退到 mock，而不是把异常抛给检索链路。
     * 用真实 MockEmbeddingService 断言回退仍返回配置维度的向量。
     */
    @Test
    void embed_shouldFallBackToMockWhenRealProviderThrows() {
        RoutingEmbeddingService routing = new RoutingEmbeddingService(
                realMockEmbeddingService(), openAiCompatibleEmbeddingService);
        ReflectionTestUtils.setField(routing, "provider", "openai-compatible");
        when(openAiCompatibleEmbeddingService.embed("query"))
                .thenThrow(new EmbeddingClientException("EMBEDDING_HTTP_ERROR", "endpoint down"));

        float[] vector = routing.embed("query");

        Assertions.assertNotNull(vector);
        Assertions.assertEquals(DIMENSION, vector.length);
    }

    @Test
    void embedBatch_shouldFallBackToMockWhenRealProviderThrows() {
        RoutingEmbeddingService routing = new RoutingEmbeddingService(
                realMockEmbeddingService(), openAiCompatibleEmbeddingService);
        ReflectionTestUtils.setField(routing, "provider", "openai-compatible");
        when(openAiCompatibleEmbeddingService.embedBatch(List.of("a", "b")))
                .thenThrow(new EmbeddingClientException("EMBEDDING_TIMEOUT", "timed out"));

        List<float[]> vectors = routing.embedBatch(List.of("a", "b"));

        Assertions.assertEquals(2, vectors.size());
        Assertions.assertEquals(DIMENSION, vectors.get(0).length);
        Assertions.assertEquals(DIMENSION, vectors.get(1).length);
    }

    private MockEmbeddingService realMockEmbeddingService() {
        MockEmbeddingService service = new MockEmbeddingService();
        ReflectionTestUtils.setField(service, "dimension", DIMENSION);
        return service;
    }
}
