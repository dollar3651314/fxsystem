package com.falconx.trading.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.TradingCoreServiceApplication;
import com.falconx.trading.application.TradingPositionCloseApplicationService;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.config.TradingTraceContextFilter;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.dto.PositionCloseResult;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingRiskSwitch;
import com.falconx.trading.repository.RedisTradingRiskSwitchCache;
import com.falconx.trading.repository.RedisTradingScheduleSnapshotRepository;
import com.falconx.trading.repository.RedisTradingSwapRateSnapshotRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.service.TradingSwapSettlementService;
import com.falconx.trading.service.model.TradingHolidayRule;
import com.falconx.trading.service.model.TradingHoursExceptionRule;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import com.falconx.trading.service.model.TradingSessionWindow;
import com.falconx.trading.service.model.TradingSwapRateRule;
import com.falconx.trading.service.model.TradingSwapRateSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * trading-core-service 控制器集成测试。
 *
 * <p>该测试覆盖 Stage 5 真实基础设施下的最小交易北向接口，
 * 验证账户查询、市价单受理和统一错误响应可以通过 HTTP 层正常工作。
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
                "spring.data.redis.port=6380",
                "falconx.trading.swap.enabled=false"
        }
)
@ExtendWith(OutputCaptureExtension.class)
class TradingControllerIntegrationTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TradingTraceContextFilter tradingTraceContextFilter;

    @Autowired
    private TradingAccountService tradingAccountService;

    @Autowired
    private QuoteDrivenEngine quoteDrivenEngine;

    @Autowired
    private TradingCoreServiceProperties tradingCoreServiceProperties;

    @Autowired
    private TradingPositionCloseApplicationService tradingPositionCloseApplicationService;

    @Autowired
    private TradingTestSupportMapper tradingTestSupportMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTradingScheduleSnapshotRepository tradingScheduleSnapshotRepository;

    @Autowired
    private RedisTradingSwapRateSnapshotRepository tradingSwapRateSnapshotRepository;

    @Autowired
    private OpenPositionSnapshotStore openPositionSnapshotStore;

    @Autowired
    private TradingSwapSettlementService tradingSwapSettlementService;

    @Autowired
    private RedisTradingRiskSwitchCache riskSwitchCache;

    private MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        // 确保 cross_mode.enabled 在每个测试结束后恢复 false，防止污染其它测试。
        riskSwitchCache.write(new TradingRiskSwitch(
                TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false, "IT", "tc-it-teardown", null, null));
    }

    @BeforeEach
    void setUp() {
        tradingTestSupportMapper.clearOwnerTables();
        openPositionSnapshotStore.replaceAll(List.of());
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:BTCUSDT");
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:ETHUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:ETHUSDT");
        stringRedisTemplate.delete("falconx:market:swap-rate:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:swap-rate:ETHUSDT");
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(tradingTraceContextFilter)
                .build();
    }

    @Test
    void shouldReturnCurrentAccountSnapshot() throws Exception {
        long userId = 31001L;
        tradingAccountService.creditDeposit(
                userId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("1500.00000000"),
                "it-credit-31001",
                "seed-31001",
                OffsetDateTime.now()
        );
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now(),
                "integration-test",
                false
        ));
        post("/api/v1/trading/orders/market", """
                {
                  "symbol": "BTCUSDT",
                  "side": "BUY",
                  "quantity": 1.0,
                  "leverage": 10,
                  "takeProfitPrice": 10200.0,
                  "stopLossPrice": 9800.0,
                  "clientOrderId": "integration-order-31001"
                }
                """, userId);

        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/trading/accounts/me")
                        .header("X-User-Id", String.valueOf(userId)))
                .andReturn()
                .getResponse();
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertNotNull(response.getHeader("X-Trace-Id"));
        Assertions.assertTrue(body.contains("\"code\":\"0\""));
        Assertions.assertTrue(body.contains("\"currency\":\"USDT\""));
        Assertions.assertTrue(body.contains("\"openPositions\""));
        // STAGE-14E1 Task 2：openPositions 硬切双币（删 unrealizedPnl）。BTCUSDT QC=USDT 同结算币 →
        // fxRate=1、inQuote==inAccount==-10。
        Assertions.assertTrue(body.contains("\"unrealizedPnlInQuote\":-10.00000000"));
        Assertions.assertTrue(body.contains("\"unrealizedPnlInAccount\":-10.00000000"));
        Assertions.assertFalse(body.contains("\"unrealizedPnl\":"), "单币 unrealizedPnl 已硬切删除");
        Assertions.assertTrue(body.contains("\"marginMode\":\"ISOLATED\""));
        Assertions.assertTrue(body.contains("\"takeProfitPrice\":10200"));
    }

    @Test
    void shouldReturnStaleQuoteFlagAndLastEffectivePricePnlInAccountSnapshot() throws Exception {
        long userId = 31032L;
        tradingAccountService.creditDeposit(
                userId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("1500.00000000"),
                "it-credit-31032",
                "seed-31032",
                OffsetDateTime.now()
        );
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now(),
                "integration-test-fresh-account",
                false
        ));
        post("/api/v1/trading/orders/market", """
                {
                  "symbol": "BTCUSDT",
                  "side": "BUY",
                  "quantity": 1.0,
                  "leverage": 10,
                  "takeProfitPrice": 10200.0,
                  "stopLossPrice": 9800.0,
                  "clientOrderId": "integration-order-31032"
                }
                """, userId);
        quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now().minusSeconds(10),
                "integration-test-stale-account",
                false
        ));

        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/trading/accounts/me")
                        .header("X-User-Id", String.valueOf(userId)))
                .andReturn()
                .getResponse();
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());
        JsonNode openPosition = root.path("data").path("openPositions").get(0);

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertTrue(openPosition.path("quoteStale").asBoolean());
        Assertions.assertEquals(0, openPosition.path("markPrice").decimalValue().compareTo(new BigDecimal("9990.00000000")));
        // STAGE-14E1 Task 2：双币（删 unrealizedPnl）。BTCUSDT QC=USDT 同结算币 → inQuote==inAccount==-10。
        Assertions.assertEquals(0, openPosition.path("unrealizedPnlInQuote").decimalValue().compareTo(new BigDecimal("-10.00000000")));
        Assertions.assertEquals(0, openPosition.path("unrealizedPnlInAccount").decimalValue().compareTo(new BigDecimal("-10.00000000")));
        Assertions.assertTrue(openPosition.path("unrealizedPnl").isMissingNode(), "单币 unrealizedPnl 已硬切删除");
        Assertions.assertEquals("ISOLATED", openPosition.path("marginMode").asText());
    }

    @Test
    void shouldPlaceFilledMarketOrderSuccessfully(CapturedOutput output) throws Exception {
        long userId = 31002L;
        tradingAccountService.creditDeposit(
                userId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("2000.00000000"),
                "it-credit-31002",
                "seed-31002",
                OffsetDateTime.now()
        );
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now(),
                "integration-test",
                false
        ));

        MockHttpServletResponse response = post("/api/v1/trading/orders/market", """
                {
                  "symbol": "BTCUSDT",
                  "side": "BUY",
                  "quantity": 1.0,
                  "leverage": 10,
                  "takeProfitPrice": 10100.0,
                  "stopLossPrice": 9800.0,
                  "clientOrderId": "integration-order-31002"
                }
                """, userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"0\""));
        Assertions.assertTrue(body.contains("\"orderStatus\":\"FILLED\""));
        Assertions.assertTrue(body.contains("\"symbol\":\"BTCUSDT\""));
        Assertions.assertTrue(body.contains("\"positionId\":"));
        Assertions.assertTrue(body.contains("\"marginMode\":\"ISOLATED\""));
        Assertions.assertTrue(body.contains("\"marginUsed\":1000.00000000"));
        Assertions.assertTrue(body.contains("\"takeProfitPrice\":10100.0"));
        Assertions.assertTrue(body.contains("\"stopLossPrice\":9800.0"));
        Assertions.assertNotNull(response.getHeader("X-Trace-Id"));
        Assertions.assertTrue(output.toString().contains("trading.order.received userId=31002 symbol=BTCUSDT clientOrderId=integration-order-31002"));
        Assertions.assertTrue(output.toString().contains("trading.order.filled userId=31002"));
        Assertions.assertTrue(output.toString().contains("traceId="));
    }

    @Test
    void shouldPlaceFilledMarketOrderWhenMarginModeExplicitlyIsolated() throws Exception {
        long userId = 31034L;
        tradingAccountService.creditDeposit(
                userId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("2000.00000000"),
                "it-credit-31034",
                "seed-31034",
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

        MockHttpServletResponse response = post("/api/v1/trading/orders/market", """
                {
                  "symbol": "BTCUSDT",
                  "side": "BUY",
                  "quantity": 1.0,
                  "leverage": 10,
                  "marginMode": "ISOLATED",
                  "takeProfitPrice": 10100.0,
                  "stopLossPrice": 9800.0,
                  "clientOrderId": "integration-order-31034"
                }
                """, userId);
        Long positionId = tradingTestSupportMapper.selectLatestPositionIdByUserId(userId);

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(response.getContentAsString().contains("\"code\":\"0\""));
        Assertions.assertTrue(response.getContentAsString().contains("\"marginMode\":\"ISOLATED\""));
        Assertions.assertEquals(2, tradingTestSupportMapper.selectPositionMarginModeCodeById(positionId));
    }

    @Test
    void shouldReturnCrossModeNotEnabledWhenCrossModeGateClosed() throws Exception {
        // STAGE-14E0：D1 后 marginMode=CROSS 命中 cross_mode 门控(关闭)，返 30088，不再是 40010。
        long userId = 31035L;
        // 明确关闭门控（不依赖 Redis 环境状态，防止跨 run 污染）。
        riskSwitchCache.write(new TradingRiskSwitch(
                TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false, "IT", "e0-gate-closed", null, null));
        tradingAccountService.creditDeposit(
                userId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("2000.00000000"),
                "it-credit-31035",
                "seed-31035",
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

        MockHttpServletResponse response = post("/api/v1/trading/orders/market", """
                {
                  "symbol": "BTCUSDT",
                  "side": "BUY",
                  "quantity": 1.0,
                  "leverage": 10,
                  "marginMode": "CROSS",
                  "clientOrderId": "integration-order-31035"
                }
                """, userId);

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(response.getContentAsString().contains("\"code\":\"30088\""));
        Assertions.assertTrue(response.getContentAsString().contains("\"message\":\"Cross Mode Not Enabled\""));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOrdersByUserId(userId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByUserId(userId));
    }

    @Test
    void shouldAllowCrossOrderWhenCrossModeGateOpen() throws Exception {
        // STAGE-14E0：cross_mode.enabled=true → CROSS 市价单正常 FILLED，1 个 OPEN 仓位创建。
        long userId = 31036L;
        // 明确开启门控。
        riskSwitchCache.write(new TradingRiskSwitch(
                TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, true, "IT", "e0-gate-open", null, null));
        tradingAccountService.creditDeposit(
                userId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("2000.00000000"),
                "it-credit-31036",
                "seed-31036",
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

        MockHttpServletResponse response = post("/api/v1/trading/orders/market", """
                {
                  "symbol": "BTCUSDT",
                  "side": "BUY",
                  "quantity": 1.0,
                  "leverage": 10,
                  "marginMode": "CROSS",
                  "clientOrderId": "integration-order-31036"
                }
                """, userId);

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(response.getContentAsString().contains("\"code\":\"0\""));
        Assertions.assertTrue(response.getContentAsString().contains("\"orderStatus\":\"FILLED\""));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        // @AfterEach tearDown 负责恢复 cross_mode.enabled=false。
    }

    @Test
    void shouldRejectMarketOrderWhenUserOpenPositionLimitReached() throws Exception {
        long userId = 31040L;
        tradingTestSupportMapper.updateRiskConfigPositionLimits(
                "BTCUSDT",
                new BigDecimal("1.00000000"),
                new BigDecimal("1000.00000000")
        );
        seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-risk-limit-user-31040-1");
        tradingAccountService.creditDeposit(
                userId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("2000.00000000"),
                "it-credit-risk-limit-user-31040",
                "seed-risk-limit-user-31040",
                OffsetDateTime.now()
        );

        MockHttpServletResponse response = post("/api/v1/trading/orders/market", """
                {
                  "symbol": "BTCUSDT",
                  "side": "BUY",
                  "quantity": 1.0,
                  "leverage": 10,
                  "clientOrderId": "integration-risk-limit-user-31040-2"
                }
                """, userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"30005\""));
        Assertions.assertTrue(body.contains("\"message\":\"Position Limit Reached\""));
        Assertions.assertTrue(body.contains("\"rejectionReason\":\"POSITION_LIMIT_REACHED\""));
        Assertions.assertEquals(2, tradingTestSupportMapper.countOrdersByUserId(userId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countTradesByUserId(userId));
    }

    @Test
    void shouldRejectMarketOrderWhenPlatformOpenPositionLimitReached() throws Exception {
        long firstUserId = 31041L;
        long secondUserId = 31042L;
        tradingTestSupportMapper.updateRiskConfigPositionLimits(
                "BTCUSDT",
                new BigDecimal("1000.00000000"),
                new BigDecimal("1.00000000")
        );
        seedAndOpenPosition(firstUserId, "BTCUSDT", TradingOrderSide.BUY, "integration-risk-limit-platform-31041");
        tradingAccountService.creditDeposit(
                secondUserId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("2000.00000000"),
                "it-credit-risk-limit-platform-31042",
                "seed-risk-limit-platform-31042",
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

        MockHttpServletResponse response = post("/api/v1/trading/orders/market", """
                {
                  "symbol": "BTCUSDT",
                  "side": "BUY",
                  "quantity": 1.0,
                  "leverage": 10,
                  "clientOrderId": "integration-risk-limit-platform-31042"
                }
                """, secondUserId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"30006\""));
        Assertions.assertTrue(body.contains("\"message\":\"Platform Position Limit Reached\""));
        Assertions.assertTrue(body.contains("\"rejectionReason\":\"PLATFORM_POSITION_LIMIT_REACHED\""));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOrdersByUserId(secondUserId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOpenPositionsByUserId(secondUserId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByUserId(secondUserId));
    }

    @Test
    void shouldReturnRejectedBusinessCodeWhenQuoteIsStale(CapturedOutput output) throws Exception {
        long userId = 31003L;
        tradingAccountService.creditDeposit(
                userId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("2000.00000000"),
                "it-credit-31003",
                "seed-31003",
                OffsetDateTime.now()
        );
        seedAlwaysOpenSchedule("ETHUSDT", "CRYPTO");
        // stale 现在按“读取时当前时间 - quote.ts”动态计算，因此这里必须写入一个已过期的时间戳，
        // 不能再依赖 payload 自带的 `stale=true` 静态提示位。
        quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                "ETHUSDT",
                new BigDecimal("1990.00000000"),
                new BigDecimal("2000.00000000"),
                new BigDecimal("1995.00000000"),
                new BigDecimal("1995.00000000"),
                OffsetDateTime.now().minusSeconds(10),
                "integration-test",
                false
        ));

        MockHttpServletResponse response = post("/api/v1/trading/orders/market", """
                {
                  "symbol": "ETHUSDT",
                  "side": "SELL",
                  "quantity": 1.0,
                  "leverage": 10,
                  "takeProfitPrice": 1800.0,
                  "stopLossPrice": 2100.0,
                  "clientOrderId": "integration-order-31003"
                }
                """, userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"30002\""));
        Assertions.assertTrue(body.contains("\"message\":\"Price Source Stale Or Disconnected\""));
        Assertions.assertTrue(body.contains("\"rejectionReason\":\"MARKET_QUOTE_STALE\""));
        Assertions.assertTrue(output.toString().contains("trading.order.rejected userId=31003 symbol=ETHUSDT clientOrderId=integration-order-31003 rejectionReason=MARKET_QUOTE_STALE"));
    }

    @Test
    void shouldReturnTradingSuspendedWhenHolidayBlocksSymbol() throws Exception {
        long userId = 31004L;
        tradingAccountService.creditDeposit(
                userId,
                tradingCoreServiceProperties.getSettlementToken(),
                new BigDecimal("2000.00000000"),
                "it-credit-31004",
                "seed-31004",
                OffsetDateTime.now()
        );
        seedHolidayClosedSchedule("BTCUSDT", "CRYPTO");
        quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now(),
                "integration-test",
                false
        ));

        MockHttpServletResponse response = post("/api/v1/trading/orders/market", """
                {
                  "symbol": "BTCUSDT",
                  "side": "BUY",
                  "quantity": 1.0,
                  "leverage": 10,
                  "clientOrderId": "integration-order-31004"
                }
                """, userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"40008\""));
        Assertions.assertTrue(body.contains("\"message\":\"Symbol Trading Suspended\""));
        Assertions.assertTrue(body.contains("\"rejectionReason\":\"SYMBOL_TRADING_SUSPENDED\""));
    }

    @Test
    void shouldListSwapSettlementsWithPagination() throws Exception {
        long userId = 31030L;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime rolloverAt = now.minusSeconds(2);
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-swap-list-31030");
        tradingTestSupportMapper.updatePositionOpenedAt(positionId, toUtcLocalDateTime(rolloverAt.minusHours(2)));
        seedSwapRates(
                "BTCUSDT",
                new TradingSwapRateRule(
                        now.toLocalDate().minusDays(1),
                        rolloverAt.toLocalTime().withNano(0),
                        new BigDecimal("-0.00010000"),
                        new BigDecimal("0.00010000")
                )
        );
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10000.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("10000.00000000"),
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)
        );
        Assertions.assertEquals(1, tradingSwapSettlementService.settleDuePositions(now));

        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/trading/swap-settlements")
                        .header("X-User-Id", String.valueOf(userId))
                        .param("page", "1")
                        .param("pageSize", "20"))
                .andReturn()
                .getResponse();
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());
        JsonNode items = root.path("data").path("items");

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertEquals(1, root.path("data").path("page").asInt());
        Assertions.assertEquals(20, root.path("data").path("pageSize").asInt());
        Assertions.assertEquals(1L, root.path("data").path("total").asLong());
        Assertions.assertTrue(items.isArray());
        Assertions.assertEquals(1, items.size());
        Assertions.assertEquals(positionId, items.get(0).path("positionId").asLong());
        Assertions.assertEquals("BTCUSDT", items.get(0).path("symbol").asText());
        Assertions.assertEquals("BUY", items.get(0).path("side").asText());
        Assertions.assertEquals("SWAP_CHARGE", items.get(0).path("settlementType").asText());
        Assertions.assertEquals(0, new BigDecimal("1.00000000").compareTo(new BigDecimal(items.get(0).path("amount").asText())));
        Assertions.assertEquals(0, new BigDecimal("1994.00000000").compareTo(new BigDecimal(items.get(0).path("balanceAfter").asText())));
        Assertions.assertEquals("swap:" + positionId + ":" + rolloverAt.withNano(0).toInstant(), items.get(0).path("referenceNo").asText());
        Assertions.assertEquals(1, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 6));
    }

    @Test
    void shouldRejectInvalidSwapSettlementPagination() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/trading/swap-settlements")
                        .header("X-User-Id", "31031")
                        .param("page", "0")
                        .param("pageSize", "200"))
                .andReturn()
                .getResponse();
        String body = response.getContentAsString();

        Assertions.assertEquals(400, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"99004\""));
        Assertions.assertTrue(body.contains("\"message\":\"invalid request payload\""));
    }

    @Test
    void shouldClosePositionSuccessfullyAndRemoveSnapshot() throws Exception {
        long userId = 31005L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-close-31005");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );

        MockHttpServletResponse response = postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertNotNull(response.getHeader("X-Trace-Id"));
        Assertions.assertTrue(body.contains("\"code\":\"0\""));
        Assertions.assertTrue(body.contains("\"positionStatus\":\"CLOSED\""));
        Assertions.assertTrue(body.contains("\"closeReason\":\"MANUAL\""));
        Assertions.assertTrue(body.contains("\"available\":2040.00000000"));
        Assertions.assertEquals("2040.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectAccountFrozenByUserId(userId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals(2, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals("10045.00000000", tradingTestSupportMapper.selectPositionClosePriceById(positionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionCloseReasonCodeById(positionId));
        Assertions.assertEquals("45.00000000", tradingTestSupportMapper.selectPositionRealizedPnlById(positionId));
        Assertions.assertNotNull(tradingTestSupportMapper.selectPositionClosedAtById(positionId));
        Assertions.assertEquals(2, tradingTestSupportMapper.countTradesByPositionId(positionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(1, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 8));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOrdersByUserId(userId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countRiskExposureBySymbol("BTCUSDT"));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectRiskExposureNetBySymbol("BTCUSDT"));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        Assertions.assertTrue(openPositionSnapshotStore.listOpenByUserId(userId).isEmpty());

        MockHttpServletResponse accountResponse = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/trading/accounts/me")
                        .header("X-User-Id", String.valueOf(userId)))
                .andReturn()
                .getResponse();
        Assertions.assertTrue(accountResponse.getContentAsString().contains("\"openPositions\":[]"));
    }

    @Test
    void shouldRejectManualCloseWhenHolidayBlocksQuoteExecution() throws Exception {
        long userId = 31006L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-close-31006");
        seedHolidayClosedSchedule("BTCUSDT", "CRYPTO");
        MockHttpServletResponse rejectedOpenResponse = post("/api/v1/trading/orders/market", """
                {
                  "symbol": "BTCUSDT",
                  "side": "BUY",
                  "quantity": 1.0,
                  "leverage": 10,
                  "clientOrderId": "integration-order-close-31006-rejected-open"
                }
                """, userId);
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );

        MockHttpServletResponse response = postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, rejectedOpenResponse.getStatus());
        Assertions.assertTrue(rejectedOpenResponse.getContentAsString().contains("\"code\":\"40008\""));
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"40008\""));
        Assertions.assertTrue(body.contains("\"message\":\"Symbol Trading Suspended\""));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 8));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
    }

    @Test
    void shouldReturnRemainingOpenPositionsInCloseSuccessResponse() throws Exception {
        long userId = 31014L;
        long closedPositionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-close-31014-btc");
        long remainingPositionId = seedAndOpenPosition(userId, "ETHUSDT", TradingOrderSide.SELL, "integration-order-close-31014-eth");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );
        publishQuote(
                "ETHUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );

        MockHttpServletResponse response = postWithoutBody("/api/v1/trading/positions/" + closedPositionId + "/close", userId);
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());
        JsonNode openPositions = root.path("data").path("account").path("openPositions");

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertTrue(openPositions.isArray());
        Assertions.assertEquals(1, openPositions.size());
        Assertions.assertEquals(remainingPositionId, openPositions.get(0).path("positionId").asLong());
        Assertions.assertEquals("ETHUSDT", openPositions.get(0).path("symbol").asText());
        Assertions.assertEquals("SELL", openPositions.get(0).path("side").asText());
        Assertions.assertEquals(2, tradingTestSupportMapper.selectPositionStatusCodeById(closedPositionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(remainingPositionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        Assertions.assertEquals(1, openPositionSnapshotStore.listOpenByUserId(userId).size());
        Assertions.assertEquals(remainingPositionId, openPositionSnapshotStore.listOpenByUserId(userId).getFirst().positionId());
    }

    @Test
    void shouldReturnPriceSourceStaleWhenCloseQuoteIsStale(CapturedOutput output) throws Exception {
        long userId = 31007L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-close-31007");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now().minusSeconds(10)
        );

        MockHttpServletResponse response = postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"30002\""));
        Assertions.assertTrue(body.contains("\"message\":\"Price Source Stale Or Disconnected\""));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals("1995.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 8));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        Assertions.assertTrue(output.toString().contains("trading.http.request.failed code=30002"));
        Assertions.assertTrue(output.toString().contains("userId=31007"));
        Assertions.assertTrue(output.toString().contains("symbol=BTCUSDT"));
        Assertions.assertTrue(output.toString().contains("positionId=" + positionId));
        Assertions.assertTrue(output.toString().contains("rejectionReason=PRICE_SOURCE_STALE_OR_DISCONNECTED"));
        Assertions.assertTrue(output.toString().contains("traceId="));
    }

    @Test
    void shouldReturnQuoteNotAvailableWhenCloseQuoteIsMissing() throws Exception {
        long userId = 31008L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-close-31008");
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:BTCUSDT");

        MockHttpServletResponse response = postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"30003\""));
        Assertions.assertTrue(body.contains("\"message\":\"Quote Not Available\""));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals("1995.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 8));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
    }

    @Test
    void shouldReturnPositionNotFoundWhenClosingMissingPosition() throws Exception {
        long userId = 31033L;
        long existingPositionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-close-31033");
        long missingPositionId = existingPositionId + 999_999L;

        MockHttpServletResponse response = postWithoutBody("/api/v1/trading/positions/" + missingPositionId + "/close", userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"40004\""));
        Assertions.assertTrue(body.contains("\"message\":\"Position Not Found\""));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(existingPositionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(existingPositionId, 2));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 8));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        Assertions.assertEquals(existingPositionId, openPositionSnapshotStore.listOpenByUserId(userId).getFirst().positionId());
    }

    @Test
    void shouldSupplementIsolatedMarginAndRecalculateLiquidationPrice() throws Exception {
        long userId = 31036L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-position-margin-31036");
        BigDecimal beforeLiquidationPrice = new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId));

        MockHttpServletResponse response = post("/api/v1/trading/positions/" + positionId + "/margin", """
                {
                  "amount": 200.0
                }
                """, userId);
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());
        JsonNode account = root.path("data").path("account");
        JsonNode openPosition = account.path("openPositions").get(0);

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertEquals("ISOLATED", root.path("data").path("marginMode").asText());
        Assertions.assertEquals(0, root.path("data").path("margin").decimalValue().compareTo(new BigDecimal("1200.00000000")));
        Assertions.assertEquals("1200.00000000", tradingTestSupportMapper.selectPositionMarginById(positionId));
        Assertions.assertEquals("1200.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals("1995.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals(0, account.path("available").decimalValue().compareTo(new BigDecimal("795.00000000")));
        Assertions.assertEquals(0, new BigDecimal("8850.00000000").compareTo(root.path("data").path("liquidationPrice").decimalValue()));
        Assertions.assertEquals(0, new BigDecimal("8850.00000000").compareTo(new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId))));
        Assertions.assertTrue(beforeLiquidationPrice.compareTo(new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId))) > 0);
        Assertions.assertEquals("ISOLATED", openPosition.path("marginMode").asText());
        Assertions.assertEquals(0, new BigDecimal("8850.00000000").compareTo(openPosition.path("liquidationPrice").decimalValue()));
        Assertions.assertEquals(1, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 10));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.position.margin.supplemented"));
        Assertions.assertEquals("1.00000000", tradingTestSupportMapper.selectRiskExposureNetBySymbol("BTCUSDT"));
        Assertions.assertEquals(1, openPositionSnapshotStore.listOpenByUserId(userId).size());
        Assertions.assertEquals(0, new BigDecimal("8850.00000000").compareTo(openPositionSnapshotStore.listOpenByUserId(userId).getFirst().liquidationPrice()));
    }

    @Test
    void shouldReturnInsufficientMarginWhenSupplementExceedsAvailable() throws Exception {
        long userId = 31037L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-position-margin-31037");

        MockHttpServletResponse response = post("/api/v1/trading/positions/" + positionId + "/margin", """
                {
                  "amount": 2000.0
                }
                """, userId);

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(response.getContentAsString().contains("\"code\":\"40001\""));
        Assertions.assertTrue(response.getContentAsString().contains("\"message\":\"Insufficient Margin\""));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectPositionMarginById(positionId));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 10));
    }

    @Test
    void shouldReturnPositionNotFoundWhenSupplementingMissingPosition() throws Exception {
        long userId = 31038L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-position-margin-31038");
        long missingPositionId = positionId + 999_999L;

        MockHttpServletResponse response = post("/api/v1/trading/positions/" + missingPositionId + "/margin", """
                {
                  "amount": 100.0
                }
                """, userId);

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(response.getContentAsString().contains("\"code\":\"40004\""));
        Assertions.assertTrue(response.getContentAsString().contains("\"message\":\"Position Not Found\""));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectPositionMarginById(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 10));
    }

    @Test
    void shouldReturnPositionAlreadyClosedWhenSupplementingClosedPosition() throws Exception {
        long userId = 31039L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-position-margin-31039");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );
        MockHttpServletResponse closeResponse = postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", userId);
        Assertions.assertTrue(closeResponse.getContentAsString().contains("\"code\":\"0\""));

        MockHttpServletResponse response = post("/api/v1/trading/positions/" + positionId + "/margin", """
                {
                  "amount": 100.0
                }
                """, userId);

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(response.getContentAsString().contains("\"code\":\"40007\""));
        Assertions.assertTrue(response.getContentAsString().contains("\"message\":\"Position Already Closed\""));
        Assertions.assertEquals(1, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 8));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 10));
    }

    @Test
    void shouldSupplementIsolatedMarginViaMePath() throws Exception {
        long userId = 31041L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-position-margin-me-31041");
        BigDecimal beforeLiquidationPrice = new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId));

        MockHttpServletResponse response = post("/api/v1/me/positions/" + positionId + "/supplement-margin", """
                {
                  "amount": 200.0
                }
                """, userId);
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertEquals("ISOLATED", root.path("data").path("marginMode").asText());
        Assertions.assertEquals(0, root.path("data").path("margin").decimalValue().compareTo(new BigDecimal("1200.00000000")));
        Assertions.assertEquals("1200.00000000", tradingTestSupportMapper.selectPositionMarginById(positionId));
        Assertions.assertEquals("1200.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertTrue(beforeLiquidationPrice.compareTo(new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId))) > 0);
        Assertions.assertEquals(1, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 10));
    }

    @Test
    void shouldRejectMeSupplementWhenPositionNotOwnedByUser() throws Exception {
        long ownerUserId = 31042L;
        long attackerUserId = 31043L;
        long positionId = seedAndOpenPosition(ownerUserId, "BTCUSDT", TradingOrderSide.BUY, "integration-position-margin-me-31042");

        MockHttpServletResponse response = post("/api/v1/me/positions/" + positionId + "/supplement-margin", """
                {
                  "amount": 100.0
                }
                """, attackerUserId);

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(response.getContentAsString().contains("\"code\":\"40004\""));
        Assertions.assertTrue(response.getContentAsString().contains("\"message\":\"Position Not Found\""));
        // 越权未生效：持仓保证金与账本不变
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectPositionMarginById(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(ownerUserId, 10));
    }

    @Test
    void shouldReturnPositionAlreadyClosedWhenClosingSamePositionTwice() throws Exception {
        long userId = 31009L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-close-31009");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );

        MockHttpServletResponse firstResponse = postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", userId);
        MockHttpServletResponse secondResponse = postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", userId);
        String secondBody = secondResponse.getContentAsString();

        Assertions.assertTrue(firstResponse.getContentAsString().contains("\"code\":\"0\""));
        Assertions.assertEquals(200, secondResponse.getStatus());
        Assertions.assertTrue(secondBody.contains("\"code\":\"40007\""));
        Assertions.assertTrue(secondBody.contains("\"message\":\"Position Already Closed\""));
        Assertions.assertEquals(2, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(1, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 8));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOrdersByUserId(userId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
    }

    @Test
    void shouldReturnPositionNotFoundWhenClosingOpenPositionBelongingToAnotherUser() throws Exception {
        long ownerUserId = 31010L;
        long otherUserId = 31011L;
        long positionId = seedAndOpenPosition(ownerUserId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-close-31010");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );

        MockHttpServletResponse response = postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", otherUserId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"40004\""));
        Assertions.assertTrue(body.contains("\"message\":\"Position Not Found\""));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals("1995.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(ownerUserId));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(ownerUserId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(ownerUserId, 8));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(ownerUserId));
        Assertions.assertEquals(1, openPositionSnapshotStore.listOpenByUserId(ownerUserId).size());
    }

    @Test
    void shouldReturnInternalErrorWithoutRecreatingSettlementAccountWhenCloseAccountIsMissing() throws Exception {
        long userId = 31012L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-close-31012");
        tradingTestSupportMapper.deleteAccountByUserIdAndCurrency(userId, tradingCoreServiceProperties.getSettlementToken());
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );

        MockHttpServletResponse response = postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(500, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"99001\""));
        Assertions.assertTrue(body.contains("\"message\":\"internal error\""));
        Assertions.assertEquals(0, tradingTestSupportMapper.countAccountsByUserId(userId));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countTradesByPositionId(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 8));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        Assertions.assertEquals(1, openPositionSnapshotStore.listOpenByUserId(userId).size());
    }

    @Test
    void shouldCloseShortPositionWithNegativeRealizedPnlAndWritePositionClosedOutbox() throws Exception {
        long userId = 31013L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.SELL, "integration-order-close-31013");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10075.00000000"),
                new BigDecimal("10085.00000000"),
                new BigDecimal("10080.00000000"),
                OffsetDateTime.now()
        );

        MockHttpServletResponse response = postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"0\""));
        Assertions.assertTrue(body.contains("\"positionStatus\":\"CLOSED\""));
        Assertions.assertTrue(body.contains("\"available\":1900.00500000"));
        Assertions.assertEquals("1900.00500000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectAccountFrozenByUserId(userId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals(2, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals("10085.00000000", tradingTestSupportMapper.selectPositionClosePriceById(positionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionCloseReasonCodeById(positionId));
        Assertions.assertEquals("-95.00000000", tradingTestSupportMapper.selectPositionRealizedPnlById(positionId));
        Assertions.assertNotNull(tradingTestSupportMapper.selectPositionClosedAtById(positionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(1, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 8));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOrdersByUserId(userId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectRiskExposureNetBySymbol("BTCUSDT"));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        Assertions.assertTrue(openPositionSnapshotStore.listOpenByUserId(userId).isEmpty());
    }

    @Test
    void shouldPatchTakeProfitAndStopLossSuccessfully() throws Exception {
        long userId = 31015L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-patch-31015");

        String requestBody = """
                {
                  "takeProfitPrice": 10250.0,
                  "stopLossPrice": 9850.0
                }
                """;
        MockHttpServletResponse firstResponse = patch("/api/v1/trading/positions/" + positionId, requestBody, userId);
        MockHttpServletResponse secondResponse = patch("/api/v1/trading/positions/" + positionId, requestBody, userId);
        JsonNode firstRoot = OBJECT_MAPPER.readTree(firstResponse.getContentAsString());
        JsonNode secondRoot = OBJECT_MAPPER.readTree(secondResponse.getContentAsString());

        Assertions.assertEquals(200, firstResponse.getStatus());
        Assertions.assertEquals(200, secondResponse.getStatus());
        Assertions.assertEquals("0", firstRoot.path("code").asText());
        Assertions.assertEquals(firstRoot.path("data"), secondRoot.path("data"));
        Assertions.assertEquals(positionId, firstRoot.path("data").path("positionId").asLong());
        Assertions.assertEquals("BTCUSDT", firstRoot.path("data").path("symbol").asText());
        Assertions.assertEquals("OPEN", firstRoot.path("data").path("status").asText());
        Assertions.assertEquals("10250.00000000", tradingTestSupportMapper.selectPositionTakeProfitPriceById(positionId));
        Assertions.assertEquals("9850.00000000", tradingTestSupportMapper.selectPositionStopLossPriceById(positionId));
        TradingPosition snapshot = openPositionSnapshotStore.listOpenByUserId(userId).getFirst();
        Assertions.assertEquals(positionId, snapshot.positionId());
        Assertions.assertEquals(0, new BigDecimal("10250.00000000").compareTo(snapshot.takeProfitPrice()));
        Assertions.assertEquals(0, new BigDecimal("9850.00000000").compareTo(snapshot.stopLossPrice()));
    }

    @Test
    void shouldPatchOnlyOneFieldAndKeepOtherUnchanged() throws Exception {
        long userId = 31016L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-patch-31016");

        MockHttpServletResponse response = patch("/api/v1/trading/positions/" + positionId, """
                {
                  "takeProfitPrice": 10280.0
                }
                """, userId);
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertEquals("10280.00000000", tradingTestSupportMapper.selectPositionTakeProfitPriceById(positionId));
        Assertions.assertEquals("9800.00000000", tradingTestSupportMapper.selectPositionStopLossPriceById(positionId));
        TradingPosition snapshot = openPositionSnapshotStore.listOpenByUserId(userId).getFirst();
        Assertions.assertEquals(0, new BigDecimal("10280.00000000").compareTo(snapshot.takeProfitPrice()));
        Assertions.assertEquals(0, new BigDecimal("9800.00000000").compareTo(snapshot.stopLossPrice()));
    }

    @Test
    void shouldClearTakeProfitAndStopLossWhenExplicitNullIsProvided() throws Exception {
        long userId = 31017L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-patch-31017");

        MockHttpServletResponse response = patch("/api/v1/trading/positions/" + positionId, """
                {
                  "takeProfitPrice": null,
                  "stopLossPrice": null
                }
                """, userId);
        JsonNode root = OBJECT_MAPPER.readTree(response.getContentAsString());

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("0", root.path("code").asText());
        Assertions.assertTrue(root.path("data").path("takeProfitPrice").isNull());
        Assertions.assertTrue(root.path("data").path("stopLossPrice").isNull());
        Assertions.assertNull(tradingTestSupportMapper.selectPositionTakeProfitPriceById(positionId));
        Assertions.assertNull(tradingTestSupportMapper.selectPositionStopLossPriceById(positionId));
        TradingPosition snapshot = openPositionSnapshotStore.listOpenByUserId(userId).getFirst();
        Assertions.assertNull(snapshot.takeProfitPrice());
        Assertions.assertNull(snapshot.stopLossPrice());
    }

    @Test
    void shouldSkipTriggeredCloseWhenPatchClearsTakeProfitBeforeOwnerWritePathRevalidation() throws Exception {
        long userId = 31028L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-patch-race-31028");
        TradingPosition staleSnapshot = openPositionSnapshotStore.listOpenByUserId(userId).getFirst();

        MockHttpServletResponse patchResponse = patch("/api/v1/trading/positions/" + positionId, """
                {
                  "takeProfitPrice": null
                }
                """, userId);

        Assertions.assertEquals(200, patchResponse.getStatus());
        Assertions.assertTrue(patchResponse.getContentAsString().contains("\"code\":\"0\""));
        Assertions.assertEquals(0, new BigDecimal("10100.00000000").compareTo(staleSnapshot.takeProfitPrice()));

        PositionCloseResult closeResult = tradingPositionCloseApplicationService.closePositionByTrigger(
                positionId,
                TradingPositionCloseReason.TAKE_PROFIT,
                new TradingQuoteSnapshot(
                        "BTCUSDT",
                        new BigDecimal("10095.00000000"),
                        new BigDecimal("10105.00000000"),
                        new BigDecimal("10100.00000000"),
                        OffsetDateTime.now(),
                        "integration-test",
                        false
                )
        );

        Assertions.assertNull(closeResult);
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertNull(tradingTestSupportMapper.selectPositionTakeProfitPriceById(positionId));
        Assertions.assertEquals("9800.00000000", tradingTestSupportMapper.selectPositionStopLossPriceById(positionId));
        Assertions.assertEquals("1995.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 8));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        TradingPosition refreshedSnapshot = openPositionSnapshotStore.listOpenByUserId(userId).getFirst();
        Assertions.assertEquals(positionId, refreshedSnapshot.positionId());
        Assertions.assertNull(refreshedSnapshot.takeProfitPrice());
        Assertions.assertEquals(0, new BigDecimal("9800.00000000").compareTo(refreshedSnapshot.stopLossPrice()));
    }

    @Test
    void shouldReturnPositionNotFoundWhenPatchingPositionBelongingToAnotherUser() throws Exception {
        long ownerUserId = 31018L;
        long otherUserId = 31019L;
        long positionId = seedAndOpenPosition(ownerUserId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-patch-31018");

        MockHttpServletResponse response = patch("/api/v1/trading/positions/" + positionId, """
                {
                  "takeProfitPrice": 10290.0
                }
                """, otherUserId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"40004\""));
        Assertions.assertTrue(body.contains("\"message\":\"Position Not Found\""));
        Assertions.assertEquals("10100.00000000", tradingTestSupportMapper.selectPositionTakeProfitPriceById(positionId));
        Assertions.assertEquals("9800.00000000", tradingTestSupportMapper.selectPositionStopLossPriceById(positionId));
        TradingPosition snapshot = openPositionSnapshotStore.listOpenByUserId(ownerUserId).getFirst();
        Assertions.assertEquals(0, new BigDecimal("10100.00000000").compareTo(snapshot.takeProfitPrice()));
        Assertions.assertEquals(0, new BigDecimal("9800.00000000").compareTo(snapshot.stopLossPrice()));
    }

    @Test
    void shouldRejectPatchWhenPositionAlreadyClosed() throws Exception {
        long userId = 31020L;
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, "integration-order-patch-31020");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );
        MockHttpServletResponse closeResponse = postWithoutBody("/api/v1/trading/positions/" + positionId + "/close", userId);
        Assertions.assertTrue(closeResponse.getContentAsString().contains("\"code\":\"0\""));

        MockHttpServletResponse response = patch("/api/v1/trading/positions/" + positionId, """
                {
                  "takeProfitPrice": 10290.0
                }
                """, userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"40007\""));
        Assertions.assertTrue(body.contains("\"message\":\"Position Already Closed\""));
        Assertions.assertEquals(2, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals("10100.00000000", tradingTestSupportMapper.selectPositionTakeProfitPriceById(positionId));
        Assertions.assertTrue(openPositionSnapshotStore.listOpenByUserId(userId).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidPatchPayloads")
    void shouldRejectInvalidPatchPayloadsWithStable4xx(String requestBody, long userId, String clientOrderId) throws Exception {
        long positionId = seedAndOpenPosition(userId, "BTCUSDT", TradingOrderSide.BUY, clientOrderId);
        MockHttpServletResponse response = patch("/api/v1/trading/positions/" + positionId, requestBody, userId);
        String body = response.getContentAsString();

        Assertions.assertEquals(400, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"99004\""));
        Assertions.assertTrue(body.contains("\"message\":\"invalid request payload\""));
        Assertions.assertEquals("10100.00000000", tradingTestSupportMapper.selectPositionTakeProfitPriceById(positionId));
        Assertions.assertEquals("9800.00000000", tradingTestSupportMapper.selectPositionStopLossPriceById(positionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(1, openPositionSnapshotStore.listOpenByUserId(userId).size());
    }

    private static Stream<Arguments> invalidPatchPayloads() {
        return Stream.of(
                Arguments.of("", 31021L, "integration-order-patch-invalid-31021"),
                Arguments.of("""
                        {
                          "takeProfitPrice": "abc"
                        }
                        """, 31022L, "integration-order-patch-invalid-31022"),
                Arguments.of("""
                        {
                          "takeProfitPrice": 0
                        }
                        """, 31023L, "integration-order-patch-invalid-31023"),
                Arguments.of("""
                        {
                          "stopLossPrice": -1
                        }
                        """, 31024L, "integration-order-patch-invalid-31024"),
                Arguments.of("""
                        {
                          "foo": 1
                        }
                        """, 31025L, "integration-order-patch-invalid-31025"),
                Arguments.of("{}", 31026L, "integration-order-patch-invalid-31026"),
                Arguments.of("[]", 31027L, "integration-order-patch-invalid-31027")
        );
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
        MvcResult mvcResult = mockMvc.perform(MockMvcRequestBuilders.post(path)
                        .header("X-User-Id", String.valueOf(userId)))
                .andReturn();
        return mvcResult.getResponse();
    }

    private MockHttpServletResponse patch(String path, String body, long userId) throws Exception {
        MockHttpServletRequestBuilder requestBuilder = MockMvcRequestBuilders.patch(path)
                .header("X-User-Id", String.valueOf(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
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

    private void seedSwapRates(String symbol, TradingSwapRateRule... rules) {
        tradingSwapRateSnapshotRepository.saveForTest(new TradingSwapRateSnapshot(
                symbol,
                List.of(rules),
                OffsetDateTime.now()
        ));
    }

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

    private LocalDateTime toUtcLocalDateTime(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
