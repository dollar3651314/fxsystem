package com.falconx.identity.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.domain.enums.UserStatus;
import com.falconx.infrastructure.security.RsaPemSupport;
import com.falconx.identity.config.IdentityServiceProperties;
import com.falconx.identity.entity.IdentityUser;
import com.falconx.identity.entity.RefreshTokenSession;
import com.falconx.identity.error.IdentityBusinessException;
import com.falconx.identity.error.IdentityErrorCode;
import com.falconx.identity.repository.IdentityUserRepository;
import com.falconx.identity.repository.RefreshTokenSessionRepository;
import com.falconx.identity.service.IdentityTokenService;
import com.falconx.identity.service.model.AuthTokenBundle;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 基于 JDK RSA 的身份令牌服务实现。
 *
 * <p>该实现使用进程内临时 RSA 密钥对签发 RS256 JWT，
 * 用于在 Stage 3A 骨架阶段冻结 token 结构、刷新轮换和一次性使用语义。
 * 后续接入正式密钥管理时，应替换密钥来源而不是改变该服务的对外方法。
 */
public class RsaIdentityTokenService implements IdentityTokenService {

    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final IdentityServiceProperties properties;
    private final IdentityUserRepository identityUserRepository;
    private final RefreshTokenSessionRepository refreshTokenSessionRepository;
    private final ObjectMapper objectMapper;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public RsaIdentityTokenService(IdentityServiceProperties properties,
                                   IdentityUserRepository identityUserRepository,
                                   RefreshTokenSessionRepository refreshTokenSessionRepository,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.identityUserRepository = identityUserRepository;
        this.refreshTokenSessionRepository = refreshTokenSessionRepository;
        this.objectMapper = objectMapper;
        this.privateKey = RsaPemSupport.parsePrivateKey(properties.getKeyPair().getPrivateKeyPem());
        this.publicKey = RsaPemSupport.parsePublicKey(properties.getKeyPair().getPublicKeyPem());
    }

    @Override
    public AuthTokenBundle issueTokens(IdentityUser user) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime accessExpiresAt = now.plus(properties.getToken().getAccessTokenTtl());
        OffsetDateTime refreshExpiresAt = now.plus(properties.getToken().getRefreshTokenTtl());
        String accessJti = randomJti();
        String refreshJti = randomJti();

        String accessToken = buildToken(accessClaims(user, accessJti, now, accessExpiresAt));
        String refreshToken = buildToken(refreshClaims(user.id(), refreshJti, now, refreshExpiresAt));

        refreshTokenSessionRepository.save(new RefreshTokenSession(
                refreshJti,
                user.id(),
                refreshExpiresAt,
                false,
                now
        ));

