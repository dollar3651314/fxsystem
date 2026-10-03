package com.falconx.trading.websocket;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.TradingCoreServiceApplication;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.application.TradingPositionMarginApplicationService;
import com.falconx.trading.command.AddIsolatedMarginCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.repository.RedisTradingScheduleSnapshotRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import com.falconx.trading.service.model.TradingSessionWindow;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 用户交易实时 WebSocket 集成测试。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380"
        }
)
class TradingUserWebSocketIntegrationTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TradingTestSupportMapper tradingTestSupportMapper;

    @Autowired
    private OpenPositionSnapshotStore openPositionSnapshotStore;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private TradingAccountService tradingAccountService;

    @Autowired
    private TradingCoreServiceProperties tradingCoreServiceProperties;

    @Autowired
    private RedisTradingScheduleSnapshotRepository tradingScheduleSnapshotRepository;

    @Autowired
    private QuoteDrivenEngine quoteDrivenEngine;

    @Autowired
    private TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService;

    @Autowired
    private TradingPositionMarginApplicationService tradingPositionMarginApplicationService;

    @BeforeEach
    void setUp() {
        tradingTestSupportMapper.clearOwnerTables();
        openPositionSnapshotStore.replaceAll(List.of());
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:BTCUSDT");
    }

    @Test
    void shouldPushAccountSnapshotAndOrderPositionAccountUpdatesAfterCommit() throws Exception {
        long userId = 33001L;
        TextFrameListener listener = new TextFrameListener();
        WebSocket socket = connect(userId, listener);

        JsonNode snapshot = listener.awaitType("account.snapshot", Duration.ofSeconds(5));
        Assertions.assertEquals("account", snapshot.path("channel").asText());
        Assertions.assertEquals(userId, snapshot.path("data").path("userId").asLong());

        socket.sendText("""
                {"type":"subscribe","requestId":"sub-1","channels":["account","orders","positions","trades","margin","ledger","liquidations"]}
                """, true).join();
        JsonNode subscribed = listener.awaitType("subscribed", Duration.ofSeconds(5));
        Assertions.assertEquals("sub-1", subscribed.path("requestId").asText());

        tradingAccountService.creditDeposit(
                userId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("2000.00000000"),
                "ws-credit-33001",
                "seed-ws-33001",
                OffsetDateTime.now()
        );
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now()
        );
        OrderPlacementResult orderPlacementResult = tradingOrderPlacementApplicationService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId,
                "BTCUSDT",
                TradingOrderSide.BUY,
                new BigDecimal("1.0"),
                new BigDecimal("10"),
                null,
                new BigDecimal("10100.0"),
                new BigDecimal("9800.0"),
                "ws-order-33001"
        ));

        Map<String, JsonNode> updates = listener.awaitTypes(
                Set.of("order.update", "position.update", "trade.created", "account.update", "ledger.created"),
                Duration.ofSeconds(5)
        );
        JsonNode orderUpdate = updates.get("order.update");
        JsonNode positionUpdate = updates.get("position.update");
        JsonNode tradeCreated = updates.get("trade.created");
        JsonNode accountUpdate = updates.get("account.update");
        JsonNode ledgerCreated = updates.get("ledger.created");

        Assertions.assertEquals("orders", orderUpdate.path("channel").asText());
        Assertions.assertEquals("FILLED", orderUpdate.path("data").path("status").asText());
        Assertions.assertEquals("BTCUSDT", positionUpdate.path("data").path("symbol").asText());
        Assertions.assertEquals("OPEN", positionUpdate.path("data").path("status").asText());
        Assertions.assertEquals("OPEN", tradeCreated.path("data").path("tradeType").asText());
        Assertions.assertEquals(userId, accountUpdate.path("data").path("userId").asLong());
        Assertions.assertEquals(1, accountUpdate.path("data").path("openPositions").size());
        Assertions.assertTrue(ledgerCreated.path("data").hasNonNull("bizType"));

        tradingPositionMarginApplicationService.addIsolatedMargin(new AddIsolatedMarginCommand(
                userId,
                orderPlacementResult.position().positionId(),
                new BigDecimal("100.00000000")
        ));
        Map<String, JsonNode> marginUpdates = listener.awaitTypes(
                Set.of("margin.update", "position.update", "account.update", "ledger.created"),
                Duration.ofSeconds(5)
        );
        Assertions.assertEquals("margin", marginUpdates.get("margin.update").path("channel").asText());
        Assertions.assertEquals(0, marginUpdates.get("margin.update")
                .path("data")
                .path("position")
                .path("margin")
                .decimalValue()
                .compareTo(new BigDecimal("1100.00000000")));

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "test-finished").join();
    }

    private WebSocket connect(long userId, WebSocket.Listener listener) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .newWebSocketBuilder()
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Uid", "U" + userId)
                .header("X-User-Status", "ACTIVE")
                .header("X-Trace-Id", "trading-user-ws-test")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/v1/trading"), listener)
                .join();
    }

    private void publishQuote(String symbol,
                              BigDecimal bid,
                              BigDecimal ask,
                              BigDecimal mark,
                              OffsetDateTime ts) {
        quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                symbol,
                bid,
                ask,
                mark,
                mark,
                ts,
                "integration-test",
                false
        ));
    }

    private void seedAlwaysOpenSchedule(String symbol, String marketCode) {
        tradingScheduleSnapshotRepository.saveForTest(new TradingScheduleSnapshot(
                symbol,
                marketCode,
                alwaysOpenSessions(),
                List.of(),
                List.of(),
                OffsetDateTime.now()
        ));
    }

    private List<TradingSessionWindow> alwaysOpenSessions() {
        return List.of(
                new TradingSessionWindow(1, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                new TradingSessionWindow(2, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                new TradingSessionWindow(3, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                new TradingSessionWindow(4, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                new TradingSessionWindow(5, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                new TradingSessionWindow(6, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                new TradingSessionWindow(7, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null)
        );
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

        JsonNode awaitType(String type, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                String value = textFrames.poll(Math.max(remainingMillis, 1L), TimeUnit.MILLISECONDS);
                if (value == null) {
                    continue;
                }
                JsonNode root = OBJECT_MAPPER.readTree(value);
                if (type.equals(root.path("type").asText())) {
                    return root;
                }
            }
            Assertions.fail("未在超时内收到 trading WebSocket 消息 type=" + type);
            return OBJECT_MAPPER.nullNode();
        }

        Map<String, JsonNode> awaitTypes(Set<String> types, Duration timeout) throws Exception {
            Map<String, JsonNode> matched = new LinkedHashMap<>();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && matched.size() < types.size()) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                String value = textFrames.poll(Math.max(remainingMillis, 1L), TimeUnit.MILLISECONDS);
                if (value == null) {
                    continue;
                }
                JsonNode root = OBJECT_MAPPER.readTree(value);
                String type = root.path("type").asText();
                if (types.contains(type)) {
                    matched.putIfAbsent(type, root);
                }
            }
            Assertions.assertEquals(types, matched.keySet(), "trading WebSocket 未收到全部预期消息");
            return matched;
        }
    }
}
