package com.falconx.console.config;

import com.falconx.console.security.AdminAuthenticationFilter;
import com.falconx.console.security.AdminTokenBlacklistService;
import com.falconx.console.security.AdminTokenSupport;
import com.falconx.console.security.RsaAdminTokenSupport;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理端 token 与鉴权 filter 装配。
 *
 * <p>装配 {@link AdminTokenSupport} bean 与 {@link AdminAuthenticationFilter} 注册：
 *
 * <ul>
 *   <li>filter 顺序：在 IP 白名单 filter（R9.3）之后，其他业务 filter 之前</li>
 *   <li>filter URL pattern：{@code /admin/*}（公开端点由 filter 内部 {@code PUBLIC_ENDPOINTS} 放行）</li>
 * </ul>
 */
@Configuration
public class AdminTokenConfiguration {

    /**
     * 装配 admin token 工具 bean。
     *
     * @param properties console 配置属性
     * @param objectMapper Jackson 序列化器
     * @return AdminTokenSupport 实现
     */
    @Bean
    AdminTokenSupport adminTokenSupport(ConsoleServiceProperties properties, ObjectMapper objectMapper) {
        return new RsaAdminTokenSupport(properties, objectMapper);
    }

    /**
     * 注册管理端鉴权 filter。
     *
     * <p>filter order 设为 200，预留 100（IP 白名单）作为前置 filter 顺位。
     *
     * @param tokenSupport admin token 工具
     * @param blacklistService Redis 黑名单服务
     * @param objectMapper Jackson 序列化器
     * @return filter 注册 bean
     */
    @Bean
    FilterRegistrationBean<AdminAuthenticationFilter> adminAuthenticationFilterRegistration(
            AdminTokenSupport tokenSupport,
            AdminTokenBlacklistService blacklistService,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<AdminAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AdminAuthenticationFilter(tokenSupport, blacklistService, objectMapper));
        registration.addUrlPatterns("/admin/*");
        registration.setName("adminAuthenticationFilter");
        registration.setOrder(200);
        return registration;
    }
}
