package com.falconx.gateway.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway 下游路由配置。
 *
 * <p>该配置类用于固定 Stage 4 统一入口路由到各 owner 服务的基础地址。
 * 当前阶段使用项目内配置，便于本地 IDEA 直接联调；生产环境后续再切到外部化配置。
 */
@ConfigurationProperties(prefix = "falconx.gateway.routes")
public class GatewayRouteProperties {

    private URI identityBaseUrl = URI.create("http://localhost:18081");
    private URI marketBaseUrl = URI.create("http://localhost:18082");
    private URI tradingBaseUrl = URI.create("http://localhost:18083");
    private URI walletBaseUrl = URI.create("http://localhost:18084");
    private URI consoleBaseUrl = URI.create("http://localhost:18085");

    public URI getIdentityBaseUrl() {
        return identityBaseUrl;
    }

    public void setIdentityBaseUrl(URI identityBaseUrl) {
        this.identityBaseUrl = identityBaseUrl;
    }

    public URI getMarketBaseUrl() {
        return marketBaseUrl;
    }

    public void setMarketBaseUrl(URI marketBaseUrl) {
        this.marketBaseUrl = marketBaseUrl;
    }

    public URI getTradingBaseUrl() {
        return tradingBaseUrl;
    }

    public void setTradingBaseUrl(URI tradingBaseUrl) {
        this.tradingBaseUrl = tradingBaseUrl;
    }

    public URI getWalletBaseUrl() {
        return walletBaseUrl;
    }

    public void setWalletBaseUrl(URI walletBaseUrl) {
        this.walletBaseUrl = walletBaseUrl;
    }

    public URI getConsoleBaseUrl() {
        return consoleBaseUrl;
    }

    public void setConsoleBaseUrl(URI consoleBaseUrl) {
        this.consoleBaseUrl = consoleBaseUrl;
    }
}
