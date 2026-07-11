package com.repolens.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.enums.TaskType;
import com.repolens.domain.mq.RepoIndexMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 异步索引消息生产者。
 * 这里的设计原则很明确：
 * - MySQL 中的 index_task 才是事实状态；
 * - RocketMQ 只是异步触发器，不承担最终状态存储；
 * - 因此 send 失败时必须回到 task 状态机做补偿，而不是只依赖 MQ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepoIndexMessageProducer {

    private final ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider;
    private final ObjectMapper objectMapper;

    @Value("${repolens.index.topic:repo_index_topic}")
    private String topic;

    @Value("${repolens.index.mq-enabled:true}")
    private boolean mqEnabled;

    @Value("${repolens.index.send-timeout-ms:5000}")
    private long sendTimeoutMs;

    /**
     * 发送一条阶段消息。
     * 消息体里只放编排所需的最小元数据：repoId、taskId、taskType、幂等键、traceId。
     */
    public void sendIndexMessage(RepoIndexMessage message) {
        if (!mqEnabled) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR, "RocketMQ send is disabled by configuration");
        }
        RocketMQTemplate rocketMQTemplate = rocketMQTemplateProvider.getIfAvailable();
        if (rocketMQTemplate == null) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR, "RocketMQTemplate is unavailable");
        }

        String tag = resolveTag(message.getTaskType());
        String destination = topic + ":" + tag;
        String payload = toJson(message);

        try {
            SendResult sendResult = rocketMQTemplate.syncSend(destination, payload, sendTimeoutMs);
            log.info("Index message sent, destination={}, repoId={}, taskId={}, traceId={}, msgId={}",
                    destination, message.getRepoId(), message.getTaskId(), message.getTraceId(),
                    sendResult.getMsgId());
        } catch (Exception ex) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR, "RocketMQ send failed: " + ex.getMessage());
        }
    }

    /**
     * tag 与任务阶段一一对应，便于后续按阶段观察或扩展消费逻辑。
     */
    private String resolveTag(TaskType taskType) {
        return switch (taskType) {
            case CLONE_REPO -> "CLONE_REPO";
            case PARSE_CODE -> "PARSE_CODE";
            case BUILD_CHUNK -> "BUILD_CHUNK";
            case VECTORIZE_CHUNK -> "VECTORIZE_CHUNK";
            default -> taskType.name();
        };
    }

    /**
     * 序列化失败属于编排输入异常，应当尽早在发送前失败。
     */
    private String toJson(RepoIndexMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Serialize index message failed: " + ex.getMessage());
        }
    }
}
