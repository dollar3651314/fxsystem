package com.falconx.gateway;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.gateway.config.GatewaySecurityProperties;
import com.falconx.infrastructure.security.RsaPemSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.data.redis.core.ScanOptions;
import reactor.core.publisher.Flux;

/**
 * gateway 路由与鉴权集成测试。
 *
 * <p>该测试使用 JDK `HttpServer` 作为下游桩服务，验证 Stage 4 的三个关键点：
 *
 * <ul>
 *   <li>公开认证接口可直接通过 gateway 转发</li>
 *   <li>受保护接口无 token 时被 gateway 拒绝</li>
 *   <li>受保护接口携带合法 token 时会把用户头和 traceId 透传到下游</li>
 * </ul>
 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class GatewayRoutingIntegrationTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final HttpServerHolder IDENTITY_SERVER = new HttpServerHolder();
    private static final HttpServerHolder MARKET_SERVER = new HttpServerHolder();
    private static final HttpServerHolder TRADING_SERVER = new HttpServerHolder();
    private static final HttpServerHolder WALLET_SERVER = new HttpServerHolder();

    private static final String TEST_PRIVATE_KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDpEXzQR+4ufMeo
            eNQcFN+4qLchG2EqmVLmSrsJWveQJR2wlQNZbAW5/BnaIEDc4OU7GFxdC7Y/tEpU
            fzQpVLgJwb4okycrckGHuJ6yy1GBn+dRc+KCDY1QRWEdHjtFHs2MltTlOJXoAda8
            zucRYuEWQMTp0rfFz4FS/NNGjA/qlcHP9LS1iUFXaeidh4wqJUGNKavGFR+RA6e+
            Gr5ljmFfisMvY+R7qfih1x2L64Qp8fjdwF0T0lv6S8x2sCtd52xn2DLF4rJegXym
            1fbpgLWFkiQd9OtmXihnP+FQadhcLt0IDQ2rXQo1unJEWODmBet28dubXaX9jcQD
            fP7zyrrXAgMBAAECggEABWKSa4fSvGGi0mcvfqNGB+YfYZ2txEqSSCTjjVxZ36UK
            S5t7qvYYFpP5Od/ACYL8mwqB4sDgn0lR9axv7u4BNrWQcOsXWsNlW5z++bN0UmuS
            LAliledoUo76fDalxZmBodhQ2nz7pbVvkV1LZHdYxMUXNHJi2AZM7gPoCi7gv2Zn
            2PlJiH4hgX78ndz+LraeFeOGqm41xsfWDl7SO1iX4N1F2PI+AOyzQk2qvyXK11Vf
            0o4iaeecao8nDp8vEXDMGl6ZeOFzD9jercjzn6oiU1Lkp00jAx8BPR9fxDssbSTy
            lKRxyzCsbPFOHyCDe0eExO2acAjM9X82K+S9i10bPQKBgQD9NuedMRFFFWyth+Bn
            CEIT1x/hs5p1bDgCo8djIDU2VVhc3aXWvu0+ubjYgaNpIUAElmtELBzaAjOamKZP
            xE/YKr0uaXqZVPdfZU+tj1hYBeYYq5xRFDPKU+avm3wTsXmFi9YrxMo9pyvAq+JY
            EtR3zdUb1OFSPPzj5fvNNLB8TQKBgQDrodkH3Zwxc5fl9AN06XFu/CtFemw/gC7Q
            /vgxKbghyyxJ9WtM6QUIBjOKYahmW7JREnCnckHU5MakVpWZ1J4eup9p/8BI7AV/
            TvzwUMVEpHOFCv6GKK+bU/Hr4J/pL9rnN9pArUcvn+6MA19GkhH+CTNl48Wmkhh6
            61MuLbeVswKBgDI/S/TggMnRt5Az727ir6IaRWRXbKYyhGbZsz5TbNvMUc2T2k3j
            81ZIKoskJpY9F+QRKVYM3ujQGQdrlU0s6p890+661a5JsxEGHKqXUHOfMArjOxDH
            zoMu5Q8h7pxF0pSSrDxhP7S+UKtaMH9DtU/U055DPzc/jPt4buBIvWDdAoGBAM86
            funI/4YKOCvXh3a5m7ZU9iVbfnn2XLYXluV94F9wFNpSiXSRdohRE+D+9CBZQqDE
            S+kntjfqn7yGHXm1oP47eNm7QDuhv0/wgslC78rnqmT2f1Qz0gUUNa4R1fE50hYJ
            a9v3yKRczmm6yX8CFerJ4rcYM4rD792iunvhXxS7AoGAANVQ4bIdZ9R15wlVHwMx
            K1pHN92mwh0nYEHyPwkyYhBj9biUD1RQlasZgO383J7WkuCdFc090tskCnKuyGlH
            DQU1mSWJu5s91YQQK01aGO7b2PyQzdbW+H58gmxXbcXTteffkqkp4GxLh2GfgTgL
            KkpAX18F0mENe5bPKhHdf78=
            -----END PRIVATE KEY-----
            """;

    private static final String TEST_PUBLIC_KEY_PEM = """
            -----BEGIN PUBLIC KEY-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA6RF80EfuLnzHqHjUHBTf
            uKi3IRthKplS5kq7CVr3kCUdsJUDWWwFufwZ2iBA3ODlOxhcXQu2P7RKVH80KVS4
            CcG+KJMnK3JBh7iesstRgZ/nUXPigg2NUEVhHR47RR7NjJbU5TiV6AHWvM7nEWLh
            FkDE6dK3xc+BUvzTRowP6pXBz/S0tYlBV2nonYeMKiVBjSmrxhUfkQOnvhq+ZY5h
            X4rDL2Pke6n4odcdi+uEKfH43cBdE9Jb+kvMdrArXedsZ9gyxeKyXoF8ptX26YC1
            hZIkHfTrZl4oZz/hUGnYXC7dCA0Nq10KNbpyRFjg5gXrdvHbm12l/Y3EA3z+88q6
            1wIDAQAB
            -----END PUBLIC KEY-----
            """;

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    private GatewaySecurityProperties gatewaySecurityProperties;

    private WebTestClient webTestClient;

    @BeforeAll
    static void startServers() throws IOException {
        IDENTITY_SERVER.start();
        MARKET_SERVER.start();
        TRADING_SERVER.start();
        WALLET_SERVER.start();
    }

    @AfterAll
    static void stopServers() {
        IDENTITY_SERVER.stop();
        MARKET_SERVER.stop();
        TRADING_SERVER.stop();
        WALLET_SERVER.stop();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("falconx.gateway.routes.identity-base-url", IDENTITY_SERVER::baseUrl);
        registry.add("falconx.gateway.routes.market-base-url", MARKET_SERVER::baseUrl);
        registry.add("falconx.gateway.routes.trading-base-url", TRADING_SERVER::baseUrl);
        registry.add("falconx.gateway.routes.wallet-base-url", WALLET_SERVER::baseUrl);
        registry.add("falconx.gateway.security.public-key-pem", () -> TEST_PUBLIC_KEY_PEM);
    }

    @BeforeEach
    void resetCapturedRequests() {
        IDENTITY_SERVER.reset();
        MARKET_SERVER.reset();
        TRADING_SERVER.reset();
        WALLET_SERVER.reset();
        clearRedisKeys("falconx:auth:token:blacklist:*");
        clearRedisKeys("falconx:gateway:rate:auth:*");
        clearRedisKeys("falconx:gateway:rate:trading:*");
        clearRedisKeys("falconx:gateway:rate:global:*");
        gatewaySecurityProperties.setGlobalRequestRateLimitPerMinute(200);
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void shouldProxyPublicAuthRequestWithoutAuthentication(CapturedOutput output) {
        EntityExchangeResult<byte[]> result = webTestClient.post()
                .uri("/api/v1/auth/login")
                .header("X-Trace-Id", "frontend-fixed-trace")
                .bodyValue("""
                        {
                          "email": "alice@example.com",
                          "password": "Passw0rd!"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .returnResult();

        CapturedRequest capturedRequest = IDENTITY_SERVER.capturedRequest();
        String responseTraceId = result.getResponseHeaders().getFirst("X-Trace-Id");

        Assertions.assertNotNull(capturedRequest);
        Assertions.assertEquals("/api/v1/auth/login", capturedRequest.path());
        Assertions.assertEquals(responseTraceId, capturedRequest.headers().get("x-trace-id"));
        Assertions.assertNotEquals("frontend-fixed-trace", capturedRequest.headers().get("x-trace-id"));
        Assertions.assertFalse(capturedRequest.headers().containsKey("x-user-id"));
        Assertions.assertTrue(output.toString().contains("gateway.request.received path=/api/v1/auth/login method=POST"));
        Assertions.assertTrue(output.toString().contains("traceId=" + responseTraceId));
    }

    @Test
    void shouldRejectProtectedRequestWithoutBearerToken(CapturedOutput output) {
        EntityExchangeResult<byte[]> result = webTestClient.get()
                .uri("/api/v1/market/quotes/BTCUSDT")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .returnResult();

        String responseBody = new String(result.getResponseBodyContent(), StandardCharsets.UTF_8);
        try {
            Assertions.assertEquals("10001", OBJECT_MAPPER.readTree(responseBody).path("code").asText());
            Assertions.assertEquals("Unauthorized", OBJECT_MAPPER.readTree(responseBody).path("message").asText());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to parse gateway unauthorized response", exception);
        }
        Assertions.assertNull(MARKET_SERVER.capturedRequest());
        String responseTraceId = result.getResponseHeaders().getFirst("X-Trace-Id");
        Assertions.assertTrue(output.toString().contains("gateway.auth.rejected path=/api/v1/market/quotes/BTCUSDT reason=missing_or_invalid_authorization_header"));
        Assertions.assertTrue(output.toString().contains("traceId=" + responseTraceId));
    }

    @Test
    void shouldForwardProtectedTradingRequestWithVerifiedUserHeaders(CapturedOutput output) {
        String accessToken = buildAccessToken("32001", "U00032001", "ACTIVE", "gateway-stage4-jti");

        EntityExchangeResult<byte[]> result = webTestClient.get()
                .uri("/api/v1/trading/accounts/me")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Trace-Id", "frontend-trace-should-be-ignored")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .returnResult();

        CapturedRequest capturedRequest = TRADING_SERVER.capturedRequest();
        String responseTraceId = result.getResponseHeaders().getFirst("X-Trace-Id");

        Assertions.assertNotNull(capturedRequest);
        Assertions.assertEquals("/api/v1/trading/accounts/me", capturedRequest.path());
        Assertions.assertEquals("32001", capturedRequest.headers().get("x-user-id"));
        Assertions.assertEquals("U00032001", capturedRequest.headers().get("x-user-uid"));
        Assertions.assertEquals("ACTIVE", capturedRequest.headers().get("x-user-status"));
        Assertions.assertEquals("vip-a", capturedRequest.headers().get("x-user-group-code"));
        Assertions.assertEquals("gateway-stage4-jti", capturedRequest.headers().get("x-user-jti"));
        Assertions.assertEquals(responseTraceId, capturedRequest.headers().get("x-trace-id"));
        Assertions.assertNotEquals("frontend-trace-should-be-ignored", capturedRequest.headers().get("x-trace-id"));
        Assertions.assertTrue(output.toString().contains("gateway.auth.accepted path=/api/v1/trading/accounts/me userId=32001 status=ACTIVE"));
        Assertions.assertTrue(output.toString().contains("traceId=" + responseTraceId));
    }

    @Test
    void shouldRouteMeWithdrawToTradingNotIdentity() {
        // 回归 BUG-01/BUG-02：/api/v1/me/withdraw/** 必须先匹配到 trading-core，
        // 否则会落到通用 /api/v1/me/** → identity-route 上抛 500。
        String accessToken = buildAccessToken("32101", "U00032101", "ACTIVE", "gateway-me-withdraw-jti");

        webTestClient.get()
                .uri("/api/v1/me/withdraw/whitelist")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk();

        CapturedRequest tradingCaptured = TRADING_SERVER.capturedRequest();
        Assertions.assertNotNull(tradingCaptured, "/api/v1/me/withdraw/** 应落到 trading-core mock");
        Assertions.assertEquals("/api/v1/me/withdraw/whitelist", tradingCaptured.path());
        Assertions.assertNull(IDENTITY_SERVER.capturedRequest(),
                "/api/v1/me/withdraw/** 不应被 /api/v1/me/** identity-route 截胡");
    }

    @Test
    void shouldStillRouteOtherMePathsToIdentity() {
        // 回归保护：除 /api/v1/me/withdraw/** 之外的 /api/v1/me/** 仍走 identity（profile / kyc 等）。
        String accessToken = buildAccessToken("32102", "U00032102", "ACTIVE", "gateway-me-profile-jti");

        webTestClient.get()
                .uri("/api/v1/me/profile")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk();

        CapturedRequest identityCaptured = IDENTITY_SERVER.capturedRequest();
        Assertions.assertNotNull(identityCaptured, "/api/v1/me/profile 应落到 identity mock");
        Assertions.assertEquals("/api/v1/me/profile", identityCaptured.path());
        Assertions.assertNull(TRADING_SERVER.capturedRequest(),
                "/api/v1/me/profile 不应被 /api/v1/me/withdraw/** trading-route 误捕");
    }

    @Test
    void shouldRejectBlacklistedAccessToken() {
        String accessToken = buildAccessToken("32002", "U00032002", "ACTIVE", "gateway-blacklisted-jti");
        reactiveStringRedisTemplate.opsForValue()
                .set("falconx:auth:token:blacklist:gateway-blacklisted-jti", "1")
                .block();

        EntityExchangeResult<byte[]> result = webTestClient.get()
                .uri("/api/v1/trading/accounts/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .returnResult();

        JsonNode responseJson = readJson(result);
        Assertions.assertEquals("10001", responseJson.path("code").asText());
        Assertions.assertNull(TRADING_SERVER.capturedRequest());
    }

    @Test
    void shouldRejectHs256Token() {
        String token = buildToken(Map.of("alg", "HS256", "typ", "JWT"),
                Map.of(
                        "typ", "access",
                        "sub", "32003",
                        "uid", "U00032003",
                        "status", "ACTIVE",
                        "jti", "gateway-hs256-jti",
                        "exp", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15).toEpochSecond()
                ),
                "fake-hs256-signature");

        webTestClient.get()
                .uri("/api/v1/trading/accounts/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("10001");
    }

    @Test
    void shouldRejectNoneAlgorithmToken() {
        String token = buildToken(Map.of("alg", "none", "typ", "JWT"),
                Map.of(
                        "typ", "access",
                        "sub", "32004",
                        "uid", "U00032004",
                        "status", "ACTIVE",
                        "jti", "gateway-none-jti",
                        "exp", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15).toEpochSecond()
                ),
                "");

        webTestClient.get()
                .uri("/api/v1/trading/accounts/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("10001");
    }

    @Test
    void shouldRateLimitAuthRequestByIpAtGateway() {
        for (int attempt = 0; attempt < 20; attempt++) {
            webTestClient.post()
                    .uri("/api/v1/auth/login")
                    .bodyValue("""
                            {
                              "email": "alice@example.com",
                              "password": "Passw0rd!"
                            }
                            """)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().returnResult();
        }

        webTestClient.post()
                .uri("/api/v1/auth/login")
                .bodyValue("""
                        {
                          "email": "alice@example.com",
                          "password": "Passw0rd!"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("10003");
    }

    @Test
    void shouldRateLimitTradingRequestByUserAtGateway() {
        alignToSecondBoundary();
        String accessToken = buildAccessToken("32005", "U00032005", "ACTIVE", "gateway-trading-rate-limit-jti");

        for (int attempt = 0; attempt < 10; attempt++) {
            webTestClient.get()
                    .uri("/api/v1/trading/accounts/me")
                    .header("Authorization", "Bearer " + accessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().returnResult();
        }

        webTestClient.get()
                .uri("/api/v1/trading/accounts/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("10012")
                .jsonPath("$.message").isEqualTo("Trading Rate Limited");
    }

    @Test
    void shouldRateLimitGlobalRequestsByIpAtGateway() {
        gatewaySecurityProperties.setGlobalRequestRateLimitPerMinute(5);
        String clientIp = "203.0.113.77";
        for (int attempt = 0; attempt < 5; attempt++) {
            webTestClient.post()
                    .uri("/api/v1/auth/refresh")
                    .header("X-Client-Ip", clientIp)
                    .bodyValue("""
                            {
                              "refreshToken": "dummy-refresh-token"
                            }
                            """)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().returnResult();
        }

        webTestClient.post()
                .uri("/api/v1/auth/refresh")
                .header("X-Client-Ip", clientIp)
                .bodyValue("""
                        {
                          "refreshToken": "dummy-refresh-token"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("10013")
                .jsonPath("$.message").isEqualTo("Global IP Rate Limited");
    }

    @Test
    void shouldRejectSameAccessTokenAfterLogoutViaGateway() {
        String accessToken = buildAccessToken("32006", "U00032006", "ACTIVE", "gateway-logout-jti");
        IDENTITY_SERVER.setAfterCaptureHook(request -> {
            if ("/api/v1/auth/logout".equals(request.path())) {
                reactiveStringRedisTemplate.opsForValue()
                        .set("falconx:auth:token:blacklist:" + request.headers().get("x-user-jti"), "1")
                        .block();
            }
        });

        webTestClient.post()
                .uri("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("0");

        CapturedRequest logoutRequest = IDENTITY_SERVER.capturedRequest();
        Assertions.assertNotNull(logoutRequest);
        Assertions.assertEquals("/api/v1/auth/logout", logoutRequest.path());
        Assertions.assertEquals("gateway-logout-jti", logoutRequest.headers().get("x-user-jti"));

        webTestClient.get()
                .uri("/api/v1/trading/accounts/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("10001");
    }

    private static String buildAccessToken(String userId, String uid, String status, String jti) {
        try {
            PrivateKey privateKey = RsaPemSupport.parsePrivateKey(TEST_PRIVATE_KEY_PEM);
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("typ", "access");
            claims.put("sub", userId);
            claims.put("uid", uid);
            claims.put("status", status);
            claims.put("groupCode", "vip-a");
            claims.put("jti", jti);
            claims.put("exp", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15).toEpochSecond());
            String signingInput = buildTokenSigningInput(Map.of("alg", "RS256", "typ", "JWT"), claims);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + BASE64_URL_ENCODER.encodeToString(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to create test token", exception);
        }
    }

    private static String buildToken(Map<String, Object> header, Map<String, Object> claims, String signature) {
        return buildTokenSigningInput(header, claims) + "." + signature;
    }

    private static String buildTokenSigningInput(Map<String, Object> header, Map<String, Object> claims) {
        return base64UrlJson(header) + "." + base64UrlJson(claims);
    }

    private static String base64UrlJson(Map<String, Object> content) {
        try {
            return BASE64_URL_ENCODER.encodeToString(OBJECT_MAPPER.writeValueAsBytes(content));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to encode JWT JSON", exception);
        }
    }

    private JsonNode readJson(EntityExchangeResult<byte[]> result) {
        try {
            return OBJECT_MAPPER.readTree(result.getResponseBodyContent());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to parse gateway response JSON", exception);
        }
    }

    private void clearRedisKeys(String pattern) {
        List<String> keys = reactiveStringRedisTemplate.scan(ScanOptions.scanOptions().match(pattern).count(100).build())
                .collectList()
                .block();
        if (keys != null && !keys.isEmpty()) {
            reactiveStringRedisTemplate.delete(Flux.fromIterable(keys)).block();
        }
    }

    private void alignToSecondBoundary() {
        long nowMillis = System.currentTimeMillis();
        long sleepMillis = 1000 - (nowMillis % 1000);
        if (sleepMillis > 0 && sleepMillis < 1000) {
            try {
                Thread.sleep(sleepMillis + 10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while aligning test execution to second boundary", exception);
            }
        }
    }

    /**
     * 下游桩服务封装。
     *
     * <p>该封装统一承载 gateway 集成测试所需的本地 HTTP 服务器，
     * 用于记录 gateway 实际透传给下游的请求路径和请求头。
     */
    private static final class HttpServerHolder {

        private final AtomicReference<CapturedRequest> capturedRequest = new AtomicReference<>();
        private final AtomicReference<Consumer<CapturedRequest>> afterCaptureHook = new AtomicReference<>();
        private HttpServer httpServer;

        private void start() throws IOException {
            this.httpServer = HttpServer.create(new InetSocketAddress(0), 0);
            this.httpServer.createContext("/", this::handle);
            this.httpServer.setExecutor(Executors.newSingleThreadExecutor());
            this.httpServer.start();
        }

        private void stop() {
            if (httpServer != null) {
                httpServer.stop(0);
            }
        }

        private void reset() {
            capturedRequest.set(null);
            afterCaptureHook.set(null);
        }

        private void setAfterCaptureHook(Consumer<CapturedRequest> hook) {
            afterCaptureHook.set(hook);
        }

        private String baseUrl() {
            return "http://localhost:" + httpServer.getAddress().getPort();
        }

        private CapturedRequest capturedRequest() {
            return capturedRequest.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((key, value) -> headers.put(key.toLowerCase(), value.isEmpty() ? "" : value.get(0)));
            CapturedRequest request = new CapturedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestMethod(),
                    headers,
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
            );
            capturedRequest.set(request);
            Consumer<CapturedRequest> hook = afterCaptureHook.get();
            if (hook != null) {
                hook.accept(request);
            }

            String traceId = exchange.getRequestHeaders().getFirst("X-Trace-Id");
            String responseBody = """
                    {
                      "code": "0",
                      "message": "success",
                      "data": {
                        "path": "%s",
                        "traceId": "%s",
                        "userId": "%s"
                      },
                      "timestamp": "%s",
                      "traceId": "%s"
                    }
                    """.formatted(
                    exchange.getRequestURI().getPath(),
                    traceId,
                    headers.getOrDefault("x-user-id", ""),
                    OffsetDateTime.now(),
                    traceId
            );
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Trace-Id", traceId);
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    /**
     * 下游请求捕获对象。
     *
     * @param path gateway 转发后的请求路径
     * @param method HTTP 方法
     * @param headers gateway 实际透传的请求头
     * @param body 原始请求体
     */
    private record CapturedRequest(String path, String method, Map<String, String> headers, String body) {
    }
}
