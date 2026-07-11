package com.repolens.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.mq.RepoIndexMessage;
import com.repolens.service.RepoAsyncIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 异步索引消费者。
 * 这个类故意很薄，只负责：
 * 1. 从 RocketMQ 收到阶段消息；
 * 2. 反序列化成 RepoIndexMessage；
 * 3. 交给 RepoAsyncIndexService 处理。
 *
 * 这样做的目的，是把“消息消费”与“业务编排”解耦，方便本地禁用 MQ 或单测服务层。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "repolens.index.consumer-enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${repolens.index.topic:repo_index_topic}",
        consumerGroup = "${repolens.index.consumer-group:repolens-index-consumer}",
        selectorExpression = "*"
)
public class IndexTaskConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper;
    private final RepoAsyncIndexService repoAsyncIndexService;

    /**
     * 消费一条索引阶段消息。
     * 反序列化失败时直接抛异常，让 MQ 感知消费失败，而不是静默吞掉坏消息。
     */
    @Override
    public void onMessage(String payload) {
        try {
            RepoIndexMessage message = objectMapper.readValue(payload, RepoIndexMessage.class);
            repoAsyncIndexService.handleIndexMessage(message);
        } catch (Exception ex) {
            log.error("Consume index message failed, payload={}", payload, ex);
            throw new RuntimeException(ex);
        }
    }
}
