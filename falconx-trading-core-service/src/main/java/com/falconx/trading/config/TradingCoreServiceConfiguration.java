package com.falconx.trading.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * trading-core-service 配置入口。
 *
 * <p>当前阶段该配置类只负责开启交易核心属性绑定。
 * 后续当 Stage 3B 逐步接入真实数据库、Kafka 和 Redis 实现时，
 * 统一在该包下继续扩展，但不应改变“应用层编排 + 服务层规则 + 仓储层 owner 数据”的分层方向。
 */
@Configuration
@EnableKafka
@EnableConfigurationProperties(TradingCoreServiceProperties.class)
public class TradingCoreServiceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TradingCoreServiceConfiguration.class);
    private static final long PRICE_TICK_RETRY_INTERVAL_MILLIS = 1_000L;
    private static final long PRICE_TICK_RETRY_ATTEMPTS = 2L;

    /**
     * 交易核心统一 JSON 序列化器。
     *
     * <p>Stage 5 开始，交易核心需要把事件 payload 真正落入 `t_outbox`，而这些 payload 中包含
     * `OffsetDateTime` 等 Java 时间类型。这里显式注册统一 `ObjectMapper`，避免各仓储层各自创建
     * 没有时间模块的 JSON 序列化器，导致真实持久化链路在运行时失败。
     *
     * @return 已自动注册常用模块的 JSON 序列化器
     */
    @Bean
    ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .build();
    }

    /**
     * 注册 Kafka 默认监听容器工厂。
     *
     * <p>交易核心存在多个 `@KafkaListener` 入口，这里统一复用 Spring Kafka 自动配置的
     * `ConsumerFactory` 构造默认容器工厂，避免监听器因缺少默认 Bean 名称而无法启动。
     *
     * @param consumerFactory Spring Kafka 自动配置好的消费者工厂
     * @return 默认 Kafka 监听容器工厂
     */
    @Bean(name = "kafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }

    /**
     * 为高频 `market.price.tick` 注册显式重试容器工厂。
     *
     * <p>Stage 6A 需要补齐“Kafka 入口失败重试专项”证据，但又不能改变高频 tick
     * “直连 Kafka、不写 Inbox”的既有模型，因此只在监听入口显式配置固定退避重试。
     *
     * @param consumerFactory Spring Kafka 自动配置好的消费者工厂
     * @return 专供 `market.price.tick` 使用的监听容器工厂
     */
    @Bean(name = "marketPriceTickKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String> marketPriceTickKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            TradingCoreServiceProperties properties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(Math.max(1, properties.getKafka().getMarketPriceTickWorkerCount()));
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, exception) -> log.error(
                        "trading.kafka.consume.dead topic={} partition={} offset={} key={} message={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        record.key(),
                        exception.getMessage(),
                        exception
                ),
                new FixedBackOff(PRICE_TICK_RETRY_INTERVAL_MILLIS, PRICE_TICK_RETRY_ATTEMPTS)
        );
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
