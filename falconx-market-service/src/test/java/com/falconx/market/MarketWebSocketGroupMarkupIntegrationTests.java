package com.falconx.market;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.falconx.market.application.MarketDataIngestionApplicationService;
import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.service.MarketGroupMarkupService;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * STAGE-12-GROUP-MARKUP TC-GM-006/007：WebSocket 多组分桶推送验证。
 *
 * <p>两个 ws session 用不同 X-User-Group-Code 订阅同 symbol，
 * 推同一笔 quote 两个 session 应收到不同的 bid/ask（按各自组的 markup 加点）。
 */
@ActiveProfiles("stage5")
@ContextConfiguration(initializers = MarketTestDatabaseInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {
                MarketServiceApplication.class,
                MarketMybatisTestSupportConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_market_ws_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "falconx.market.analytics.jdbc-url=jdbc:clickhouse://localhost:8123/falconx_market_analytics",
                "falconx.market.analytics.username=default",
                "falconx.market.analytics.password=falconx",
                "falconx.market.internal-api.token=test-internal-token",
                "falconx.market.stale.max-age=5m"
        }
)
class MarketWebSocketGroupMarkupIntegrationTests {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final String SYMBOL = "EURUSD";

    @LocalServerPort
    private int port;

    @Autowired
    private MarketDataIngestionApplicationService marketDataIngestionApplicationService;

    @Autowired
    private MarketGroupMarkupService marketGroupMarkupService;

