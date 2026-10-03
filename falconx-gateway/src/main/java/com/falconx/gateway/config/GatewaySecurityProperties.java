package com.falconx.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway 安全配置属性。
 *
 * <p>当前阶段该配置类只承载 JWT 验证所需的开发态 RSA 公钥。
 * 生产环境应改为外部密钥管理或环境变量注入。
 */
@ConfigurationProperties(prefix = "falconx.gateway.security")
public class GatewaySecurityProperties {

    private String publicKeyPem;
    /**
     * STAGE-2-REALTIME-DATA Phase 2：console-service 公钥（admin token 验签）。
     *
     * <p>用于 admin WebSocket 握手时辨认 console issuer 的 token；与 publicKeyPem 互不影响。
     * dev 用 application-dev.yml 内联占位密钥；prod 通过 {@code FALCONX_GATEWAY_CONSOLE_PUBLIC_KEY_PEM} 注入。
     */
    private String consolePublicKeyPem;
    private String clientIpHeader = "X-Client-Ip";
    /**
     * STAGE-12 安全加固：可信代理 IP 白名单。
     *
     * <p>resolveClientIp() 只在 remoteAddress 命中此白名单时才信任 {@code X-Client-Ip} header。
     * 否则只用 remoteAddress —— 防止攻击者直接发请求并伪造 X-Client-Ip 绕过 IP 限流。
     *
     * <p>dev 环境默认包含 localhost / docker bridge。生产应在 nginx 后改成 LB IP 段。
     */
    private List<String> trustedProxyIps = List.of(
            "127.0.0.1", "::1", "0:0:0:0:0:0:0:1",
            "172.17.0.1", "172.18.0.1", "172.19.0.1", "172.20.0.1");
    private int authRequestRateLimitPerMinute = 20;
    private int tradingRequestRateLimitPerSecond = 10;
    private int globalRequestRateLimitPerMinute = 200;
    private int marketWebSocketConnectionLimit = 5;
    private int connectTimeoutMillis = 1000;
    private long responseTimeoutMillis = 5000;
    /**
     * 内部 API token，gateway 路由 {@code /internal/v1/**} 时通过 {@code X-Internal-Token} header 注入。
     *
     * <p>由 console-service 调用业务服务的 internal RPC 使用；business-service 通过 filter 校验。
     * 详细机制见 [`管理端架构`](docs/architecture/管理端架构.md) §4.1。
     *
     * <p>dev / test 默认 {@code falconx-internal-dev-token}；prod / staging 通过环境变量
     * {@code FALCONX_INTERNAL_API_TOKEN} 注入，缺失时 gateway 启动失败 fail-closed。
     */
    private String internalApiToken;

    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    public void setPublicKeyPem(String publicKeyPem) {
        this.publicKeyPem = publicKeyPem;
    }

    public String getConsolePublicKeyPem() {
        return consolePublicKeyPem;
    }

    public void setConsolePublicKeyPem(String consolePublicKeyPem) {
        this.consolePublicKeyPem = consolePublicKeyPem;
    }

    public String getClientIpHeader() {
        return clientIpHeader;
    }

    public void setClientIpHeader(String clientIpHeader) {
        this.clientIpHeader = clientIpHeader;
    }

    public List<String> getTrustedProxyIps() {
        return trustedProxyIps;
    }

    public void setTrustedProxyIps(List<String> trustedProxyIps) {
        this.trustedProxyIps = trustedProxyIps;
    }

    public int getAuthRequestRateLimitPerMinute() {
        return authRequestRateLimitPerMinute;
    }

    public void setAuthRequestRateLimitPerMinute(int authRequestRateLimitPerMinute) {
        this.authRequestRateLimitPerMinute = authRequestRateLimitPerMinute;
    }

    public int getTradingRequestRateLimitPerSecond() {
        return tradingRequestRateLimitPerSecond;
    }

    public void setTradingRequestRateLimitPerSecond(int tradingRequestRateLimitPerSecond) {
        this.tradingRequestRateLimitPerSecond = tradingRequestRateLimitPerSecond;
    }

    public int getGlobalRequestRateLimitPerMinute() {
        return globalRequestRateLimitPerMinute;
    }

    public void setGlobalRequestRateLimitPerMinute(int globalRequestRateLimitPerMinute) {
        this.globalRequestRateLimitPerMinute = globalRequestRateLimitPerMinute;
    }

    public int getMarketWebSocketConnectionLimit() {
        return marketWebSocketConnectionLimit;
    }

    public void setMarketWebSocketConnectionLimit(int marketWebSocketConnectionLimit) {
        this.marketWebSocketConnectionLimit = marketWebSocketConnectionLimit;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public long getResponseTimeoutMillis() {
        return responseTimeoutMillis;
    }

    public void setResponseTimeoutMillis(long responseTimeoutMillis) {
        this.responseTimeoutMillis = responseTimeoutMillis;
    }

    public String getInternalApiToken() {
        return internalApiToken;
    }

    public void setInternalApiToken(String internalApiToken) {
        this.internalApiToken = internalApiToken;
    }
}
