package com.falconx.trading.websocket;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.TradingCoreServiceApplication;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.repository.RedisTradingScheduleSnapshotRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.trading.service.FxRateService;
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
import java.util.ArrayList;
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
 * STAGE-14E1 Task 3（master §7.5 WebSocket 最终 break）：WS 推送链路端到端 IT —— 验证
 * Task 1（position.update / position.pnl 双币 + 元数据）与 Task 2（account.update 内嵌持仓双币 +
 * 账户级 marginLevel）真正经【真 WebSocket 连接 + 真 DB/Redis + 真推送 harness】落到推送帧上。
 *
 * <p>harness 复用 {@link TradingUserWebSocketIntegrationTests}：JDK {@link HttpClient} 真连
 * {@code ws://localhost:{port}/ws/v1/trading}（非同进程 payload 校验），订阅 account/positions，
 * 在真 MySQL（{@code falconx_trading_it}）/ Redis / FxRateService 上开 EURAUD（QC=AUD，AC=USDT，
 * AUD→USDT=0.65）ISOLATED 仓，驱动 tick，断言推送帧字段。
 *
 * <p>断言要点（master §7.5 break 口径）：
 * <ul>
 *   <li>position.update / position.pnl 帧含 {@code quoteCurrency / fxRate / unrealizedPnlInQuote /
 *       unrealizedPnlInAccount / isolatedMargin}，且【无】旧单币字段 {@code unrealizedPnl}；</li>
 *   <li>EURAUD 异币种：quoteCurrency=AUD、fxRate=0.65（≠1），unrealizedPnlInAccount=inQuote×0.65；</li>
 *   <li>ISOLATED 仓 isolatedMargin 非 null（=position.margin）；</li>
 *   <li>account.update 帧含 {@code equity / marginLevel / marginLevelStatus / marginMode}，
 *       openPositions[0] 双币（同 position 帧口径）。</li>
 * </ul>
 *
 * <p>position.pnl 节流：{@link PositionPnlPushThrottler} 按 symbol 100ms。本 IT 在开仓 tick 之后
 * 等待 >100ms 再驱动第二条 tick 以越过节流窗口，并在多秒 await 窗口内轮询接收 position.pnl 帧。
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
class TradingWsBreakFieldsIntegrationTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SYMBOL_SPEC_KEY_PREFIX = "falconx:market:symbol-spec:";

    /** EURAUD 计价币 AUD（master §3.3）；0.1 lot=10000 EUR。 */
    private static final String EURAUD = "EURAUD";
    private static final BigDecimal LOT_0_1_EUR = new BigDecimal("10000.00000000");
    private static final BigDecimal LEV_200 = new BigDecimal("200");
    /** master §3.3：AUDUSDT=0.6500，即 AUD→USDT rate 0.65。 */
    private static final BigDecimal AUD_USDT_RATE = new BigDecimal("0.65000000");

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
    private FxRateService fxRateService;

    @BeforeEach
    void setUp() {
        tradingTestSupportMapper.clearOwnerTables();
        openPositionSnapshotStore.replaceAll(List.of());
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:" + EURAUD);
        stringRedisTemplate.delete("falconx:market:trading:schedule:" + EURAUD);
        // EURAUD SymbolSpec：base=EUR / quote=AUD / maxLev=200 / feeRate=0（QC=AUD 触发双币换算路径）。
        seedSymbolSpec(EURAUD, "EUR", "AUD", 200, BigDecimal.ZERO);
        // FX：AUD→USDT=0.65（生产 Kafka 增量同一写入口 acceptUpdate）。
        fxRateService.acceptUpdate("AUD", "USDT", AUD_USDT_RATE, System.currentTimeMillis());
    }

    /**
     * 端到端：开 EURAUD ISOLATED 仓 → 真 WS 收 account.snapshot/account.update/position.update（含双币 +
     * 账户级 marginLevel），再驱动 tick → position.pnl（双币 + 无旧 unrealizedPnl）。
     */
    @Test
    void shouldPushDualCurrencyPositionAndMarginLevelAccountFieldsOverWebSocket() throws Exception {
        long userId = 34001L;
        TextFrameListener listener = new TextFrameListener();
        WebSocket socket = connect(userId, listener);

        // 初始快照（account.snapshot）即应带 marginLevel 字段族（无持仓时 marginLevel=null、status=HEALTHY）。
        JsonNode snapshot = listener.awaitType("account.snapshot", Duration.ofSeconds(5));
        Assertions.assertEquals(userId, snapshot.path("data").path("userId").asLong());
        JsonNode snapData = snapshot.path("data");
        Assertions.assertTrue(snapData.has("equity"), "account.snapshot 应含 equity 字段");
        Assertions.assertTrue(snapData.has("marginLevel"), "account.snapshot 应含 marginLevel 字段");
        Assertions.assertTrue(snapData.has("marginLevelStatus"), "account.snapshot 应含 marginLevelStatus 字段");
        Assertions.assertTrue(snapData.has("marginMode"), "account.snapshot 应含 marginMode 字段");
        // 无持仓：marginLevel=null，marginLevelStatus=HEALTHY（MarginLevelMonitor 约定）。
        Assertions.assertTrue(snapData.path("marginLevel").isNull(), "无持仓 marginLevel 应为 null");
        Assertions.assertEquals("HEALTHY", snapData.path("marginLevelStatus").asText());

        socket.sendText("""
                {"type":"subscribe","requestId":"sub-e1","channels":["account","orders","positions","trades","margin","ledger"]}
                """, true).join();
        listener.awaitType("subscribed", Duration.ofSeconds(5));

        // 入金 + always-open schedule + 开仓前 tick（写 quote snapshot，开仓需活动报价）。
        tradingAccountService.creditDeposit(
                userId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("1000.00000000"),
                "ws-e1-credit-34001",
                "seed-ws-e1-34001",
                OffsetDateTime.now()
        );
        seedAlwaysOpenSchedule(EURAUD, "FX");
        publishQuote(EURAUD, new BigDecimal("1.64980000"), new BigDecimal("1.65000000"), new BigDecimal("1.65000000"));

        // 开 EURAUD BUY 0.1lot 200x ISOLATED（不传 marginMode → 账户默认 ISOLATED）。
        OrderPlacementResult result = tradingOrderPlacementApplicationService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId,
                EURAUD,
                TradingOrderSide.BUY,
                LOT_0_1_EUR,
                LEV_200,
                null,
                null,
                null,
                "ws-e1-order-34001"
        ));
        Assertions.assertNotNull(result.position(), "EURAUD 开仓应成功：" + result.rejectionReason());

        Map<String, JsonNode> updates = listener.awaitTypes(
                Set.of("order.update", "position.update", "account.update"),
                Duration.ofSeconds(5)
        );

        // ---- position.update：双币 + 元数据，无旧 unrealizedPnl ----
        JsonNode posData = updates.get("position.update").path("data");
        Assertions.assertEquals(EURAUD, posData.path("symbol").asText());
        Assertions.assertEquals("OPEN", posData.path("status").asText());
        assertDualCurrencyPositionFrame(posData, "position.update");

        // ---- account.update：账户级 marginLevel 字段族 + openPositions[0] 双币 ----
        JsonNode acctData = updates.get("account.update").path("data");
        Assertions.assertEquals(userId, acctData.path("userId").asLong());
        Assertions.assertTrue(acctData.has("equity"), "account.update 应含 equity");
        Assertions.assertTrue(acctData.hasNonNull("equity"), "有持仓时 equity 应非 null（FX 可用）");
        Assertions.assertTrue(acctData.has("marginLevel"), "account.update 应含 marginLevel");
        Assertions.assertTrue(acctData.hasNonNull("marginLevel"), "有持仓时 marginLevel 应非 null（FX 可用）");
        Assertions.assertTrue(acctData.has("marginLevelStatus"), "account.update 应含 marginLevelStatus");
        Assertions.assertEquals("HEALTHY", acctData.path("marginLevelStatus").asText(), "健康账户 status=HEALTHY");
        Assertions.assertTrue(acctData.has("marginMode"), "account.update 应含 marginMode");
        Assertions.assertEquals(1, acctData.path("openPositions").size());
        JsonNode embedded = acctData.path("openPositions").get(0);
        Assertions.assertEquals(EURAUD, embedded.path("symbol").asText());
        assertDualCurrencyPositionFrame(embedded, "account.update.openPositions[0]");
        Assertions.assertEquals("ISOLATED", embedded.path("marginMode").asText());

        // ---- 驱动第二条 tick（越过 100ms 节流窗口）→ position.pnl 双币帧 ----
        // 越过 PositionPnlPushThrottler 的 100ms 窗口：sleep 120ms 后再推，且后续 await 窗口内可能多 tick。
        Thread.sleep(150L);
        publishQuote(EURAUD, new BigDecimal("1.65980000"), new BigDecimal("1.66000000"), new BigDecimal("1.66000000"));
        JsonNode pnl = awaitTypeWithRetryTick(listener, "position.pnl", userId);
        JsonNode pnlData = pnl.path("data");
        Assertions.assertEquals(EURAUD, pnlData.path("symbol").asText());
        Assertions.assertEquals(result.position().positionId(), pnlData.path("positionId").asLong());
        assertDualPnlFrame(pnlData, "position.pnl");
        // BUY 在更高 mark（bid 1.6598 > entry 1.65）→ inQuote 为正盈利，AC=inQuote×0.65。
        Assertions.assertTrue(pnlData.hasNonNull("unrealizedPnlInQuote"));
        BigDecimal inQuote = pnlData.path("unrealizedPnlInQuote").decimalValue();
        BigDecimal inAccount = pnlData.path("unrealizedPnlInAccount").decimalValue();
        Assertions.assertTrue(inQuote.signum() > 0, "BUY 浮盈 inQuote 应为正");
        BigDecimal expectedAccount = inQuote.multiply(AUD_USDT_RATE).setScale(8, java.math.RoundingMode.HALF_UP);
        Assertions.assertEquals(0, inAccount.compareTo(expectedAccount), "unrealizedPnlInAccount=inQuote×0.65");

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "test-finished").join();
    }

    /** position.update / account.update.openPositions 帧的双币断言（带 markPrice，含 entryPrice）。 */
    private void assertDualCurrencyPositionFrame(JsonNode data, String where) {
        assertDualPnlFrame(data, where);
        Assertions.assertTrue(data.hasNonNull("entryPrice"), where + " 应含 entryPrice");
        // ISOLATED 仓：isolatedMargin 非 null（= position.margin）。
        Assertions.assertTrue(data.hasNonNull("isolatedMargin"), where + " ISOLATED 仓 isolatedMargin 应非 null");
    }

    /** 双币浮盈亏字段族断言 + 旧单币字段不存在 + EURAUD 异币种 QC/fx 真值。 */
    private void assertDualPnlFrame(JsonNode data, String where) {
        Assertions.assertFalse(data.has("unrealizedPnl"),
                where + " 不应再含旧单币字段 unrealizedPnl（master §7.5 break）");
        Assertions.assertTrue(data.has("quoteCurrency"), where + " 应含 quoteCurrency");
        Assertions.assertTrue(data.has("fxRate"), where + " 应含 fxRate");
        Assertions.assertTrue(data.has("unrealizedPnlInQuote"), where + " 应含 unrealizedPnlInQuote");
        Assertions.assertTrue(data.has("unrealizedPnlInAccount"), where + " 应含 unrealizedPnlInAccount");
        Assertions.assertTrue(data.has("isolatedMargin"), where + " 应含 isolatedMargin");
        // EURAUD QC=AUD、fx=0.65（≠1，异币种换算真值）。
        Assertions.assertEquals("AUD", data.path("quoteCurrency").asText(), where + " EURAUD QC 应为 AUD");
        Assertions.assertTrue(data.hasNonNull("fxRate"), where + " 异币种 fxRate 应非 null");
        Assertions.assertEquals(0, data.path("fxRate").decimalValue().compareTo(AUD_USDT_RATE),
                where + " EURAUD fxRate 应为 0.65");
    }

    /**
     * position.pnl 受 symbol 100ms 节流：若首条 await 未命中（节流吞掉），间隔再补推一条 tick 重试，
     * 最多 3 轮（每轮 sleep>100ms + tick + await 2s），避免节流窗口竞态导致偶发 flake。
     */
    private JsonNode awaitTypeWithRetryTick(TextFrameListener listener, String type, long userId) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            JsonNode frame = listener.tryAwaitType(type, Duration.ofSeconds(2));
            if (frame != null) {
                return frame;
            }
            Thread.sleep(150L);
            BigDecimal bid = new BigDecimal("1.6598").add(new BigDecimal("0.0001").multiply(BigDecimal.valueOf(attempt + 1)));
            publishQuote(EURAUD, bid, bid.add(new BigDecimal("0.0002")), bid.add(new BigDecimal("0.0001")));
        }
        Assertions.fail("未在重试窗口内收到 position.pnl 帧 userId=" + userId);
        return OBJECT_MAPPER.nullNode();
    }

    // ========================= harness =========================

    private WebSocket connect(long userId, WebSocket.Listener listener) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .newWebSocketBuilder()
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Uid", "U" + userId)
                .header("X-User-Status", "ACTIVE")
                .header("X-Trace-Id", "trading-ws-break-test")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/v1/trading"), listener)
                .join();
    }

    private void publishQuote(String symbol, BigDecimal bid, BigDecimal ask, BigDecimal mark) {
        quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                symbol, bid, ask, mark, mark, OffsetDateTime.now(), "ws-break-it", false));
    }

    private void seedSymbolSpec(String symbol, String base, String quote, int maxLeverage, BigDecimal feeRate) {
        Integer category = ("BTC".equals(base) || "ETH".equals(base) || "XRP".equals(base)) ? 1 : 2;
        SymbolSpec spec = new SymbolSpec(
                symbol, maxLeverage, feeRate, BigDecimal.ZERO,
                new BigDecimal("0.00000001"), new BigDecimal("1000000000"), BigDecimal.ZERO,
                5, 8, base, quote, category);
        try {
            stringRedisTemplate.opsForValue().set(SYMBOL_SPEC_KEY_PREFIX + symbol,
                    OBJECT_MAPPER.writeValueAsString(spec));
        } catch (Exception e) {
            throw new IllegalStateException("seed symbol spec failed: " + symbol, e);
        }
    }

    private void seedAlwaysOpenSchedule(String symbol, String marketCode) {
        List<TradingSessionWindow> windows = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            windows.add(new TradingSessionWindow(day, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59),
                    "UTC", true, LocalDate.of(2026, 1, 1), null));
        }
        tradingScheduleSnapshotRepository.saveForTest(new TradingScheduleSnapshot(
                symbol, marketCode, windows, List.of(), List.of(), OffsetDateTime.now()));
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
            JsonNode frame = tryAwaitType(type, timeout);
            if (frame == null) {
                Assertions.fail("未在超时内收到 trading WebSocket 消息 type=" + type);
                return OBJECT_MAPPER.nullNode();
            }
            return frame;
        }

        JsonNode tryAwaitType(String type, Duration timeout) throws Exception {
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
            return null;
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
