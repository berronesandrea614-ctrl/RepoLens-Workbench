package com.repolens.service;

import java.util.function.Consumer;

/**
 * Feishu (Lark) client interface — decouples business logic from SDK,
 * enabling straightforward mocking in unit tests.
 */
public interface FeishuClient {

    /**
     * Establishes a long-running WebSocket event subscription (im.message.receive_v1).
     * Calls {@code onMessage} with the plain text content of each received text message.
     *
     * @param appId     Feishu App ID
     * @param appSecret Feishu App Secret (plaintext)
     * @param onMessage callback invoked for each received text message
     * @throws Exception if the connection cannot be established
     */
    void connect(String appId, String appSecret, Consumer<String> onMessage) throws Exception;

    /**
     * Sends a text message to the chat associated with {@code appId}.
     * The target chat ID is determined by the first message received on this connection.
     *
     * @param appId     Feishu App ID
     * @param appSecret Feishu App Secret (plaintext)
     * @param text      message body to send
     * @throws Exception if the send fails
     */
    void sendMessage(String appId, String appSecret, String text) throws Exception;

    /**
     * Disconnects the WebSocket for the given App ID (best-effort, fail-safe).
     *
     * @param appId Feishu App ID
     */
    void disconnect(String appId);
}
