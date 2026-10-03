package com.falconx.gateway.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.gateway.config.GatewaySecurityProperties;
import com.falconx.infrastructure.security.RsaPemSupport;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * gateway JWT 校验器。
 *
 * <p>该组件负责使用 `identity-service` 对应的开发态 RSA 公钥验证 Access Token，
 * 并解析出 gateway 后续透传给下游的用户身份信息。
 */
@Component
public class GatewayJwtVerifier {

    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final String ACCESS_TOKEN_BLACKLIST_KEY_PREFIX = "falconx:auth:token:blacklist:";
    /**
     * STAGE-12 安全：admin token 黑名单 key 前缀。与 console-service AdminTokenBlacklistService
     * 共享同一 Redis namespace `falconx:admin:token:blacklist:{jti}`。
     * console 端 logout 时写入这个 key（TTL = 剩余 token TTL），gateway 验签后查询。
     */
    private static final String ADMIN_TOKEN_BLACKLIST_KEY_PREFIX = "falconx:admin:token:blacklist:";

    private final ObjectMapper objectMapper;
    private final ReactiveStringRedisTemplate reactiveStringRedisTemplate;
    private final PublicKey publicKey;
    /**
     * STAGE-2-REALTIME-DATA Phase 2：console-service 公钥；
     * null 表示未配置（dev 占位密钥未注入），admin WS 鉴权将永远失败。
     */
    private final PublicKey consolePublicKey;

    public GatewayJwtVerifier(ObjectMapper objectMapper,
                              ReactiveStringRedisTemplate reactiveStringRedisTemplate,
                              GatewaySecurityProperties properties) {
        this.objectMapper = objectMapper;
        this.reactiveStringRedisTemplate = reactiveStringRedisTemplate;
        this.publicKey = RsaPemSupport.parsePublicKey(properties.getPublicKeyPem());
        this.consolePublicKey = properties.getConsolePublicKeyPem() == null || properties.getConsolePublicKeyPem().isBlank()
                ? null
                : RsaPemSupport.parsePublicKey(properties.getConsolePublicKeyPem());
    }

    /**
     * 验证并解析 Access Token。
     *
     * @param token JWT 文本
     * @return 解析后的最小主体
     */
    public Mono<GatewayAuthenticatedPrincipal> verifyAccessToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalStateException("Invalid token structure");
            }
            String signingInput = parts[0] + "." + parts[1];
            if (!verifySignature(signingInput, parts[2])) {
                throw new IllegalStateException("Invalid token signature");
            }

            JsonNode claims = objectMapper.readTree(BASE64_URL_DECODER.decode(parts[1]));
            if (!"access".equals(claims.path("typ").asText())) {
                throw new IllegalStateException("Token type is not access");
            }
            if (claims.path("exp").asLong(0) <= OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond()) {
                throw new IllegalStateException("Token expired");
            }

            String userId = claims.path("sub").asText(null);
            String uid = claims.path("uid").asText(null);
            String status = claims.path("status").asText(null);
            String groupCode = claims.path("groupCode").asText("default");
            String jti = claims.path("jti").asText(null);
            if (userId == null || uid == null || status == null || jti == null) {
                throw new IllegalStateException("Token payload missing required claims");
            }
            GatewayAuthenticatedPrincipal principal = new GatewayAuthenticatedPrincipal(
                    userId,
                    uid,
                    status,
                    normalizeGroupCode(groupCode),
                    jti
            );
            return reactiveStringRedisTemplate.hasKey(accessTokenBlacklistKey(jti))
                    .flatMap(blacklisted -> {
                        if (Boolean.TRUE.equals(blacklisted)) {
                            return Mono.error(new IllegalStateException("Access token blacklisted"));
                        }
                        return Mono.just(principal);
                    });
        } catch (Exception exception) {
            return Mono.error(new IllegalStateException("Invalid access token", exception));
        }
    }

    /**
     * STAGE-2-REALTIME-DATA Phase 2 + STAGE-12 安全加固：验证 console-service 签发的 admin access token。
     *
     * <p>用于 WebSocket 握手分流 + 加入黑名单检查（STAGE-12 新增 jti 撤销链路）。
     * 与 user token 一样：admin logout / 强制吊销时，console-service 把 jti 写入
     * Redis key `falconx:admin:token:blacklist:{jti}`，gateway 验签通过后查询此 key
     * 是否存在，存在则拒绝。
     *
     * @return adminUserId 字符串（{@code sub} claim）
     */
    public Mono<String> verifyAdminAccessToken(String token) {
        if (consolePublicKey == null) {
            return Mono.error(new IllegalStateException("Console public key not configured"));
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalStateException("Invalid admin token structure");
            }
            String signingInput = parts[0] + "." + parts[1];
            if (!verifySignature(signingInput, parts[2], consolePublicKey)) {
                throw new IllegalStateException("Invalid admin token signature");
            }
            JsonNode claims = objectMapper.readTree(BASE64_URL_DECODER.decode(parts[1]));
            if (!"falconx-console-service".equals(claims.path("iss").asText())) {
                throw new IllegalStateException("Admin token issuer mismatch");
            }
            if (!"access".equals(claims.path("typ").asText())) {
                throw new IllegalStateException("Admin token type is not access");
            }
            if (claims.path("exp").asLong(0) <= OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond()) {
                throw new IllegalStateException("Admin token expired");
            }
            String adminUserId = claims.path("sub").asText(null);
            if (adminUserId == null || adminUserId.isBlank()) {
                throw new IllegalStateException("Admin token missing sub");
            }
            String jti = claims.path("jti").asText(null);
            // jti 兼容性：如果旧 token 没 jti（升级前签发的），保留旧行为不检查；
            // 升级后所有新 token 都有 jti，此时严格检查黑名单。
            if (jti == null || jti.isBlank()) {
                return Mono.just(adminUserId);
            }
            return reactiveStringRedisTemplate.hasKey(adminTokenBlacklistKey(jti))
                    .flatMap(blacklisted -> {
                        if (Boolean.TRUE.equals(blacklisted)) {
                            return Mono.error(new IllegalStateException("Admin access token blacklisted"));
                        }
                        return Mono.just(adminUserId);
                    });
        } catch (Exception exception) {
            return Mono.error(new IllegalStateException("Invalid admin access token", exception));
        }
    }

    /**
     * STAGE-2-REALTIME-DATA Phase 2：根据 token payload 内 {@code iss} 字段辨别 user/admin。
     * 不验签，只用于路由判断。
     *
     * @return true 表示是 admin token（iss=falconx-console-service）
     */
    public boolean looksLikeAdminToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            JsonNode claims = objectMapper.readTree(BASE64_URL_DECODER.decode(parts[1]));
            return "falconx-console-service".equals(claims.path("iss").asText());
        } catch (Exception exception) {
            return false;
        }
    }

    private String normalizeGroupCode(String groupCode) {
        return groupCode == null || groupCode.isBlank() ? "default" : groupCode.trim();
    }

    private boolean verifySignature(String signingInput, String encodedSignature) {
        return verifySignature(signingInput, encodedSignature, publicKey);
    }

    private boolean verifySignature(String signingInput, String encodedSignature, PublicKey key) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.verify(BASE64_URL_DECODER.decode(encodedSignature));
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    private String accessTokenBlacklistKey(String jti) {
        return ACCESS_TOKEN_BLACKLIST_KEY_PREFIX + jti;
    }

    private String adminTokenBlacklistKey(String jti) {
        return ADMIN_TOKEN_BLACKLIST_KEY_PREFIX + jti;
    }
}
