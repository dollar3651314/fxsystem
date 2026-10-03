package com.falconx.trading.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.TradingCoreServiceApplication;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.config.TradingTraceContextFilter;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.repository.RedisTradingScheduleSnapshotRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.service.model.TradingHolidayRule;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import com.falconx.trading.service.model.TradingSessionWindow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 用户视角查询接口集成测试。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380"
        }
)
class TradingUserQueryControllerIntegrationTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TradingTraceContextFilter tradingTraceContextFilter;

    @Autowired
    private TradingAccountService tradingAccountService;

    @Autowired
    private TradingCoreServiceProperties tradingCoreServiceProperties;

    @Autowired
    private QuoteDrivenEngine quoteDrivenEngine;

    @Autowired
    private TradingTestSupportMapper tradingTestSupportMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTradingScheduleSnapshotRepository tradingScheduleSnapshotRepository;

    @Autowired
    private OpenPositionSnapshotStore openPositionSnapshotStore;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tradingTestSupportMapper.clearOwnerTables();
        openPositionSnapshotStore.replaceAll(List.of());
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:BTCUSDT");
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:ETHUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:ETHUSDT");
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(tradingTraceContextFilter)
                .build();
    }

    @Test
    void shouldListOrdersWithPagination() throws Exception {
        long userId = 32001L;
        seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "query-order-32001-1");
        seedAndOpenPosition(userId, "ETHUSDT", TradingOrderSide.SELL, "query-order-32001-2");
        seedAndOpenPosition(32002L, "BTCUSDT", TradingOrderSide.BUY, "query-order-32002-1");

        MockHttpServletResponse response = get("/api/v1/trading/orders", userId, 1, 1);
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());
        JsonNode items = root.path("data").path("items");

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertEquals(1, root.path("data").path("page").asInt());
        Assertions.assertEquals(1, root.path("data").path("pageSize").asInt());
        Assertions.assertEquals(2L, root.path("data").path("total").asLong());
        Assertions.assertEquals(1, items.size());
        Assertions.assertEquals("query-order-32001-2", items.get(0).path("clientOrderId").asText());
        Assertions.assertEquals("ETHUSDT", items.get(0).path("symbol").asText());
        Assertions.assertEquals("SELL", items.get(0).path("side").asText());
        Assertions.assertEquals("FILLED", items.get(0).path("status").asText());
        Assertions.assertTrue(items.get(0).hasNonNull("fee"));
    }

    @Test
    void shouldListTradesWithPagination() throws Exception {
        long userId = 32003L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "query-trade-32003-1");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );
        postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", userId);
        seedAndOpenPosition(32004L, "BTCUSDT", TradingOrderSide.BUY, "query-trade-32004-1");

        MockHttpServletResponse response = get("/api/v1/trading/trades", userId, 1, 10);
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());
        JsonNode items = root.path("data").path("items");

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertEquals(2L, root.path("data").path("total").asLong());
        Assertions.assertEquals(2, items.size());
        Assertions.assertEquals("CLOSE", items.get(0).path("tradeType").asText());
        Assertions.assertEquals("OPEN", items.get(1).path("tradeType").asText());
        Assertions.assertEquals(positionId, items.get(0).path("positionId").asLong());
        Assertions.assertTrue(items.get(0).hasNonNull("fee"));
        Assertions.assertTrue(items.get(0).hasNonNull("realizedPnl"));
    }

    @Test
    void shouldListPositionsWithPagination() throws Exception {
        long userId = 32005L;
        long closedPositionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "query-position-32005-1");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );
        postWithoutBody("/api/v1/trading/positions/" + closedPositionId + "/close", userId);
        long openPositionId = seedAndOpenPosition(userId, "ETHUSDT", TradingOrderSide.SELL, "query-position-32005-2");
        publishQuote(
                "ETHUSDT",
                new BigDecimal("9995.00000000"),
                new BigDecimal("10005.00000000"),
                new BigDecimal("10000.00000000"),
                OffsetDateTime.now()
        );
        seedAndOpenPosition(32006L, "BTCUSDT", TradingOrderSide.BUY, "query-position-32006-1");

        MockHttpServletResponse response = get("/api/v1/trading/positions", userId, 1, 10);
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());
        JsonNode items = root.path("data").path("items");

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertEquals(2L, root.path("data").path("total").asLong());
        Assertions.assertEquals(2, items.size());

        JsonNode openItem = findItemByLong(items, "positionId", openPositionId);
        JsonNode closedItem = findItemByLong(items, "positionId", closedPositionId);
        Assertions.assertEquals("OPEN", openItem.path("status").asText());
        Assertions.assertEquals("SELL", openItem.path("side").asText());
        Assertions.assertEquals("ISOLATED", openItem.path("marginMode").asText());
        Assertions.assertTrue(openItem.hasNonNull("markPrice"));
        // STAGE-14E1 Task 1（master §7.5）：单币 unrealizedPnl 硬切双币 + 元数据。
        Assertions.assertTrue(openItem.hasNonNull("unrealizedPnlInQuote"));
        Assertions.assertTrue(openItem.hasNonNull("unrealizedPnlInAccount"));
        Assertions.assertEquals("USDT", openItem.path("quoteCurrency").asText());
        Assertions.assertTrue(openItem.hasNonNull("fxRate"));
        Assertions.assertTrue(openItem.hasNonNull("isolatedMargin"));
        Assertions.assertTrue(openItem.hasNonNull("quoteStale"));
        Assertions.assertEquals("CLOSED", closedItem.path("status").asText());
        Assertions.assertEquals("MANUAL", closedItem.path("closeReason").asText());
        Assertions.assertTrue(closedItem.hasNonNull("closePrice"));
        Assertions.assertTrue(closedItem.hasNonNull("realizedPnl"));
    }

    @Test
    void shouldListOpenPositionWithQuoteStaleAndLastEffectivePricePnl() throws Exception {
        long userId = 32012L;
        long openPositionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "query-position-stale-32012");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now().minusSeconds(10)
        );

        MockHttpServletResponse response = get("/api/v1/trading/positions", userId, 1, 20);
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());
        JsonNode items = root.path("data").path("items");
        JsonNode openItem = findItemByLong(items, "positionId", openPositionId);

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertEquals("OPEN", openItem.path("status").asText());
        Assertions.assertTrue(openItem.path("quoteStale").asBoolean());
        Assertions.assertEquals(0, openItem.path("markPrice").decimalValue().compareTo(new BigDecimal("9990.00000000")));
        // STAGE-14E1 Task 1：BTCUSDT 同币种（QC=USDT=AC）→ inQuote==inAccount==-10，fxRate=1。
        Assertions.assertEquals(0, openItem.path("unrealizedPnlInQuote").decimalValue().compareTo(new BigDecimal("-10.00000000")));
        Assertions.assertEquals(0, openItem.path("unrealizedPnlInAccount").decimalValue().compareTo(new BigDecimal("-10.00000000")));
        Assertions.assertEquals(0, openItem.path("fxRate").decimalValue().compareTo(BigDecimal.ONE));
    }

    @Test
    void shouldListLedgerEntriesWithPagination() throws Exception {
        long userId = 32007L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "query-ledger-32007-1");
        post("/api/v1/trading/positions/" + positionId + "/margin", """
                {
                  "amount": 200.0
                }
                """, userId);
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );
        postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", userId);
        seedAndOpenPosition(32008L, "BTCUSDT", TradingOrderSide.BUY, "query-ledger-32008-1");

        MockHttpServletResponse response = get("/api/v1/trading/ledger", userId, 1, 20);
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());
        JsonNode items = root.path("data").path("items");

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertEquals(tradingTestSupportMapper.countLedgerByUserId(userId).longValue(), root.path("data").path("total").asLong());
        Assertions.assertTrue(items.isArray());
        Assertions.assertTrue(items.size() >= 5);
        Set<String> bizTypes = streamTexts(items, "bizType");
        Assertions.assertTrue(bizTypes.contains("DEPOSIT_CREDIT"));
        Assertions.assertTrue(bizTypes.contains("ORDER_FEE_CHARGED"));
        Assertions.assertTrue(bizTypes.contains("ISOLATED_MARGIN_SUPPLEMENT"));
        Assertions.assertTrue(bizTypes.contains("REALIZED_PNL"));
        Assertions.assertEquals("REALIZED_PNL", items.get(0).path("bizType").asText());
        Assertions.assertTrue(items.get(0).hasNonNull("referenceNo"));
    }

    @Test
    void shouldListLiquidationsWithPagination() throws Exception {
        long userId = 32009L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "query-liquidation-32009-1");
        BigDecimal liquidationPrice = new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId));
        publishQuote(
                "BTCUSDT",
                liquidationPrice.subtract(new BigDecimal("5.00000000")),
                liquidationPrice.add(new BigDecimal("5.00000000")),
                liquidationPrice,
                OffsetDateTime.now()
        );
        seedAndOpenPosition(32010L, "BTCUSDT", TradingOrderSide.BUY, "query-liquidation-32010-1");

        MockHttpServletResponse response = get("/api/v1/trading/liquidations", userId, 1, 20);
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());
        JsonNode items = root.path("data").path("items");

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertEquals(1L, root.path("data").path("total").asLong());
        Assertions.assertEquals(1, items.size());
        Assertions.assertEquals(positionId, items.get(0).path("positionId").asLong());
        Assertions.assertEquals("BTCUSDT", items.get(0).path("symbol").asText());
        Assertions.assertEquals("BUY", items.get(0).path("side").asText());
        Assertions.assertEquals("ISOLATED", items.get(0).path("marginMode").asText());
        Assertions.assertTrue(items.get(0).hasNonNull("platformCoveredLoss"));
        Assertions.assertTrue(items.get(0).hasNonNull("markPrice"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/trading/orders",
            "/api/v1/trading/trades",
            "/api/v1/trading/positions",
            "/api/v1/trading/ledger",
            "/api/v1/trading/liquidations"
    })
    void shouldRejectInvalidPaginationForUserQueryEndpoints(String path) throws Exception {
        MockHttpServletResponse response = get(path, 32011L, 0, 200);
        String body = response.getContentAsString();

        Assertions.assertEquals(400, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"99004\""));
        Assertions.assertTrue(body.contains("\"message\":\"invalid request payload\""));
    }

    private MockHttpServletResponse get(String path, long userId, int page, int pageSize) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .header("X-User-Id", String.valueOf(userId))
                        .param("page", String.valueOf(page))
                        .param("pageSize", String.valueOf(pageSize)))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse post(String path, String body, long userId) throws Exception {
        MvcResult mvcResult = mockMvc.perform(MockMvcRequestBuilders.post(path)
                        .header("X-User-Id", String.valueOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        return mvcResult.getResponse();
    }

    private MockHttpServletResponse postWithoutBody(String path, long userId) throws Exception {
        MockHttpServletRequestBuilder requestBuilder = MockMvcRequestBuilders.post(path)
                .header("X-User-Id", String.valueOf(userId));
        return mockMvc.perform(requestBuilder)
                .andReturn()
                .getResponse();
    }

    private long seedAndOpenPosition(long userId,
                                     String symbol,
                                     TradingOrderSide side,
                                     String clientOrderId) throws Exception {
        tradingAccountService.creditDeposit(
                userId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("2000.00000000"),
                "it-credit-" + clientOrderId,
                "seed-" + clientOrderId,
                OffsetDateTime.now()
        );
        seedAlwaysOpenSchedule(symbol, "CRYPTO");
        publishQuote(
                symbol,
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now()
        );
        MockHttpServletResponse response = post("/api/v1/trading/orders/market", """
                {
                  "symbol": "%s",
                  "side": "%s",
                  "quantity": 1.0,
                  "leverage": 10,
                  "takeProfitPrice": %s,
                  "stopLossPrice": %s,
                  "clientOrderId": "%s"
                }
                """.formatted(
                symbol,
                side.name(),
                side == TradingOrderSide.BUY ? "10100.0" : "9800.0",
                side == TradingOrderSide.BUY ? "9800.0" : "10100.0",
                clientOrderId
        ), userId);
        Assertions.assertTrue(response.getContentAsString().contains("\"code\":\"0\""));
        Long positionId = tradingTestSupportMapper.selectLatestPositionIdByUserId(userId);
        Assertions.assertNotNull(positionId);
        return positionId;
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

    @SuppressWarnings("unused")
    private void seedHolidayClosedSchedule(String symbol, String marketCode) {
        tradingScheduleSnapshotRepository.saveForTest(new TradingScheduleSnapshot(
                symbol,
                marketCode,
                alwaysOpenSessions(),
                List.of(),
                List.of(new TradingHolidayRule(
                        marketCode,
                        OffsetDateTime.now(ZoneOffset.UTC).toLocalDate(),
                        1,
                        null,
                        null,
                        "UTC",
                        "Integration Holiday",
                        "INT"
                )),
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

    private JsonNode findItemByLong(JsonNode items, String fieldName, long expectedValue) {
        for (JsonNode item : items) {
            if (item.path(fieldName).asLong() == expectedValue) {
                return item;
            }
        }
        throw new IllegalStateException("Unable to find item for " + fieldName + "=" + expectedValue);
    }

    private Set<String> streamTexts(JsonNode items, String fieldName) {
        return StreamSupport.stream(items.spliterator(), false)
                .map(item -> item.path(fieldName).asText())
                .collect(java.util.stream.Collectors.toSet());
    }
}
