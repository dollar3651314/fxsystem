package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.event.MarketGroupMarkupItem;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.application.TradingPositionCloseApplicationService;
import com.falconx.trading.command.CloseTradingPositionCommand;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.dto.PositionCloseResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.repository.RedisTradingScheduleSnapshotRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.trading.service.TradingGroupMarkupService;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import com.falconx.trading.service.model.TradingSessionWindow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * STAGE-12-GROUP-MARKUP TC-GM-016~017: 平仓 PnL round-trip + 冻结口径集成测试。
 *
 * <p>核心验证：
 * <ul>
 *   <li>TC-GM-016 BUY 持仓平仓用 position.bidExtraAtOpen 算 exitPrice
 *       （即使管理端已改组级配置，平仓仍按开仓时冻结的 markup）</li>
 *   <li>TC-GM-017 round-trip PnL = (exit - entry) × qty 双向含 markup
 *       开 entry=ask+askExtra；平 exit=bid+bidExtraAtOpen；净 PnL 经济正确</li>
 *   <li>TC-GM-016b SELL 持仓平仓走 ask + askExtraAtOpen</li>
 *   <li>TC-GM-017b 即使组级配置在持仓期间被修改，平仓仍按冻结值结算</li>
 * </ul>
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380"
        }
)
class TradingClosePositionGroupMarkupIntegrationTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-05-21T00:00:00Z");
    private static final int TRADE_TYPE_CLOSE = 2;

    @Autowired
    private TradingDepositCreditApplicationService tradingDepositCreditApplicationService;

    @Autowired
    private TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService;

    @Autowired
    private TradingPositionCloseApplicationService tradingPositionCloseApplicationService;

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

    @MockitoBean
    private TradingGroupMarkupService tradingGroupMarkupService;

    @BeforeEach
    void cleanTradingStores() {
        tradingTestSupportMapper.clearOwnerTables();
        openPositionSnapshotStore.replaceAll(List.of());
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:BTCUSDT");

        Mockito.reset(tradingGroupMarkupService);
        Mockito.when(tradingGroupMarkupService.find(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Optional.empty());
    }

    /**
     * TC-GM-016 BUY 持仓平仓走 bid + position.bidExtraAtOpen（冻结值，不查实时配置）。
     */
    @Test
    void TC_GM_016_close_buy_position_uses_frozen_bid_extra() {
        // 1) 开仓时 markup: bid_extra=0.5, ask_extra=1.0
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("0.5"), new BigDecimal("1.0"),
                        true, T0)));

        long userId = 96001L;
        OrderPlacementResult open = openPosition(userId, "vip", TradingOrderSide.BUY,
                "stage12-close-016",
                new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"));
        Long positionId = open.position().positionId();
        // entryPrice = ask(10000) + askExtra(1.0) = 10001
        Assertions.assertEquals(0, open.position().entryPrice().compareTo(new BigDecimal("10001.00000000")));

        // 2) 涨价 + 推送新报价
        publishQuote("BTCUSDT", new BigDecimal("10090.00000000"), new BigDecimal("10100.00000000"),
                new BigDecimal("10095.00000000"), OffsetDateTime.now());

        // 3) 平仓
        PositionCloseResult closed = tradingPositionCloseApplicationService.closePosition(
                new CloseTradingPositionCommand(userId, positionId));

        // exitPrice = bid(10090) + bidExtraAtOpen(0.5) = 10090.5
        Assertions.assertEquals("10090.50000000",
                tradingTestSupportMapper.selectTradePriceByPositionIdAndTradeType(positionId, TRADE_TYPE_CLOSE),
                "BUY 平仓走 bid + bidExtraAtOpen");
        Assertions.assertEquals("10090.50000000",
                tradingTestSupportMapper.selectPositionClosePriceById(positionId));
    }

    /** TC-GM-016b SELL 持仓平仓走 ask + askExtraAtOpen。 */
    @Test
    void TC_GM_016b_close_sell_position_uses_frozen_ask_extra() {
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("0.5"), new BigDecimal("1.0"),
                        true, T0)));

        long userId = 96002L;
        OrderPlacementResult open = openPosition(userId, "vip", TradingOrderSide.SELL,
                "stage12-close-016b",
                new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"));
        Long positionId = open.position().positionId();
        // entryPrice = bid(9990) + bidExtra(0.5) = 9990.5
        Assertions.assertEquals(0, open.position().entryPrice().compareTo(new BigDecimal("9990.50000000")));

        publishQuote("BTCUSDT", new BigDecimal("9890.00000000"), new BigDecimal("9900.00000000"),
                new BigDecimal("9895.00000000"), OffsetDateTime.now());

        tradingPositionCloseApplicationService.closePosition(new CloseTradingPositionCommand(userId, positionId));

        // SELL 平仓 exitPrice = ask(9900) + askExtraAtOpen(1.0) = 9901
        Assertions.assertEquals("9901.00000000",
                tradingTestSupportMapper.selectTradePriceByPositionIdAndTradeType(positionId, TRADE_TYPE_CLOSE));
    }

    /**
     * TC-GM-017 round-trip PnL: BUY 持仓开 entry=10001 平 exit=10090.5，PnL=(10090.5-10001)×1=89.5
     * 双向 markup 都含在 entry / exit 中，PnL 经济正确（仅反映价格变动，不被 markup 干扰）。
     */
    @Test
    void TC_GM_017_close_realized_pnl_round_trip_with_markup() {
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("0.5"), new BigDecimal("1.0"),
                        true, T0)));

        long userId = 96003L;
        OrderPlacementResult open = openPosition(userId, "vip", TradingOrderSide.BUY,
                "stage12-close-017",
                new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"));
        Long positionId = open.position().positionId();

        publishQuote("BTCUSDT", new BigDecimal("10090.00000000"), new BigDecimal("10100.00000000"),
                new BigDecimal("10095.00000000"), OffsetDateTime.now());

        tradingPositionCloseApplicationService.closePosition(new CloseTradingPositionCommand(userId, positionId));

        // entry=10001 exit=10090.5 PnL=(10090.5-10001)*1=89.5
        Assertions.assertEquals("89.50000000",
                tradingTestSupportMapper.selectTradeRealizedPnlByPositionIdAndTradeType(positionId, TRADE_TYPE_CLOSE),
                "realized PnL 应反映 entry/exit 双向含 markup 的差价");
        Assertions.assertEquals("89.50000000",
                tradingTestSupportMapper.selectPositionRealizedPnlById(positionId));
    }

    /**
     * TC-GM-017b 持仓期间运营改了组级配置，平仓仍按冻结的 bidExtraAtOpen 结算。
     * 这是冻结策略的核心保证：换组 / 改配置不影响存量持仓的 PnL 口径。
     */
    @Test
    void TC_GM_017b_close_uses_frozen_value_even_if_live_config_changed() {
        // 开仓用 0.5/1.0 markup
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("0.5"), new BigDecimal("1.0"),
                        true, T0)));

        long userId = 96004L;
        OrderPlacementResult open = openPosition(userId, "vip", TradingOrderSide.BUY,
                "stage12-close-017b",
                new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"));
        Long positionId = open.position().positionId();
        BigDecimal frozenBidExtra = open.position().bidExtraAtOpen();
        Assertions.assertEquals(0, frozenBidExtra.compareTo(new BigDecimal("0.5")));

        // 运营把 markup 改成 5.0 / 10.0（"实时" find 返回新值）
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("5.0"), new BigDecimal("10.0"),
                        true, T0.plusMinutes(5))));

        publishQuote("BTCUSDT", new BigDecimal("10090.00000000"), new BigDecimal("10100.00000000"),
                new BigDecimal("10095.00000000"), OffsetDateTime.now());

        tradingPositionCloseApplicationService.closePosition(new CloseTradingPositionCommand(userId, positionId));

        // 仍按冻结的 0.5 算：exit=10090+0.5=10090.5（不是 10090+5.0=10095）
        Assertions.assertEquals("10090.50000000",
                tradingTestSupportMapper.selectTradePriceByPositionIdAndTradeType(positionId, TRADE_TYPE_CLOSE),
                "平仓必须按 position 冻结的 bidExtraAtOpen=0.5 结算，不受运营改配置影响");
    }

    private OrderPlacementResult openPosition(long userId, String groupCode,
                                              TradingOrderSide side, String clientOrderId,
                                              BigDecimal bid, BigDecimal ask) {
        OffsetDateTime now = OffsetDateTime.now();
        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-" + clientOrderId,
                99000L + userId,
                userId,
                ChainType.ETH,
                "USDT",
                "0x" + clientOrderId,
                new BigDecimal("2000.00000000"),
                now
        ));
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        publishQuote("BTCUSDT", bid, ask,
                bid.add(ask).divide(BigDecimal.valueOf(2), 8, java.math.RoundingMode.DOWN),
                now);
        return tradingOrderPlacementApplicationService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId,
                "BTCUSDT",
                side,
                new BigDecimal("1.00000000"),
                new BigDecimal("10"),
                null,
                null,
                null,
                clientOrderId,
                groupCode
        ));
    }

    private void publishQuote(String symbol, BigDecimal bid, BigDecimal ask, BigDecimal mark, OffsetDateTime ts) {
        quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                symbol, bid, ask, mark, mark, ts, "stage12-it",
                TradingQuoteQualityStatus.FRESH.stale(),
                TradingQuoteQualityStatus.FRESH.name(),
                null
        ));
    }

    private void seedAlwaysOpenSchedule(String symbol, String marketCode) {
        tradingScheduleSnapshotRepository.saveForTest(new TradingScheduleSnapshot(
                symbol, marketCode,
                List.of(
                        new TradingSessionWindow(1, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(2, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(3, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(4, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(5, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(6, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(7, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null)
                ),
                List.of(),
                List.of(),
                OffsetDateTime.now()
        ));
    }
}
