package com.falconx.gateway.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.springframework.cloud.gateway.support.RouteMetadataUtils.CONNECT_TIMEOUT_ATTR;
import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gateway 基础配置入口。
 *
 * <p>Stage 4 通过该配置类完成三件事情：
 *
 * <ul>
 *   <li>启用路由和安全属性绑定</li>
 *   <li>建立北向入口到各 owner 服务的静态路由</li>
 *   <li>保持 gateway 只负责转发，不引入业务编排</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties({GatewayRouteProperties.class, GatewaySecurityProperties.class})
public class GatewayConfiguration {

    /**
     * 提供 gateway 统一使用的 ObjectMapper。
     *
     * <p>当前 Stage 4 的 gateway 同时承担两类 JSON 处理职责：
     *
     * <ul>
     *   <li>校验 Access Token 时解析 JWT payload</li>
     *   <li>鉴权失败时输出统一 JSON 错误响应</li>
     * </ul>
     *
     * <p>在当前 `Spring Cloud Gateway + Spring Boot 4` 组合下，
     * 为避免不同自动配置路径下 `ObjectMapper` 装配不稳定，
     * 这里显式声明一个最小公共 Bean，后续若补统一 JSON 配置，
     * 也应继续从该 Bean 扩展。
     *
     * @return gateway 统一 JSON 序列化器
     */
    @Bean
    public ObjectMapper gatewayObjectMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * 建立 FalconX 一期最小路由表。
     *
     * @param builder Spring Cloud Gateway 路由构建器
     * @param properties 路由配置
     * @return 路由定位器
     */
    @Bean
    public RouteLocator falconxRouteLocator(RouteLocatorBuilder builder,
                                            GatewayRouteProperties properties,
                                            GatewaySecurityProperties securityProperties) {
        return builder.routes()
                .route("identity-route", route -> route.path("/api/v1/auth/**")
                        .filters(filters -> filters.circuitBreaker(config ->
                                config.setFallbackUri("forward:/internal/gateway/fallback/identity-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getIdentityBaseUrl().toString()))
                // STAGE-7-WITHDRAW: /api/v1/me/withdraw/** 在 trading-core，
                // 必须放在通用 /api/v1/me/** → identity 路由之前，否则会被错误转发到 identity 致 500
                // （Spring Cloud Gateway 路由按声明顺序匹配，first match wins）
                .route("trading-me-withdraw-route", route -> route.path("/api/v1/me/withdraw/**")
                        .filters(filters -> filters.circuitBreaker(config ->
                                config.setFallbackUri("forward:/internal/gateway/fallback/trading-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getTradingBaseUrl().toString()))
                // STAGE-14D1: /api/v1/me/margin-mode（margin mode 切换）+ /api/v1/me/positions/**
                // （supplement-margin）在 trading-core，同 withdraw 必须放在通用 /api/v1/me/** → identity
                // 路由之前，否则被错误转发到 identity 致 500（D1 只用 controller IT 验证，漏了 gateway 路由）。
                .route("trading-me-margin-mode-route", route -> route.path("/api/v1/me/margin-mode/**")
                        .filters(filters -> filters.circuitBreaker(config ->
                                config.setFallbackUri("forward:/internal/gateway/fallback/trading-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getTradingBaseUrl().toString()))
                .route("trading-me-positions-route", route -> route.path("/api/v1/me/positions/**")
                        .filters(filters -> filters.circuitBreaker(config ->
                                config.setFallbackUri("forward:/internal/gateway/fallback/trading-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getTradingBaseUrl().toString()))
                // STAGE-1B-USER-PROFILE: /api/v1/me/profile + /api/v1/me/kyc 走 identity（与 auth 共路由器）
                .route("identity-me-route", route -> route.path("/api/v1/me/**")
                        .filters(filters -> filters.circuitBreaker(config ->
                                config.setFallbackUri("forward:/internal/gateway/fallback/identity-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getIdentityBaseUrl().toString()))
                .route("market-route", route -> route.path("/api/v1/market/**")
                        .filters(filters -> filters.circuitBreaker(config ->
                                config.setFallbackUri("forward:/internal/gateway/fallback/market-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getMarketBaseUrl().toString()))
                .route("trading-route", route -> route.path("/api/v1/trading/**")
                        .filters(filters -> filters.circuitBreaker(config ->
                                config.setFallbackUri("forward:/internal/gateway/fallback/trading-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getTradingBaseUrl().toString()))
                .route("wallet-route", route -> route.path("/api/v1/wallet/**")
                        .filters(filters -> filters.circuitBreaker(config ->
                                config.setFallbackUri("forward:/internal/gateway/fallback/wallet-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getWalletBaseUrl().toString()))
                // STAGE-1-CONSOLE-FOUNDATION：管理后台路径前缀路由（按用户决策 D2）
                // 复用 C 端域名，通过 path /admin/** 路由到 console-service:18085；IP 白名单单独由 console-service 应用层校验。
                .route("console-route", route -> route.path("/admin/**")
                        .filters(filters -> filters.circuitBreaker(config ->
                                config.setFallbackUri("forward:/internal/gateway/fallback/console-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getConsoleBaseUrl().toString()))
                // STAGE-2-CUSTOMER：跨服务 internal RPC 路由（gateway 注入 X-Internal-Token）
                // 仅 console-service 通过 gateway 调用 /internal/v1/{service}/**；business-service filter 校验 token
                // 详细机制见 docs/architecture/管理端架构.md §4.1
                .route("internal-identity-route", route -> route.path("/internal/v1/identity/**")
                        .filters(filters -> filters.setRequestHeader("X-Internal-Token",
                                        securityProperties.getInternalApiToken())
                                .circuitBreaker(config ->
                                        config.setFallbackUri("forward:/internal/gateway/fallback/identity-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getIdentityBaseUrl().toString()))
                .route("internal-market-route", route -> route.path("/internal/v1/market/**")
                        .filters(filters -> filters.setRequestHeader("X-Internal-Token",
                                        securityProperties.getInternalApiToken())
                                .circuitBreaker(config ->
                                        config.setFallbackUri("forward:/internal/gateway/fallback/market-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getMarketBaseUrl().toString()))
                .route("internal-trading-route", route -> route.path("/internal/v1/trading/**")
                        .filters(filters -> filters.setRequestHeader("X-Internal-Token",
                                        securityProperties.getInternalApiToken())
                                .circuitBreaker(config ->
                                        config.setFallbackUri("forward:/internal/gateway/fallback/trading-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getTradingBaseUrl().toString()))
                .route("internal-wallet-route", route -> route.path("/internal/v1/wallet/**")
                        .filters(filters -> filters.setRequestHeader("X-Internal-Token",
                                        securityProperties.getInternalApiToken())
                                .circuitBreaker(config ->
                                        config.setFallbackUri("forward:/internal/gateway/fallback/wallet-route")))
                        .metadata(CONNECT_TIMEOUT_ATTR, securityProperties.getConnectTimeoutMillis())
                        .metadata(RESPONSE_TIMEOUT_ATTR, securityProperties.getResponseTimeoutMillis())
                        .uri(properties.getWalletBaseUrl().toString()))
                .build();
    }
}
