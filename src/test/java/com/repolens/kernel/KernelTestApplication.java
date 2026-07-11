package com.repolens.kernel;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * 重写内核 E2E 的最小 Spring 上下文：只装 {@code com.repolens.kernel} 下的组件 + 数据源/MyBatis-Plus 自动配置，
 * 不启全量 {@code RepoLensApplication}（避免 Redis/RocketMQ/Milvus/RAG 等外部依赖拖垮上下文）。
 * 这样 E2E 聚焦真验证闭环本身，又是货真价实的 Spring + 真 MySQL 端到端。
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisReactiveAutoConfiguration.class})
@ComponentScan("com.repolens.kernel")
@MapperScan("com.repolens.kernel.persistence.mapper")
public class KernelTestApplication {
}