        return new AuthTokenBundle(
                accessToken,
                refreshToken,
                properties.getToken().getAccessTokenTtl().toSeconds(),
                properties.getToken().getRefreshTokenTtl().toSeconds(),
                user.status().name(),
                user.emailVerified()
        );
    }

    @Override
    public AuthTokenBundle refresh(String refreshToken) {
        JsonNode claims = parseAndVerifyToken(refreshToken, "refresh", IdentityErrorCode.REFRESH_TOKEN_INVALID);

        String jti = claims.path("jti").asText(null);
        long userId = claims.path("sub").asLong(-1);
        long expiresAtEpoch = claims.path("exp").asLong(0);
        if (jti == null || userId <= 0 || expiresAtEpoch <= 0) {
            throw new IdentityBusinessException(IdentityErrorCode.REFRESH_TOKEN_INVALID);
        }

        RefreshTokenSession session = refreshTokenSessionRepository.findByJtiForUpdate(jti)
                .orElseThrow(() -> new IdentityBusinessException(IdentityErrorCode.REFRESH_TOKEN_INVALID));
        if (session.used() || session.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new IdentityBusinessException(IdentityErrorCode.REFRESH_TOKEN_INVALID);
        }

        IdentityUser user = identityUserRepository.findById(userId)
                .orElseThrow(() -> new IdentityBusinessException(IdentityErrorCode.REFRESH_TOKEN_INVALID));
        if (user.status() == UserStatus.BANNED) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_BANNED);
        }

        if (!refreshTokenSessionRepository.markUsedIfUnused(jti)) {
            throw new IdentityBusinessException(IdentityErrorCode.REFRESH_TOKEN_INVALID);
        }
        return issueTokens(user);
    }

    @Override
    public ValidatedAccessToken parseAndValidateAccessToken(String accessToken) {
        JsonNode claims = parseAndVerifyToken(accessToken, "access", IdentityErrorCode.UNAUTHORIZED);
        String userId = claims.path("sub").asText(null);
        String jti = claims.path("jti").asText(null);
        long expiresAtEpoch = claims.path("exp").asLong(0);
        if (userId == null || jti == null || expiresAtEpoch <= 0) {
            throw new IdentityBusinessException(IdentityErrorCode.UNAUTHORIZED);
        }
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(expiresAtEpoch), ZoneOffset.UTC);
        Duration remainingTtl = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), expiresAt);
        if (remainingTtl.isNegative() || remainingTtl.isZero()) {
            throw new IdentityBusinessException(IdentityErrorCode.UNAUTHORIZED);
        }
        return new ValidatedAccessToken(userId, jti, expiresAt, remainingTtl);
    }

    private Map<String, Object> accessClaims(IdentityUser user,
                                             String jti,
                                             OffsetDateTime issuedAt,
                                             OffsetDateTime expiresAt) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getToken().getIssuer());
        claims.put("typ", "access");
        claims.put("sub", String.valueOf(user.id()));
        claims.put("uid", user.uid());
        claims.put("email", maskEmail(user.email()));
        claims.put("status", user.status().name());
        claims.put("groupCode", normalizeGroupCode(user.groupCode()));
        claims.put("jti", jti);
        claims.put("iat", issuedAt.toEpochSecond());
        claims.put("exp", expiresAt.toEpochSecond());
        return claims;
    }

    private Map<String, Object> refreshClaims(Long userId,
                                              String jti,
                                              OffsetDateTime issuedAt,
                                              OffsetDateTime expiresAt) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getToken().getIssuer());
        claims.put("typ", "refresh");
        claims.put("sub", String.valueOf(userId));
        claims.put("jti", jti);
        claims.put("iat", issuedAt.toEpochSecond());
        claims.put("exp", expiresAt.toEpochSecond());
        return claims;
    }

    private String normalizeGroupCode(String groupCode) {
        return groupCode == null || groupCode.isBlank() ? "default" : groupCode.trim();
    }

    private String buildToken(Map<String, Object> claims) {
        try {
            String header = base64UrlJson(Map.of("alg", "RS256", "typ", "JWT"));
            String payload = base64UrlJson(claims);
            String signingInput = header + "." + payload;
            String signature = sign(signingInput);
            return signingInput + "." + signature;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize JWT payload", exception);
        }
    }

    private JsonNode parseAndVerifyToken(String token, String expectedType, IdentityErrorCode errorCode) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IdentityBusinessException(errorCode);
        }

        String signingInput = parts[0] + "." + parts[1];
        if (!verifySignature(signingInput, parts[2])) {
            throw new IdentityBusinessException(errorCode);
        }

        try {
            JsonNode claims = objectMapper.readTree(BASE64_URL_DECODER.decode(parts[1]));
            long expiresAtEpoch = claims.path("exp").asLong(0);
            if (expiresAtEpoch <= OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond()) {
                throw new IdentityBusinessException(errorCode);
            }
            if (!properties.getToken().getIssuer().equals(claims.path("iss").asText())) {
                throw new IdentityBusinessException(errorCode);
            }
            if (!expectedType.equals(claims.path("typ").asText())) {
                throw new IdentityBusinessException(errorCode);
            }
            return claims;
        } catch (JacksonException exception) {
            throw new IdentityBusinessException(errorCode);
        }
    }

    private String base64UrlJson(Map<String, Object> content) throws JacksonException {
        return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(content));
    }

    private String sign(String signingInput) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return BASE64_URL_ENCODER.encodeToString(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign JWT", exception);
        }
    }

    private boolean verifySignature(String signingInput, String encodedSignature) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.verify(BASE64_URL_DECODER.decode(encodedSignature));
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "***" + email.substring(Math.max(atIndex, 0));
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }

    private String randomJti() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
