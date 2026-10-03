package com.falconx.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * STAGE-13 SystemConfigClient autoconfigure 属性。
 *
 * <p>各业务 service 在 application.yml 配置：
 * <pre>
 * falconx:
 *   system-config:
 *     enabled: true
 *     console-base-url: http://localhost:18085  # 拉取 config 的 console-service 地址
 *     internal-token: ${FALCONX_INTERNAL_API_TOKEN}
 * </pre>
 *
 * <p>{@code enabled=false} 时所有 getter 直接返回 defaultValue（适合单元测试 / dev 不依赖 config 中心）。
 */
@ConfigurationProperties(prefix = "falconx.system-config")
public class SystemConfigClientProperties {

    private boolean enabled = true;
    /**
     * console-service 暴露的 internal RPC 地址。dev 默认 localhost:18085；
     * 生产应通过 env 指向 service mesh 内部地址。
     */
    private String consoleBaseUrl = "http://localhost:18085";
    /** 内部 API token（同 GatewaySecurityProperties.internalApiToken）。 */
    private String internalToken;
    /** 启动时拉取超时。 */
    private long fetchTimeoutMillis = 3000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getConsoleBaseUrl() {
        return consoleBaseUrl;
    }

    public void setConsoleBaseUrl(String consoleBaseUrl) {
        this.consoleBaseUrl = consoleBaseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public long getFetchTimeoutMillis() {
        return fetchTimeoutMillis;
    }

    public void setFetchTimeoutMillis(long fetchTimeoutMillis) {
        this.fetchTimeoutMillis = fetchTimeoutMillis;
    }
}