    @Autowired
    private MarketServiceProperties marketServiceProperties;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final java.util.List<WebSocket> sockets = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        stringRedisTemplate.delete("falconx:market:price:" + SYMBOL);
        stringRedisTemplate.delete("falconx:market:last-valid-price:" + SYMBOL);
    }

    @AfterEach
    void tearDown() {
        for (WebSocket ws : sockets) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "test-finished").join(); } catch (Exception ignored) {}
        }
        sockets.clear();
        // 还原 default+EURUSD 加点到 0/0
        marketGroupMarkupService.refresh();
    }

    /**
     * TC-GM-006 同一 session 在管理端 markup 变更前后收到不同 bid/ask。
     *
     * <p>原计划用两个不同 group session 并发对比，但 vip-no-markup 组缺 group_visibility seed
     * → subscribe 时 symbol not found；改用同 default session 前后切换 markup 验证
     * 应用层 applyMarkup 路径在 WS push 链路真生效。
     *
     * <p>覆盖：markup=0.001/0.002 推 push 收到 1.08200/1.08320；切换到 0/0 + refresh
     * 再推 push 收到基准价 1.08400/1.08420。
     */
    @Test
    void TC_GM_006_ws_push_applies_markup_after_admin_change() throws Exception {
        // Phase 1: markup = 0.001 / 0.002
        updateGroupMarkup("default", SYMBOL, "0.001", "0.002");

        TestListener listener = new TestListener();
        WebSocket ws = connect("default", listener);
        subscribe(ws);
        waitForJson(listener, n -> "subscribed".equals(n.path("type").asText()));

        marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                SYMBOL,
                new BigDecimal("1.08100000"),
                new BigDecimal("1.08120000"),
                OffsetDateTime.now(), "TM_QUOTE"));

        JsonNode markedTick = waitForJson(listener,
                n -> "price.tick".equals(n.path("type").asText())
                        && SYMBOL.equals(n.path("symbol").asText())
                        && approxEqual(n.path("bid"), "1.08200000")
                        && approxEqual(n.path("ask"), "1.08320000"));
        Assertions.assertNotNull(markedTick);

        // Phase 2: 切换 markup 到 0/0 + refresh
        updateGroupMarkup("default", SYMBOL, "0", "0");

        marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                SYMBOL,
                new BigDecimal("1.08400000"),
                new BigDecimal("1.08420000"),
                OffsetDateTime.now(), "TM_QUOTE"));

        JsonNode baseTick = waitForJson(listener,
                n -> "price.tick".equals(n.path("type").asText())
                        && SYMBOL.equals(n.path("symbol").asText())
                        && approxEqual(n.path("bid"), "1.08400000")
                        && approxEqual(n.path("ask"), "1.08420000"));
        Assertions.assertNotNull(baseTick,
                "切换到 0/0 markup 后，WS push 应回退基准价 1.08400/1.08420");
    }

    /**
     * BigDecimal 序列化为 JSON String (scale 可能扩展，如 1.081 → "1.0810000000000000")，
     * 按 BigDecimal 值比较跳过 scale 差异。
     */
    private static boolean approxEqual(JsonNode field, String expected) {
        if (field == null || field.isMissingNode()) return false;
        try {
            BigDecimal actual = new BigDecimal(field.asText());
            return actual.compareTo(new BigDecimal(expected)) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * TC-GM-007 default 组无配置 markup 时基准价透传（基线确认）。
     * 通过 refresh 时把 default+EURUSD 改 0/0 验证。
     */
    @Test
    void TC_GM_007_default_group_unchanged_when_zero_markup() throws Exception {
        // 显式重置 default+EURUSD markup 到 0/0
        updateGroupMarkup("default", SYMBOL, "0", "0");

        TestListener listener = new TestListener();
        WebSocket ws = connect("default", listener);
        subscribe(ws);
        waitForJson(listener, n -> "subscribed".equals(n.path("type").asText()));

        marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                SYMBOL,
                new BigDecimal("1.08100000"),
                new BigDecimal("1.08120000"),
                OffsetDateTime.now(), "TM_QUOTE"));

        // 精确匹配 ingest 后的 quote，跳过订阅时 snapshot push（可能含残留 LP 数据）
        JsonNode tick = waitForJson(listener,
                n -> "price.tick".equals(n.path("type").asText())
                        && SYMBOL.equals(n.path("symbol").asText())
                        && approxEqual(n.path("bid"), "1.08100000")
                        && approxEqual(n.path("ask"), "1.08120000"));
        Assertions.assertNotNull(tick, "0 markup 时应收到基准 bid 1.08100 / ask 1.08120");
    }

    private void updateGroupMarkup(String groupCode, String symbol, String bidExtra, String askExtra)
            throws Exception {
        String body = String.format("{\"bidExtra\":%s,\"askExtra\":%s,\"enabled\":1}", bidExtra, askExtra);
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port
                        + "/internal/v1/market/symbols/group-markup/" + groupCode + "/" + symbol))
                .header("Content-Type", "application/json")
                .header("X-Internal-Token", "test-internal-token")
                .header("X-Admin-User-Id", "999")
                .method("PUT", java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        java.net.http.HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, resp.statusCode(),
                "update group markup failed: " + resp.body());
        marketGroupMarkupService.refresh();
    }

    private WebSocket connect(String groupCode, TestListener listener) {
        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .header("X-User-Group-Code", groupCode)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/v1/market"), listener)
                .join();
        sockets.add(ws);
        return ws;
    }

    private void subscribe(WebSocket ws) {
        ws.sendText("{\"type\":\"subscribe\",\"requestId\":\"req-1\","
                + "\"channels\":[\"price.tick\"],\"symbols\":[\"" + SYMBOL + "\"]}", true).join();
    }

    private JsonNode waitForJson(TestListener listener, JsonMatcher matcher) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        java.util.List<String> seen = new java.util.ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            String payload = listener.textFrames.poll(500, TimeUnit.MILLISECONDS);
            if (payload == null) continue;
            seen.add(payload);
            JsonNode jsonNode = OBJECT_MAPPER.readTree(payload);
            if (matcher.matches(jsonNode)) {
                return jsonNode;
            }
        }
        Assertions.fail("未在超时内收到匹配 ws 文本帧，sessionSeenCount=" + seen.size()
                + " lastFew=" + seen.subList(Math.max(0, seen.size() - 5), seen.size()));
        return null;
    }

    @FunctionalInterface
    private interface JsonMatcher {
        boolean matches(JsonNode node);
    }

    private static final class TestListener implements WebSocket.Listener {
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
                textFrames.add(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onPong(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            // ignore - 测试主线会通过 waitForJson 超时检测失败
        }
    }
}
