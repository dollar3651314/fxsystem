package com.falconx.infrastructure.config;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;

/**
 * STAGE-13 SystemConfigClient 自动装配。
 *
 * <p>各业务 service 只需在 application.yml 加：
 * <pre>
 * falconx:
 *   system-config:
 *     enabled: true
 *     console-base-url: http://localhost:18085
 *     internal-token: ${FALCONX_INTERNAL_API_TOKEN:falconx-internal-dev-token}
 * </pre>
 *
 * <p>注入：{@code @Autowired SystemConfigClient configClient;}
 */
@Configuration
@ConditionalOnClass({ ReactiveRedisConnectionFactory.class, ObjectMapper.class })
@ConditionalOnProperty(prefix = "falconx.system-config", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SystemConfigClientProperties.class)
public class SystemConfigClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SystemConfigClient systemConfigClient(SystemConfigClientProperties properties,
                                                 ReactiveRedisConnectionFactory connectionFactory,
                                                 ObjectMapper objectMapper) {
        return new DefaultSystemConfigClient(properties, connectionFactory, objectMapper);
    }
}
