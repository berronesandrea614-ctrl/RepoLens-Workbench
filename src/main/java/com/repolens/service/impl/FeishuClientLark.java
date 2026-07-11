package com.repolens.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.repolens.service.FeishuClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Feishu (Lark) client implementation using the official oapi-sdk 2.4.x.
 *
 * <p><b>Long connection strategy</b>: Uses {@code com.lark.oapi.ws.Client} WS subscription.
 * {@link com.lark.oapi.ws.Client#start()} is blocking, so it runs in a daemon thread pool.
 *
 * <p><b>Reply chat_id strategy (MVP)</b>: Records the chat_id from the <em>first</em> message
 * received on each appId connection. All outbound messages go to that chat.
 * For production, a persistent per-user chat_id store would be used instead.
 *
 * <p><b>Disconnect</b>: {@link com.lark.oapi.ws.Client#disconnect()} is {@code protected};
 * invoked via reflection (fail-safe). Marked as "需真机验证的 SDK 用法".
 *
 * <p><b>All Feishu SDK calls are fail-safe</b>: exceptions are caught and logged;
 * they never propagate to crash the application.
 */
@Slf4j
@Component
@Primary
public class FeishuClientLark implements FeishuClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Active WS clients keyed by appId. */
    private final Map<String, com.lark.oapi.ws.Client> wsClients = new ConcurrentHashMap<>();

    /**
     * First chat_id seen per appId — used as the reply target for outbound messages.
     * MVP simplification; see class javadoc.
     */
    private final Map<String, String> chatIdMap = new ConcurrentHashMap<>();

    /** Background threads for blocking WS .start() calls. */
    private final ExecutorService wsExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "feishu-ws");
        t.setDaemon(true);
        return t;
    });

    // ── FeishuClient ─────────────────────────────────────────────────────────

    @Override
    public void connect(String appId, String appSecret, Consumer<String> onMessage) throws Exception {
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        try {
                            P2MessageReceiveV1Data data = event.getEvent();
                            if (data == null || data.getMessage() == null) return;
                            EventMessage msg = data.getMessage();

                            // Record first chat_id for reply target
                            String chatId = msg.getChatId();
                            if (chatId != null && !chatId.isBlank()) {
                                chatIdMap.putIfAbsent(appId, chatId);
                            }

                            // Only handle text-type messages
                            if (!"text".equals(msg.getMessageType())) return;

                            String content = msg.getContent();
                            if (content == null || content.isBlank()) return;

                            // Content is JSON: {"text":"user input"}
                            String text = MAPPER.readTree(content).path("text").asText("");
                            if (!text.isBlank()) {
                                onMessage.accept(text);
                            }
                        } catch (Exception e) {
                            log.warn("[Feishu] Error processing received message for appId={}: {}",
                                    appId, e.getMessage());
                        }
                    }
                })
                .build();

        com.lark.oapi.ws.Client wsClient = new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                .eventHandler(dispatcher)
                .autoReconnect(true)
                .build();

        // Close any existing connection for this appId to prevent thread/resource leak
        if (wsClients.containsKey(appId)) {
            log.info("[Feishu] Replacing existing WS connection for appId={}, disconnecting old", appId);
            disconnect(appId);
        }
        wsClients.put(appId, wsClient);

        // start() is blocking — run in daemon thread
        wsExecutor.submit(() -> {
            try {
                log.info("[Feishu] WS connecting for appId={}", appId);
                wsClient.start();
                log.info("[Feishu] WS disconnected for appId={}", appId);
            } catch (Exception e) {
                log.error("[Feishu] WS error for appId={}: {}", appId, e.getMessage());
            }
        });
    }

    @Override
    public void sendMessage(String appId, String appSecret, String text) throws Exception {
        String chatId = chatIdMap.get(appId);
        if (chatId == null) {
            log.warn("[Feishu] No chat_id recorded for appId={}; message not sent. " +
                    "Send at least one message to the bot first.", appId);
            // 【需真机验证的 SDK 用法】: In production consider fetching bot's own chat list
            return;
        }

        // Build a new API client per call (lightweight; no long-lived state needed for REST)
        // 【需真机验证的 SDK 用法】: Client creation is cheap but if SDK caches tokens globally,
        // multiple Client instances per appId may cause token-refresh contention.
        Client apiClient = Client.newBuilder(appId, appSecret).build();

        String contentJson = MAPPER.writeValueAsString(Map.of("text", text));

        CreateMessageReqBody body = CreateMessageReqBody.newBuilder()
                .receiveId(chatId)
                .msgType("text")
                .content(contentJson)
                .build();

        CreateMessageReq req = CreateMessageReq.newBuilder()
                .receiveIdType("chat_id")
                .createMessageReqBody(body)
                .build();

        CreateMessageResp resp = apiClient.im().v1().message().create(req);
        if (!resp.success()) {
            log.warn("[Feishu] sendMessage failed for appId={}: code={} msg={}",
                    appId, resp.getCode(), resp.getMsg());
            throw new RuntimeException("Feishu sendMessage failed: " + resp.getMsg());
        }
        log.debug("[Feishu] Message sent to chatId={} appId={}", chatId, appId);
    }

    @Override
    public void disconnect(String appId) {
        chatIdMap.remove(appId);
        com.lark.oapi.ws.Client wsClient = wsClients.remove(appId);
        if (wsClient == null) return;
        // 关键：飞书 SDK 的 ws.Client 内置断线自动重连（autoReconnect=true），
        // 仅调 disconnect() 会被 SDK 立刻重连回来（解绑/删除“没作用”的真凶）。
        // 必须先反射把 autoReconnect 置 false，再 disconnect，并兜底关闭底层 WebSocket。
        try {
            java.lang.reflect.Field ar = com.lark.oapi.ws.Client.class.getDeclaredField("autoReconnect");
            ar.setAccessible(true);
            ar.set(wsClient, Boolean.FALSE);
        } catch (Exception e) {
            log.warn("[Feishu] Could not disable autoReconnect for appId={}: {}", appId, e.getMessage());
        }
        try {
            Method m = com.lark.oapi.ws.Client.class.getDeclaredMethod("disconnect");
            m.setAccessible(true);
            m.invoke(wsClient);
            log.info("[Feishu] WS disconnected (autoReconnect off) for appId={}", appId);
        } catch (Exception e) {
            log.warn("[Feishu] Reflection disconnect failed for appId={}: {}", appId, e.getMessage());
        }
        // 兜底：直接关闭底层 WebSocket，确保连接真正终止、不再重连。
        try {
            java.lang.reflect.Field cf = com.lark.oapi.ws.Client.class.getDeclaredField("conn");
            cf.setAccessible(true);
            Object conn = cf.get(wsClient);
            if (conn != null) {
                conn.getClass().getMethod("close", int.class, String.class).invoke(conn, 1000, "unbind");
                log.info("[Feishu] underlying WebSocket closed for appId={}", appId);
            }
        } catch (Exception e) {
            log.warn("[Feishu] Could not close underlying WebSocket for appId={}: {}", appId, e.getMessage());
        }
    }
}
