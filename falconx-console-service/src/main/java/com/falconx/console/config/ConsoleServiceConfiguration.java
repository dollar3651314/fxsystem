package com.falconx.console.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * console-service 基础配置入口。
 *
 * <p>本类只装配与管理端业务无关的通用 Bean：
 *
 * <ul>
 *   <li>统一 JSON 序列化器（Jackson 3，与 C 端服务保持一致）</li>
 *   <li>{@link ConsoleServiceProperties} 配置属性绑定</li>
 * </ul>
 *
 * <p>{@code SnowflakeIdGenerator} 是 {@code falconx-infrastructure} 的 {@code @Component}，
 * 由 {@link com.falconx.console.ConsoleServiceApplication} 的 {@code scanBasePackages} 自动扫描注入。
 *
 * <p>token / 鉴权 / IP 白名单 / 默认超管初始化等装配分别在 R9.2、R9.3、R9.4 子任务的对应 Configuration 中。
 */
@Configuration
@EnableConfigurationProperties(ConsoleServiceProperties.class)
public class ConsoleServiceConfiguration {

    /**
     * 装配统一 ObjectMapper（与 falconx-identity-service 一致）。
     *
     * @return ObjectMapper
     */
    @Bean
    ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .build();
    }
}
