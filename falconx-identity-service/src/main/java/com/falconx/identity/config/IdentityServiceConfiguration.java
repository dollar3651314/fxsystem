package com.falconx.identity.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.falconx.identity.repository.IdentityInboxRepository;
import com.falconx.identity.repository.IdentityUserRepository;
import com.falconx.identity.repository.RefreshTokenSessionRepository;
import com.falconx.identity.service.IdentityTokenService;
import com.falconx.identity.service.PasswordHashService;
import com.falconx.identity.service.impl.BCryptPasswordHashService;
import com.falconx.identity.service.impl.RsaIdentityTokenService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * identity-service 基础配置入口。
 *
 * <p>当前阶段已经切换到真实 owner 仓储实现，这里只保留与身份领域无关的公共配置装配：
 *
 * <ul>
 *   <li>统一 JSON 序列化器</li>
 *   <li>密码哈希服务</li>
 *   <li>JWT 签发服务</li>
 * </ul>
 *
 * <p>后续若接入正式密钥管理，只允许替换密钥来源，不应改变这里的 Bean 边界。
 */
@Configuration
@EnableKafka
@EnableConfigurationProperties(IdentityServiceProperties.class)
public class IdentityServiceConfiguration {

    @Bean
    ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .build();
    }

    @Bean
    PasswordHashService passwordHashService(IdentityServiceProperties properties) {
        return new BCryptPasswordHashService(properties);
    }

    @Bean
    IdentityTokenService identityTokenService(IdentityServiceProperties properties,
                                              IdentityUserRepository identityUserRepository,
                                              RefreshTokenSessionRepository refreshTokenSessionRepository,
                                              ObjectMapper objectMapper) {
        return new RsaIdentityTokenService(
                properties,
                identityUserRepository,
                refreshTokenSessionRepository,
                objectMapper
        );
    }

    /**
     * 注册 Kafka 默认监听容器工厂。
     *
     * <p>`@KafkaListener` 默认会按 `kafkaListenerContainerFactory` 这个 Bean 名称查找容器工厂。
     * 这里显式注册，避免服务在接入真实 Kafka listener 后因默认 Bean 缺失导致上下文启动失败。
     *
     * @param consumerFactory Spring Kafka 自动配置好的消费者工厂
     * @return 默认监听容器工厂
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
}
