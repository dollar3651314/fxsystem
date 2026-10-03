package com.falconx.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * identity-service 配置属性。
 *
 * <p>该配置类用于冻结 Stage 3A 身份服务的最小安全与认证参数，
 * 包括 token 签发者、访问令牌时长、刷新令牌时长以及密码哈希强度。
 * 当前阶段默认使用进程内临时 RSA 密钥对，仅用于本地骨架验证。
 */
@ConfigurationProperties(prefix = "falconx.identity")
public class IdentityServiceProperties {

    private final Token token = new Token();
    private final Password password = new Password();
    private final Security security = new Security();
    private final KeyPair keyPair = new KeyPair();
    private final Kafka kafka = new Kafka();
    private final InternalApi internalApi = new InternalApi();

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

    public Kafka getKafka() {
        return kafka;
    }

    public InternalApi getInternalApi() {
        return internalApi;
    }

    /**
     * Token 签发配置。
     */
    public static class Token {
        private String issuer = "falconx-identity-service";
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofDays(3);

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
     * 密码哈希配置。
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
     * 登录与注册防护配置。
     */
    public static class Security {
        private int loginFailureLimit = 5;
        private Duration loginLockDuration = Duration.ofMinutes(15);
        private int registerLimit = 5;
        private Duration registerWindow = Duration.ofHours(1);

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

        public int getRegisterLimit() {
            return registerLimit;
        }

        public void setRegisterLimit(int registerLimit) {
            this.registerLimit = registerLimit;
        }

        public Duration getRegisterWindow() {
            return registerWindow;
        }

        public void setRegisterWindow(Duration registerWindow) {
            this.registerWindow = registerWindow;
        }
    }

    /**
     * RSA 密钥配置。
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
     * identity-service Kafka 消费配置。
     */
    public static class Kafka {
        private String depositCreditedTopic = "falconx.trading.deposit.credited";
        private String consumerGroupId = "falconx.identity-service.deposit-credited-consumer-group";
        // STAGE-5-WALLET-PROVISION：注册成功后向 wallet-service 发布的事件 topic
        private String userRegisteredTopic = "falconx.identity.user.registered";
        // STAGE-6-KYC Phase 4：KYC 审核完成后向 trading-core 发布的事件 topic
        private String kycReviewedTopic = "falconx.identity.kyc.reviewed";

        public String getDepositCreditedTopic() {
            return depositCreditedTopic;
        }

        public void setDepositCreditedTopic(String depositCreditedTopic) {
            this.depositCreditedTopic = depositCreditedTopic;
        }

        public String getConsumerGroupId() {
            return consumerGroupId;
        }

        public void setConsumerGroupId(String consumerGroupId) {
            this.consumerGroupId = consumerGroupId;
        }

        public String getUserRegisteredTopic() {
            return userRegisteredTopic;
        }

        public void setUserRegisteredTopic(String userRegisteredTopic) {
            this.userRegisteredTopic = userRegisteredTopic;
        }

        public String getKycReviewedTopic() {
            return kycReviewedTopic;
        }

        public void setKycReviewedTopic(String kycReviewedTopic) {
            this.kycReviewedTopic = kycReviewedTopic;
        }
    }

    /**
     * 跨服务 internal RPC 鉴权配置。
     *
     * <p>由 {@code STAGE-2-CUSTOMER} 引入：管理后台 console-service 通过 gateway 调用 identity 的
     * {@code /internal/v1/identity/**} 时携带 {@code X-Internal-Token}，identity filter 校验。
     *
     * <p>详细机制见 {@code docs/architecture/管理端架构.md} §4.1。
     */
    public static class InternalApi {
        private String token;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }
}
