package com.falconx.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.gateway.controller.GatewayFallbackController;
import com.falconx.infrastructure.security.RsaPemSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

/**
 * gateway fallback / timeout 集成测试。
 *
 * <p>该测试使用真实 gateway 应用上下文，验证两类下游故障都会命中
 * `CircuitBreaker / timeout / GatewayFallbackController`，并统一返回 `99002`。
 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayFallbackIntegrationTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final HttpServerHolder IDENTITY_SERVER = new HttpServerHolder();
    private static final HttpServerHolder MARKET_SERVER = new HttpServerHolder();
    private static final HttpServerHolder TRADING_SERVER = new HttpServerHolder();
    private static final int UNAVAILABLE_WALLET_PORT = reserveUnusedPort();

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

    private WebTestClient webTestClient;

    @BeforeAll
    static void startServers() throws IOException {
        IDENTITY_SERVER.start();
        MARKET_SERVER.start();
        TRADING_SERVER.start();
    }

    @AfterAll
    static void stopServers() {
        IDENTITY_SERVER.stop();
        MARKET_SERVER.stop();
        TRADING_SERVER.stop();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("falconx.gateway.routes.identity-base-url", IDENTITY_SERVER::baseUrl);
        registry.add("falconx.gateway.routes.market-base-url", MARKET_SERVER::baseUrl);
        registry.add("falconx.gateway.routes.trading-base-url", TRADING_SERVER::baseUrl);
        registry.add("falconx.gateway.routes.wallet-base-url", () -> "http://localhost:" + UNAVAILABLE_WALLET_PORT);
        registry.add("falconx.gateway.security.public-key-pem", () -> TEST_PUBLIC_KEY_PEM);
        registry.add("falconx.gateway.security.connect-timeout-millis", () -> 100);
        registry.add("falconx.gateway.security.response-timeout-millis", () -> 200L);
    }

    @BeforeEach
    void setUp() {
        IDENTITY_SERVER.reset();
        MARKET_SERVER.reset();
        TRADING_SERVER.reset();
        clearRedisKeys("falconx:gateway:rate:auth:*");
        clearRedisKeys("falconx:gateway:rate:trading:*");
        clearRedisKeys("falconx:gateway:rate:global:*");
        clearRedisKeys("falconx:auth:token:blacklist:*");
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void shouldReturn99002WhenTradingRouteTimesOut() {
        TRADING_SERVER.setResponseDelayMillis(800);
        String accessToken = buildAccessToken("42001", "U00042001", "ACTIVE", "gateway-fallback-timeout-jti");

        try (FallbackLogProbe logProbe = new FallbackLogProbe()) {
            EntityExchangeResult<byte[]> result = webTestClient.get()
                    .uri("/api/v1/trading/accounts/me")
                    .header("Authorization", "Bearer " + accessToken)
                    .exchange()
                    .expectStatus().isEqualTo(504)
                    .expectHeader().exists("X-Trace-Id")
                    .expectBody()
                    .returnResult();

            String body = new String(result.getResponseBodyContent(), StandardCharsets.UTF_8);
            JsonNode json = readJson(result);

            Assertions.assertEquals("99002", json.path("code").asText());
            Assertions.assertEquals("dependency timeout", json.path("message").asText());
            Assertions.assertFalse(body.contains("ReadTimeoutException"));
            Assertions.assertFalse(body.contains("TimeoutException"));
            Assertions.assertNotNull(TRADING_SERVER.capturedRequest());
            Assertions.assertTrue(logProbe.contains("gateway.route.fallback routeId=trading-route"));
        }
    }

    @Test
    void shouldReturn99002WhenWalletRouteIsUnavailable() {
        String accessToken = buildAccessToken("42002", "U00042002", "ACTIVE", "gateway-fallback-unavailable-jti");

        try (FallbackLogProbe logProbe = new FallbackLogProbe()) {
            EntityExchangeResult<byte[]> result = webTestClient.get()
                    .uri("/api/v1/wallet/ping")
                    .header("Authorization", "Bearer " + accessToken)
                    .exchange()
                    .expectStatus().isEqualTo(504)
                    .expectHeader().exists("X-Trace-Id")
                    .expectBody()
                    .returnResult();

            String body = new String(result.getResponseBodyContent(), StandardCharsets.UTF_8);
            JsonNode json = readJson(result);

            Assertions.assertEquals("99002", json.path("code").asText());
            Assertions.assertEquals("dependency timeout", json.path("message").asText());
            Assertions.assertFalse(body.contains("ConnectException"));
            Assertions.assertFalse(body.contains("Connection refused"));
            Assertions.assertTrue(logProbe.contains("gateway.route.fallback routeId=wallet-route"));
        }
    }

    private JsonNode readJson(EntityExchangeResult<byte[]> result) {
        try {
            return OBJECT_MAPPER.readTree(result.getResponseBodyContent());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to parse gateway response JSON", exception);
        }
    }

    private static String buildAccessToken(String userId, String uid, String status, String jti) {
        try {
            PrivateKey privateKey = RsaPemSupport.parsePrivateKey(TEST_PRIVATE_KEY_PEM);
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("typ", "access");
            claims.put("sub", userId);
            claims.put("uid", uid);
            claims.put("status", status);
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

    private static int reserveUnusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to reserve an unused port for gateway fallback test", exception);
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

    /**
     * fallback 日志探针。
     *
     * <p>该探针通过 `GatewayFallbackController` 的日志事件证明回退控制器被真实触发。
     */
    private static final class FallbackLogProbe implements AutoCloseable {

        private final Logger logger = (Logger) LoggerFactory.getLogger(GatewayFallbackController.class);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private FallbackLogProbe() {
            appender.start();
            logger.addAppender(appender);
        }

        private boolean contains(String fragment) {
            return appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains(fragment));
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * 下游桩服务封装。
     *
     * <p>该封装支持记录 gateway 透传请求，并按测试需要人为放慢响应，触发 gateway timeout/fallback。
     */
    private static final class HttpServerHolder {

        private final AtomicReference<CapturedRequest> capturedRequest = new AtomicReference<>();
        private final AtomicLong responseDelayMillis = new AtomicLong(0L);
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
            responseDelayMillis.set(0L);
        }

        private void setResponseDelayMillis(long delayMillis) {
            responseDelayMillis.set(delayMillis);
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
            capturedRequest.set(new CapturedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestMethod(),
                    headers,
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
            ));

            long delayMillis = responseDelayMillis.get();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Delayed fallback stub interrupted", exception);
                }
            }

            String traceId = exchange.getRequestHeaders().getFirst("X-Trace-Id");
            String responseBody = """
                    {
                      "code": "0",
                      "message": "success",
                      "data": null,
                      "timestamp": "%s",
                      "traceId": "%s"
                    }
                    """.formatted(OffsetDateTime.now(), traceId);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Trace-Id", traceId);
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // gateway 已因超时提前回退时，下游桩的回写会被对端关闭；该异常不影响测试结论
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * 下游请求捕获对象。
     */
    private record CapturedRequest(String path, String method, Map<String, String> headers, String body) {
    }
}
