package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.event.MarketGroupMarkupItem;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.application.TradingUserQueryApplicationService;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.dto.TradingPositionSummaryResponse;
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
 * STAGE-12-GROUP-MARKUP TC-GM-018: unrealized PnL 应用 position 冻结 markup。
 *
 * <p>验证 {@link TradingUserQueryApplicationService#getPositionSummary} 计算的 unrealized PnL
 * 含 markup（caller 传 base bid/ask，calculatePositionPnl 内部自动加 markup）。
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
class TradingUnrealizedPnLGroupMarkupIntegrationTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-05-21T00:00:00Z");

    @Autowired
    private TradingDepositCreditApplicationService tradingDepositCreditApplicationService;

    @Autowired
    private TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService;

    @Autowired
    private TradingUserQueryApplicationService tradingUserQueryApplicationService;

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
     * TC-GM-018: BUY 持仓 unrealized PnL = (base.bid + bidExtraAtOpen - entry) × qty
     *
     * 验证 user query 链路通过 calculatePositionPnl 应用 position 冻结的 markup。
     */
    @Test
    void TC_GM_018_unrealized_pnl_includes_frozen_markup() {
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("0.5"), new BigDecimal("1.0"),
                        true, T0)));

        long userId = 98001L;
        OrderPlacementResult open = openPosition(userId, "vip",
                "stage12-unrealized-018",
                new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"));
        // entry = ask(10000) + askExtra(1.0) = 10001
        Assertions.assertEquals(0, open.position().entryPrice().compareTo(new BigDecimal("10001.00000000")));

        // 推送涨价行情：base.bid=10090, base.ask=10100
        publishQuote("BTCUSDT", new BigDecimal("10090.00000000"), new BigDecimal("10100.00000000"),
                new BigDecimal("10095.00000000"), OffsetDateTime.now());

        TradingPositionSummaryResponse summary = tradingUserQueryApplicationService.getPositionSummary(userId);

        // unrealized PnL = (base.bid + bidExtraAtOpen - entry) × qty = (10090 + 0.5 - 10001) × 1 = 89.5
        Assertions.assertEquals(1, summary.openPositionCount());
        Assertions.assertEquals(0, summary.totalUnrealizedPnl().compareTo(new BigDecimal("89.50000000")),
                "unrealized PnL 应含 markup = (bid+bidExtra-entry)*qty = 89.5，实际：" + summary.totalUnrealizedPnl());
    }

    /** TC-GM-018b: 无 markup 持仓 PnL 走基准价（验证缺 markup 时回退正确）。 */
    @Test
    void TC_GM_018b_unrealized_pnl_without_markup_uses_base_price() {
        // 不 mock find，使用 @BeforeEach 默认 Optional.empty()

        long userId = 98002L;
        OrderPlacementResult open = openPosition(userId, null,
                "stage12-unrealized-018b",
                new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"));
        Assertions.assertEquals(0, open.position().entryPrice().compareTo(new BigDecimal("10000.00000000")));

        publishQuote("BTCUSDT", new BigDecimal("10090.00000000"), new BigDecimal("10100.00000000"),
                new BigDecimal("10095.00000000"), OffsetDateTime.now());

        TradingPositionSummaryResponse summary = tradingUserQueryApplicationService.getPositionSummary(userId);

        // unrealized PnL = (10090 - 10000) × 1 = 90 （0 markup）
        Assertions.assertEquals(0, summary.totalUnrealizedPnl().compareTo(new BigDecimal("90.00000000")),
                "默认组 0 markup → PnL=(10090-10000)*1=90");
    }

    private OrderPlacementResult openPosition(long userId, String groupCode,
                                              String clientOrderId,
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
                TradingOrderSide.BUY,
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
