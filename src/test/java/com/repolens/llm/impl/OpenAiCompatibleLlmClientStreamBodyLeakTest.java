package com.repolens.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.llm.LlmClientException;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * C2 验证：generateStream / generateStreamWithTools 的非 2xx 响应 body InputStream 必须被读取并关闭，
 * 避免在高频调用时泄漏 selector 线程和文件描述符。
 *
 * <h3>测试策略</h3>
 * 本地 HttpServer 第一次请求返回 401/429 + 512 KB 大体（远超典型 OS socket 缓冲区 ~128 KB）：
 * <ul>
 *   <li><b>NEW (closing) code</b> — {@code readBodySnippet()} 读取 512 字节后关闭 InputStream。
 *       JDK HttpClient 接到 close() 信号后清理连接（排水剩余字节或发 RST），服务端写入完成或抛
 *       IOException，处理器及时退出，CountDownLatch 在 3 s 内被释放。<b>断言通过。</b></li>
 *   <li><b>OLD (leaking) code</b> — InputStream 从未被读取或关闭。JDK 保持连接"进行中"状态，
 *       不主动排水；当 OS 发送 + 接收缓冲区（合计 ~128–256 KB）填满后，服务端写入永久阻塞。
 *       CountDownLatch 在 3 s 超时内无法释放。<b>断言失败。</b></li>
 * </ul>
 *
 * <p>注意：不断言服务端写入是否抛 IOException——是否抛取决于 JDK 选择"排水"还是"RST"，
 * 两种行为都证明客户端正确关闭了连接（服务端不会永久阻塞）。
 */
class OpenAiCompatibleLlmClientStreamBodyLeakTest {

    /** 512 KB > 典型 OS socket 缓冲区：若客户端既不读也不关闭，服务端写入将阻塞。 */
    private static final int LARGE_BODY_SIZE = 512 * 1024;

    /** generate() 回落路径使用的小 JSON 响应，以保证回落请求快速完成。 */
    private static final byte[] SMALL_401_BODY =
            "{\"error\":{\"message\":\"Unauthorized\"}}".getBytes(StandardCharsets.UTF_8);

    @Test
    void generateStream_bodyStreamIsClosedOnNon2xxResponse_serverHandlerExitsPromptly()
            throws Exception {
        byte[] largeBody = new byte[LARGE_BODY_SIZE];
        Arrays.fill(largeBody, (byte) 'X');

        AtomicInteger reqCount = new AtomicInteger(0);
        // Released when the first handler exits (regardless of IOException or success).
        CountDownLatch firstHandlerDone = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            int req = reqCount.incrementAndGet();
            if (req == 1) {
                // First request — streaming path.  Large body so the write blocks when client
                // holds the connection open without draining (OLD leaking code).
                exchange.sendResponseHeaders(401, largeBody.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(largeBody); // blocks if client is not reading/closing
                } catch (IOException ignored) {
                    // IOException is acceptable: JDK chose to RST the connection after close().
                } finally {
                    firstHandlerDone.countDown(); // released either way once the handler exits
                }
            } else {
                // Subsequent requests (generate() fallback): tiny body for fast completion.
                exchange.sendResponseHeaders(401, SMALL_401_BODY.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(SMALL_401_BODY);
                } catch (IOException ignored) {}
            }
        });
        server.start();

        try {
            LlmRuntimeConfig cfg = runtimeConfig(
                    "http://localhost:" + server.getAddress().getPort(), "bad-key", "test-model", 5000);
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(new ObjectMapper(), cfg);

            LlmRequest request = LlmRequest.builder().systemPrompt("sys").userPrompt("user").build();

            try {
                client.generateStream(request, token -> {}, response -> {});
                Assertions.fail("Expected LlmClientException but none thrown");
            } catch (LlmClientException ex) {
                Assertions.assertNotNull(ex.getErrorCode(), "error code should be non-null");
            } catch (Exception ex) {
                // generateStream may wrap; any exception is acceptable here.
            }

            // Key assertion: server's first handler must exit within 3 s.
            //
            // NEW code: readBodySnippet() reads 512 bytes and closes the InputStream.
            //   JDK drains the remaining bytes to clean up the connection, unblocking the
            //   server's os.write(); the handler exits and releases the latch.  → PASS
            //
            // OLD code: InputStream is never read or closed.
            //   JDK keeps the connection pending and issues no drain; the server's write
            //   blocks when OS buffers (~128 KB) fill up.  → latch not released → FAIL
            boolean handledInTime = firstHandlerDone.await(3, TimeUnit.SECONDS);
            Assertions.assertTrue(handledInTime,
                    "Server's first (streaming) handler did not complete within 3 s — " +
                    "the response InputStream was likely leaked (not closed by readBodySnippet), " +
                    "blocking the server's write indefinitely");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateStreamWithTools_bodyStreamIsClosedOnNon2xxResponse_serverHandlerExitsPromptly()
            throws Exception {
        byte[] largeBody = new byte[LARGE_BODY_SIZE];
        Arrays.fill(largeBody, (byte) 'Y');

        AtomicInteger reqCount = new AtomicInteger(0);
        CountDownLatch firstHandlerDone = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            int req = reqCount.incrementAndGet();
            if (req == 1) {
                exchange.sendResponseHeaders(429, largeBody.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(largeBody);
                } catch (IOException ignored) {
                } finally {
                    firstHandlerDone.countDown();
                }
            } else {
                exchange.sendResponseHeaders(429, SMALL_401_BODY.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(SMALL_401_BODY);
                } catch (IOException ignored) {}
            }
        });
        server.start();

        try {
            LlmRuntimeConfig cfg = runtimeConfig(
                    "http://localhost:" + server.getAddress().getPort(), "key", "test-model", 5000);
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(new ObjectMapper(), cfg);

            LlmRequest request = LlmRequest.builder().systemPrompt("sys").userPrompt("user").build();

            try {
                client.generateStreamWithTools(request, new com.repolens.llm.StreamWithToolsListener() {
                    @Override public void onContentToken(String token) {}
                    @Override public void onToolCallStart(String toolName) {}
                    @Override public void onDone(com.repolens.llm.model.LlmResponse response) {}
                });
                Assertions.fail("Expected LlmClientException but none thrown");
            } catch (LlmClientException ex) {
                Assertions.assertNotNull(ex.getErrorCode());
            } catch (Exception ex) {
                // Any exception is acceptable; what matters is the server handler did not block.
            }

            boolean handledInTime = firstHandlerDone.await(3, TimeUnit.SECONDS);
            Assertions.assertTrue(handledInTime,
                    "Server's first (streaming-with-tools) handler did not complete within 3 s — " +
                    "response InputStream was likely leaked (not closed by readBodySnippet)");
        } finally {
            server.stop(0);
        }
    }

    private static LlmRuntimeConfig runtimeConfig(
            String baseUrl, String apiKey, String modelName, int timeoutMs) {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(cfg, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(cfg, "apiKey", apiKey);
        ReflectionTestUtils.setField(cfg, "modelName", modelName);
        ReflectionTestUtils.setField(cfg, "timeoutMs", timeoutMs);
        return cfg;
    }
}
