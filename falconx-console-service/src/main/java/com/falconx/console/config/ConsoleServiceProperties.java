package com.falconx.console.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * console-service 配置属性。
 *
 * <p>对应 {@code application.yml} 的 {@code falconx.console.*} 配置树，
 * 覆盖 admin token 签发、密码哈希、登录防护、IP 白名单、独立 RSA 密钥与默认超管初始化参数。
 *
 * <p>与 {@code falconx-identity-service} 的 {@code IdentityServiceProperties} 完全独立：
 *
 * <ul>
 *   <li>独立 RSA 私钥（{@code key-pair.private-key-pem}），不复用 C 端 identity 私钥</li>
 *   <li>独立 issuer（默认 {@code falconx-console-service}）</li>
 *   <li>更短的 access / refresh TTL（30m / 8h，比 C 端短）</li>
 *   <li>更高的 BCrypt strength 默认值（12，可在 test profile 调低到 4）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "falconx.console")
public class ConsoleServiceProperties {

    private final Token token = new Token();
    private final Password password = new Password();
    private final Security security = new Security();
    private final KeyPair keyPair = new KeyPair();
    private final InitialSuperAdmin initialSuperAdmin = new InitialSuperAdmin();
    private final InternalRpc internalRpc = new InternalRpc();
    private final BalanceAdjust balanceAdjust = new BalanceAdjust();

    public Token getToken() {
        return token;
    }

    public Password getPassword() {
        return password;
    }

    public Security getSecurity() {
        return security;
    }

    public KeyPair getKeyPair() {
        return keyPair;
    }

    public InitialSuperAdmin getInitialSuperAdmin() {
        return initialSuperAdmin;
    }

    public InternalRpc getInternalRpc() {
        return internalRpc;
    }

    public BalanceAdjust getBalanceAdjust() {
        return balanceAdjust;
    }

    /**
     * Admin token 签发配置（与 C 端 identity 完全隔离）。
     */
    public static class Token {
        private String issuer = "falconx-console-service";
        private Duration accessTokenTtl = Duration.ofMinutes(30);
        private Duration refreshTokenTtl = Duration.ofHours(8);

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }
    }

    /**
     * 管理员密码哈希配置。
     */
    public static class Password {
        private int bcryptStrength = 12;

        public int getBcryptStrength() {
            return bcryptStrength;
        }

        public void setBcryptStrength(int bcryptStrength) {
            this.bcryptStrength = bcryptStrength;
        }
    }

    /**
     * 登录防护与 IP 白名单配置。
     */
    public static class Security {
        private int loginFailureLimit = 5;
        private Duration loginLockDuration = Duration.ofMinutes(30);
        private boolean ipWhitelistEnabled = true;
        private List<String> ipWhitelist = new ArrayList<>();

        public int getLoginFailureLimit() {
            return loginFailureLimit;
        }

        public void setLoginFailureLimit(int loginFailureLimit) {
            this.loginFailureLimit = loginFailureLimit;
        }

        public Duration getLoginLockDuration() {
            return loginLockDuration;
        }

        public void setLoginLockDuration(Duration loginLockDuration) {
            this.loginLockDuration = loginLockDuration;
        }

        public boolean isIpWhitelistEnabled() {
            return ipWhitelistEnabled;
        }

        public void setIpWhitelistEnabled(boolean ipWhitelistEnabled) {
            this.ipWhitelistEnabled = ipWhitelistEnabled;
        }

        public List<String> getIpWhitelist() {
            return ipWhitelist;
        }

        public void setIpWhitelist(List<String> ipWhitelist) {
            this.ipWhitelist = ipWhitelist;
        }
    }

    /**
     * 独立 RSA 密钥（与 C 端 identity 隔离）。
     *
     * <p>生产环境通过 {@code FALCONX_CONSOLE_PRIVATE_KEY_PEM} / {@code FALCONX_CONSOLE_PUBLIC_KEY_PEM}
     * 环境变量注入，禁止把生产密钥写入仓库。
     */
    public static class KeyPair {
        private String privateKeyPem;
        private String publicKeyPem;

        public String getPrivateKeyPem() {
            return privateKeyPem;
        }

        public void setPrivateKeyPem(String privateKeyPem) {
            this.privateKeyPem = privateKeyPem;
        }

        public String getPublicKeyPem() {
            return publicKeyPem;
        }

        public void setPublicKeyPem(String publicKeyPem) {
            this.publicKeyPem = publicKeyPem;
        }
    }

    /**
     * 默认超管启动初始化配置。
     *
     * <p>启动时若 {@code t_admin_user} 中不存在 SUPER_ADMIN 角色绑定的管理员，
     * console-service 自动 INSERT 默认超管账号，密码使用 {@code defaultPassword} 经 BCrypt 加密；
     * {@code mustChangePassword=1} 强制首次登录修改密码。
     */
    public static class InitialSuperAdmin {
        private String username = "superadmin";
        private String defaultPassword = "falconx-admin-init";
        private String realName = "系统超级管理员";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDefaultPassword() {
            return defaultPassword;
        }

        public void setDefaultPassword(String defaultPassword) {
            this.defaultPassword = defaultPassword;
        }

        public String getRealName() {
            return realName;
        }

        public void setRealName(String realName) {
            this.realName = realName;
        }
    }

    /**
     * STAGE-2-CUSTOMER：调用 business-service internal RPC 配置。
     *
     * <p>console 通过 gateway 发起调用：{@code POST {gatewayBaseUrl}/internal/v1/{service}/...}
     * gateway 自动注入 {@code X-Internal-Token}（gateway 配置）。console 仅需注入
     * {@code X-Admin-User-Id}（来自 {@code AdminSecurityContextHolder.current().adminUserId}）和
     * {@code X-Trace-Id}（MDC）。
     */
    public static class InternalRpc {
        private String gatewayBaseUrl = "http://localhost:18080";
        private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(2);
        private java.time.Duration readTimeout = java.time.Duration.ofSeconds(5);

        public String getGatewayBaseUrl() {
            return gatewayBaseUrl;
        }

        public void setGatewayBaseUrl(String gatewayBaseUrl) {
            this.gatewayBaseUrl = gatewayBaseUrl;
        }

        public java.time.Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(java.time.Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public java.time.Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(java.time.Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    /**
     * STAGE-2-CUSTOMER：调余额限额配置（单次 / 单日，按管理端架构 §4.4）。
     */
    public static class BalanceAdjust {
        private java.math.BigDecimal singleLimitUSD = new java.math.BigDecimal("100000");
        private java.math.BigDecimal dailyLimitUSD = new java.math.BigDecimal("1000000");

        public java.math.BigDecimal getSingleLimitUSD() {
            return singleLimitUSD;
        }

        public void setSingleLimitUSD(java.math.BigDecimal singleLimitUSD) {
            this.singleLimitUSD = singleLimitUSD;
        }

        public java.math.BigDecimal getDailyLimitUSD() {
            return dailyLimitUSD;
        }

        public void setDailyLimitUSD(java.math.BigDecimal dailyLimitUSD) {
            this.dailyLimitUSD = dailyLimitUSD;
        }
    }
}
