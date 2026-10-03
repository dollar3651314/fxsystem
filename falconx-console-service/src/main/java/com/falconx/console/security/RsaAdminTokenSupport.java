package com.falconx.console.security;

import com.falconx.console.config.ConsoleServiceProperties;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.infrastructure.security.RsaPemSupport;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 基于 JDK RSA 的管理端 token 签发 / 校验实现。
 *
 * <p>使用 {@link com.falconx.console.config.ConsoleServiceProperties.KeyPair} 中的独立 RSA 密钥对签发 RS256 JWT，
 * 与 {@code falconx-identity-service} 的 C 端 token 完全隔离。
 *
 * <p>JWT claims：
 * <ul>
 *   <li>{@code iss}：{@code falconx-console-service}</li>
 *   <li>{@code typ}：{@code access} 或 {@code refresh}</li>
 *   <li>{@code sub}：管理员 id（access）或管理员 id（refresh）</li>
 *   <li>{@code username}：仅 access</li>
 *   <li>{@code roles}：仅 access，角色 code 数组</li>
 *   <li>{@code mustChangePassword}：仅 access</li>
 *   <li>{@code jti}：token 唯一标识</li>
 *   <li>{@code iat / exp}：unix 秒</li>
 * </ul>
 */
public class RsaAdminTokenSupport implements AdminTokenSupport {

    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final String TYP_ACCESS = "access";
    private static final String TYP_REFRESH = "refresh";

    private final ConsoleServiceProperties properties;
    private final ObjectMapper objectMapper;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public RsaAdminTokenSupport(ConsoleServiceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.privateKey = RsaPemSupport.parsePrivateKey(properties.getKeyPair().getPrivateKeyPem());
        this.publicKey = RsaPemSupport.parsePublicKey(properties.getKeyPair().getPublicKeyPem());
    }

    @Override
    public IssuedToken issueAccessToken(long adminUserId, String username, List<String> roles, boolean mustChangePassword) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plus(properties.getToken().getAccessTokenTtl());
        String jti = randomJti();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getToken().getIssuer());
        claims.put("typ", TYP_ACCESS);
        claims.put("sub", String.valueOf(adminUserId));
        claims.put("username", username);
        claims.put("roles", roles == null ? List.of() : List.copyOf(roles));
        claims.put("mustChangePassword", mustChangePassword);
        claims.put("jti", jti);
        claims.put("iat", now.toEpochSecond());
        claims.put("exp", expiresAt.toEpochSecond());

        String token = buildToken(claims);
        return new IssuedToken(token, jti, expiresAt, properties.getToken().getAccessTokenTtl().toSeconds());
    }

    @Override
    public IssuedToken issueRefreshToken(long adminUserId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plus(properties.getToken().getRefreshTokenTtl());
        String jti = randomJti();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getToken().getIssuer());
        claims.put("typ", TYP_REFRESH);
        claims.put("sub", String.valueOf(adminUserId));
        claims.put("jti", jti);
        claims.put("iat", now.toEpochSecond());
        claims.put("exp", expiresAt.toEpochSecond());

        String token = buildToken(claims);
        return new IssuedToken(token, jti, expiresAt, properties.getToken().getRefreshTokenTtl().toSeconds());
    }

    @Override
    public AdminPrincipal parseAndVerifyAccessToken(String accessToken) {
        JsonNode claims = parseAndVerify(accessToken, TYP_ACCESS);
        long adminUserId = parseAdminUserId(claims);
        String username = claims.path("username").asText(null);
        if (username == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        List<String> roles = parseRoles(claims);
        String jti = claims.path("jti").asText(null);
        if (jti == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(claims.path("exp").asLong(0)), ZoneOffset.UTC);
        Duration remainingTtl = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), expiresAt);
        boolean mustChangePassword = claims.path("mustChangePassword").asBoolean(false);
        return new AdminPrincipal(adminUserId, username, roles, jti, expiresAt, remainingTtl, mustChangePassword);
    }

    @Override
    public ValidatedRefreshToken parseAndVerifyRefreshToken(String refreshToken) {
        JsonNode claims = parseAndVerify(refreshToken, TYP_REFRESH);
        long adminUserId = parseAdminUserId(claims);
        String jti = claims.path("jti").asText(null);
        if (jti == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(claims.path("exp").asLong(0)), ZoneOffset.UTC);
        return new ValidatedRefreshToken(adminUserId, jti, expiresAt);
    }

    private JsonNode parseAndVerify(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!verifySignature(signingInput, parts[2])) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        try {
            JsonNode claims = objectMapper.readTree(BASE64_URL_DECODER.decode(parts[1]));
            if (!properties.getToken().getIssuer().equals(claims.path("iss").asText())) {
                throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
            }
            if (!expectedType.equals(claims.path("typ").asText())) {
                throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
            }
            long expiresAtEpoch = claims.path("exp").asLong(0);
            if (expiresAtEpoch <= OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond()) {
                throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_EXPIRED);
            }
            return claims;
        } catch (JacksonException exception) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
    }

    private long parseAdminUserId(JsonNode claims) {
        try {
            return Long.parseLong(claims.path("sub").asText());
        } catch (NumberFormatException ex) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
    }

    private List<String> parseRoles(JsonNode claims) {
        JsonNode rolesNode = claims.path("roles");
        if (!rolesNode.isArray()) {
            return List.of();
        }
        List<String> roles = new java.util.ArrayList<>(rolesNode.size());
        for (JsonNode role : rolesNode) {
            roles.add(role.asText());
        }
        return List.copyOf(roles);
    }

    private String buildToken(Map<String, Object> claims) {
        try {
            String header = base64UrlJson(Map.of("alg", "RS256", "typ", "JWT"));
            String payload = base64UrlJson(claims);
            String signingInput = header + "." + payload;
            String signature = sign(signingInput);
            return signingInput + "." + signature;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize admin JWT payload", exception);
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
            throw new IllegalStateException("Unable to sign admin JWT", exception);
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

    private String randomJti() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
