package com.repolens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.service.impl.OpenAiCompatibleEmbeddingService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class OpenAiCompatibleEmbeddingServiceTest {

    @Test
    void embedBatch_shouldCallEmbeddingsEndpointAndParseVectors() throws Exception {
        AtomicReference<String> pathRef = new AtomicReference<>();
        AtomicReference<String> authRef = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            pathRef.set(exchange.getRequestURI().getPath());
            authRef.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] responseBody = """
                    {
                      "data": [
                        {"index": 0, "embedding": [0.1, 0.2, 0.3, 0.4]},
                        {"index": 1, "embedding": [0.5, 0.6, 0.7, 0.8]}
                      ],
                      "usage": {
                        "prompt_tokens": 12,
                        "total_tokens": 12
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }
        });
        server.start();

        try {
            OpenAiCompatibleEmbeddingService service = new OpenAiCompatibleEmbeddingService(new ObjectMapper());
            ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:" + server.getAddress().getPort() + "/v1/");
            ReflectionTestUtils.setField(service, "apiKey", "test-embedding-key");
            ReflectionTestUtils.setField(service, "defaultModelName", "test-embedding-model");
            ReflectionTestUtils.setField(service, "embeddingDimension", 4);
            ReflectionTestUtils.setField(service, "defaultTimeoutMs", 3000);

            List<float[]> vectors = service.embedBatch(List.of("alpha", "beta"));

            Assertions.assertEquals("/v1/embeddings", pathRef.get());
            Assertions.assertEquals("Bearer test-embedding-key", authRef.get());
            Assertions.assertEquals(2, vectors.size());
            Assertions.assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, vectors.get(0));
            Assertions.assertArrayEquals(new float[]{0.5f, 0.6f, 0.7f, 0.8f}, vectors.get(1));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embedBatch_shouldThrowWhenConfigMissing() {
        OpenAiCompatibleEmbeddingService service = new OpenAiCompatibleEmbeddingService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseUrl", "");
        ReflectionTestUtils.setField(service, "apiKey", "");
        ReflectionTestUtils.setField(service, "defaultModelName", "test-embedding-model");
        ReflectionTestUtils.setField(service, "embeddingDimension", 4);
        ReflectionTestUtils.setField(service, "defaultTimeoutMs", 3000);

        EmbeddingClientException ex = Assertions.assertThrows(
                EmbeddingClientException.class,
                () -> service.embedBatch(List.of("alpha"))
        );

        Assertions.assertEquals("EMBEDDING_CONFIG_MISSING", ex.getErrorCode());
        Assertions.assertTrue(ex.getMessage().contains("base-url"));
    }

    @Test
    void embedBatch_shouldThrowWhenDimensionMismatch() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/embeddings", exchange -> {
            byte[] responseBody = """
                    {
                      "data": [
                        {"index": 0, "embedding": [0.1, 0.2, 0.3]}
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }
        });
        server.start();

        try {
            OpenAiCompatibleEmbeddingService service = new OpenAiCompatibleEmbeddingService(new ObjectMapper());
            ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(service, "apiKey", "test-embedding-key");
            ReflectionTestUtils.setField(service, "defaultModelName", "test-embedding-model");
            ReflectionTestUtils.setField(service, "embeddingDimension", 4);
            ReflectionTestUtils.setField(service, "defaultTimeoutMs", 3000);

            EmbeddingClientException ex = Assertions.assertThrows(
                    EmbeddingClientException.class,
                    () -> service.embedBatch(List.of("alpha"))
            );

            Assertions.assertEquals("EMBEDDING_DIMENSION_MISMATCH", ex.getErrorCode());
        } finally {
            server.stop(0);
        }
    }
}
