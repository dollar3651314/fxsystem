package com.falconx.trading.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * trading-core Kafka 执行器配置。
 *
 * <p>低频 Kafka 事件虽然不具备 `market.price.tick` 的高频压力，
 * 但同样禁止在 Kafka listener 回调线程里直接进入 owner 业务写链路。
 * 这里提供 trading-core 自管执行器，供低频事件先切线程再进入事务处理。
 */
@Configuration
public class TradingKafkaExecutionConfiguration {

    /**
     * 低频 Kafka 业务执行器。
     *
     * <p>当前固定为单线程，保持本地执行顺序稳定，
     * 同时继续让 listener 线程同步等待处理结果，以保留现有异常回抛和容器重试语义。
     */
    @Bean(name = "tradingLowFrequencyKafkaExecutor", destroyMethod = "shutdown")
    public ExecutorService tradingLowFrequencyKafkaExecutor() {
        return Executors.newSingleThreadExecutor(Thread.ofPlatform()
                .name("trading-kafka-low-frequency-", 0)
                .factory());
    }
}
