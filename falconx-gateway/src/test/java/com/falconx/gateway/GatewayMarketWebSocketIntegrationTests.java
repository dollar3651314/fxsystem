package com.falconx.gateway;

import com.falconx.infrastructure.security.RsaPemSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * gateway 北向 WebSocket 集成测试。
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayMarketWebSocketIntegrationTests {

    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final AtomicReference<DisposableServer> MARKET_WS_SERVER = new AtomicReference<>();
    private static final AtomicReference<String> MARKET_WS_BASE_URL = new AtomicReference<>();
    private static final AtomicReference<String> CAPTURED_USER_ID = new AtomicReference<>();
    private static final AtomicReference<String> CAPTURED_UID = new AtomicReference<>();
    private static final AtomicReference<String> CAPTURED_STATUS = new AtomicReference<>();
    private static final AtomicReference<String> CAPTURED_GROUP_CODE = new AtomicReference<>();
    private static final AtomicReference<String> CAPTURED_TRACE_ID = new AtomicReference<>();

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

    @Autowired
    private ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    @BeforeAll
    static void startMarketWsServer() {
        DisposableServer server = HttpServer.create()
                .host("localhost")
                .port(0)
                .route(routes -> routes.ws("/ws/v1/market", (inbound, outbound) -> {
                    CAPTURED_USER_ID.set(inbound.headers().get("X-User-Id"));
                    CAPTURED_UID.set(inbound.headers().get("X-User-Uid"));
                    CAPTURED_STATUS.set(inbound.headers().get("X-User-Status"));
                    CAPTURED_GROUP_CODE.set(inbound.headers().get("X-User-Group-Code"));
                    CAPTURED_TRACE_ID.set(inbound.headers().get("X-Trace-Id"));
                    return outbound.sendString(inbound.receive().asString().map(message -> "echo:" + message));
                }).ws("/ws/v1/trading", (inbound, outbound) -> {
                    CAPTURED_USER_ID.set(inbound.headers().get("X-User-Id"));
                    CAPTURED_UID.set(inbound.headers().get("X-User-Uid"));
                    CAPTURED_STATUS.set(inbound.headers().get("X-User-Status"));
                    CAPTURED_GROUP_CODE.set(inbound.headers().get("X-User-Group-Code"));
                    CAPTURED_TRACE_ID.set(inbound.headers().get("X-Trace-Id"));
                    return outbound.sendString(inbound.receive().asString().map(message -> "trading-echo:" + message));
                }))
                .bindNow();
        MARKET_WS_SERVER.set(server);
        MARKET_WS_BASE_URL.set("http://localhost:" + server.port());
    }

    @AfterAll
    static void stopMarketWsServer() {
        DisposableServer server = MARKET_WS_SERVER.getAndSet(null);
        if (server != null) {
            server.disposeNow();
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("falconx.gateway.routes.market-base-url", MARKET_WS_BASE_URL::get);
        registry.add("falconx.gateway.routes.trading-base-url", MARKET_WS_BASE_URL::get);
        registry.add("falconx.gateway.security.public-key-pem", () -> TEST_PUBLIC_KEY_PEM);
    }

    @BeforeEach
    void setUp() {
        CAPTURED_USER_ID.set(null);
        CAPTURED_UID.set(null);
        CAPTURED_STATUS.set(null);
        CAPTURED_GROUP_CODE.set(null);
        CAPTURED_TRACE_ID.set(null);
        clearRedisKeys("falconx:auth:token:blacklist:*");
    }

    @Test
    void shouldRejectWebSocketHandshakeWithoutToken(CapturedOutput output) {
        assertHandshakeStatus(401, () -> connect(null, new TextFrameListener()));
        Assertions.assertTrue(output.toString().contains("gateway.websocket.handshake.rejected path=/ws/v1/market reason=missing_token"));
    }

    @Test
    void shouldRejectBannedUserHandshakeWith403(CapturedOutput output) {
        assertHandshakeStatus(403, () -> connect(buildAccessToken("42003", "U00042003", "BANNED", "gateway-ws-banned"),
                new TextFrameListener()));
        Assertions.assertTrue(output.toString().contains("gateway.websocket.handshake.rejected path=/ws/v1/market userId=42003 reason=user_banned"));
    }

    @Test
    void shouldProxyAuthorizedWebSocketAndForwardUserHeaders(CapturedOutput output) {
        TextFrameListener listener = new TextFrameListener();
        WebSocket socket = connect(buildAccessToken("42001", "U00042001", "ACTIVE", "gateway-ws-jti"), listener);

        socket.sendText("{\"type\":\"ping\",\"ts\":\"2026-04-21T12:00:00Z\"}", true).join();
        String echoed = listener.awaitText(Duration.ofSeconds(5));

        Assertions.assertEquals("echo:{\"type\":\"ping\",\"ts\":\"2026-04-21T12:00:00Z\"}", echoed);
        Assertions.assertEquals("42001", CAPTURED_USER_ID.get());
        Assertions.assertEquals("U00042001", CAPTURED_UID.get());
        Assertions.assertEquals("ACTIVE", CAPTURED_STATUS.get());
        Assertions.assertEquals("default", CAPTURED_GROUP_CODE.get());
        Assertions.assertNotNull(CAPTURED_TRACE_ID.get());
        Assertions.assertTrue(output.toString().contains("gateway.websocket.handshake.accepted path=/ws/v1/market userId=42001 status=ACTIVE"));
        Assertions.assertTrue(output.toString().contains("gateway.websocket.proxy.connected"));

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "test-finished").join();
    }

    @Test
    void shouldRejectTradingWebSocketHandshakeWithoutToken(CapturedOutput output) {
        assertHandshakeStatus(401, () -> connect("/ws/v1/trading", null, new TextFrameListener()));
        Assertions.assertTrue(output.toString().contains("gateway.websocket.handshake.rejected path=/ws/v1/trading reason=missing_token"));
    }

    @Test
    void shouldProxyAuthorizedTradingWebSocketAndForwardUserHeaders(CapturedOutput output) {
        TextFrameListener listener = new TextFrameListener();
        WebSocket socket = connect("/ws/v1/trading",
                buildAccessToken("42011", "U00042011", "ACTIVE", "gateway-trading-ws-jti"),
                listener);

        socket.sendText("{\"type\":\"ping\",\"ts\":\"2026-04-30T12:00:00Z\"}", true).join();
        String echoed = listener.awaitText(Duration.ofSeconds(5));

        Assertions.assertEquals("trading-echo:{\"type\":\"ping\",\"ts\":\"2026-04-30T12:00:00Z\"}", echoed);
        Assertions.assertEquals("42011", CAPTURED_USER_ID.get());
        Assertions.assertEquals("U00042011", CAPTURED_UID.get());
        Assertions.assertEquals("ACTIVE", CAPTURED_STATUS.get());
        Assertions.assertEquals("default", CAPTURED_GROUP_CODE.get());
        Assertions.assertNotNull(CAPTURED_TRACE_ID.get());
        Assertions.assertTrue(output.toString().contains("gateway.websocket.handshake.accepted path=/ws/v1/trading userId=42011 status=ACTIVE"));
        Assertions.assertTrue(output.toString().contains("gateway.websocket.proxy.connected"));

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "test-finished").join();
    }

    @Test
    void shouldRejectSixthConcurrentConnectionWith429(CapturedOutput output) {
        WebSocket[] sockets = new WebSocket[5];
        try {
            for (int index = 0; index < sockets.length; index++) {
                sockets[index] = connect(buildAccessToken("42002", "U00042002", "ACTIVE", "gateway-ws-limit-" + index),
                        new TextFrameListener());
            }

            assertHandshakeStatus(429, () -> connect(buildAccessToken("42002", "U00042002", "ACTIVE", "gateway-ws-limit-6"),
                    new TextFrameListener()));
            Assertions.assertTrue(output.toString().contains("reason=connection_limit_exceeded limit=5"));
        } finally {
            for (WebSocket socket : sockets) {
                if (socket != null) {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "cleanup").join();
                }
            }
        }
    }

    private WebSocket connect(String token, WebSocket.Listener listener) {
        return connect("/ws/v1/market", token, listener);
    }

    private WebSocket connect(String path, String token, WebSocket.Listener listener) {
        String query = token == null ? "" : "?token=" + token;
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + path + query), listener)
                .join();
    }

    private void assertHandshakeStatus(int expectedStatus, ThrowingConnect connectAction) {
        CompletionException exception = Assertions.assertThrows(CompletionException.class, connectAction::connect);
        Assertions.assertInstanceOf(WebSocketHandshakeException.class, exception.getCause());
        WebSocketHandshakeException handshakeException = (WebSocketHandshakeException) exception.getCause();
        Assertions.assertEquals(expectedStatus, handshakeException.getResponse().statusCode());
    }

    private String buildAccessToken(String userId, String uid, String status, String jti) {
        long issuedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).toEpochSecond();
        long expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15).toEpochSecond();
        String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payloadJson = """
                {"typ":"access","sub":"%s","uid":"%s","status":"%s","groupCode":"default","jti":"%s","iat":%d,"exp":%d}
                """.formatted(userId, uid, status, jti, issuedAt, expiresAt).replaceAll("\\s+", "");
        String encodedHeader = BASE64_URL_ENCODER.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = BASE64_URL_ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;
        return signingInput + "." + sign(signingInput);
    }

    private String sign(String signingInput) {
        try {
            PrivateKey privateKey = RsaPemSupport.parsePrivateKey(TEST_PRIVATE_KEY_PEM);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return BASE64_URL_ENCODER.encodeToString(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign JWT for gateway websocket test", exception);
        }
    }

    private void clearRedisKeys(String pattern) {
        var keys = reactiveStringRedisTemplate.scan(ScanOptions.scanOptions().match(pattern).count(100).build())
                .collectList()
                .block();
        if (keys != null && !keys.isEmpty()) {
            reactiveStringRedisTemplate.delete(Flux.fromIterable(keys)).block();
        }
    }

    private static final class TextFrameListener implements WebSocket.Listener {
        private final BlockingQueue<String> textFrames = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                textFrames.offer(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        String awaitText(Duration timeout) {
            try {
                String value = textFrames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (value == null) {
                    Assertions.fail("未在超时内收到 gateway WebSocket 文本帧");
                }
                return value;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 gateway WebSocket 文本帧时被中断", exception);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingConnect {
        void connect();
    }
}
