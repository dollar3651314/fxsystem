package com.falconx.identity.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * identity-service Kafka 执行器配置。
 *
 * <p>身份域低频 Kafka 事件同样不能直接在 listener 回调线程里进入 owner 事务写路径。
 * 这里提供 identity-service 自管执行器，统一承接入账完成事件的业务消费。
 */
@Configuration
public class IdentityKafkaExecutionConfiguration {

    /**
     * identity Kafka 业务执行器。
     *
     * <p>当前保持单线程和同步等待结果，优先修复线程边界问题，
     * 不改变现有 Kafka 容器的异常回抛与重试行为。
     */
    @Bean(name = "identityKafkaExecutor", destroyMethod = "shutdown")
    public ExecutorService identityKafkaExecutor() {
        return Executors.newSingleThreadExecutor(Thread.ofPlatform()
                .name("identity-kafka-", 0)
                .factory());
    }
}
