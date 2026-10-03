package com.falconx.console.config;

import com.falconx.console.security.AdminIpWhitelistFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理端 IP 白名单 filter 装配。
 *
 * <p>filter order = 100，先于 {@link AdminTokenConfiguration} 注册的鉴权 filter（order=200）执行，
 * 确保 IP 白名单是请求进入应用后的第一道防线（gateway 路由级是更上游的防线，由 R4 单独实施）。
 *
 * <p>本 filter 在 {@code dev} / {@code test} profile 下因 {@code ip-whitelist-enabled=false} 自动放行，
 * 不影响本地开发与集成测试。
 */
@Configuration
public class AdminIpWhitelistConfiguration {

    /**
     * 注册管理端 IP 白名单 filter。
     *
     * @param properties console 配置属性
     * @param objectMapper Jackson 序列化器
     * @return filter 注册 bean
     */
    @Bean
    FilterRegistrationBean<AdminIpWhitelistFilter> adminIpWhitelistFilterRegistration(
            ConsoleServiceProperties properties,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<AdminIpWhitelistFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AdminIpWhitelistFilter(properties, objectMapper));
        registration.addUrlPatterns("/admin/*");
        registration.setName("adminIpWhitelistFilter");
        registration.setOrder(100);
        return registration;
    }
}
