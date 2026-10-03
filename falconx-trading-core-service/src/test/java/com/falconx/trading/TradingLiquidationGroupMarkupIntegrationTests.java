package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.event.MarketGroupMarkupItem;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.dto.PriceTickProcessingResult;
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
 * STAGE-12-GROUP-MARKUP TC-GM-019: 强平判定按 effectiveMark（含冻结 markup）。
 *
 * <p>核心约束：强平判定走 QuoteDrivenEngine → resolvePositionMarkPrice(quote, position)，
 * effective = base.bid + position.bidExtraAtOpen (BUY 持仓平仓口径)；
 * 与 liquidationPrice（开仓时按含 markup 的 entry 算）做比较，与口径一致。
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
class TradingLiquidationGroupMarkupIntegrationTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-05-21T00:00:00Z");

    @Autowired
    private TradingDepositCreditApplicationService tradingDepositCreditApplicationService;

    @Autowired
    private TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService;

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
     * TC-GM-019: vip 组 BUY 持仓在 effectiveMark = base.bid + bidExtraAtOpen 跌破 liquidationPrice 时强平。
     */
    @Test
    void TC_GM_019_liquidation_triggers_on_effective_mark_with_frozen_markup() {
        // vip 组 markup 0.5/1.0
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("0.5"), new BigDecimal("1.0"),
                        true, T0)));

        long userId = 97001L;
        OrderPlacementResult open = openPosition(userId, "vip",
                "stage12-liq-019",
                new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"));
        Long positionId = open.position().positionId();
        // entry = ask(10000) + askExtra(1.0) = 10001
        Assertions.assertEquals(0, open.position().entryPrice().compareTo(new BigDecimal("10001.00000000")));
        BigDecimal liquidationPrice = new BigDecimal(
                tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId));

        // 推送 bid 远低于 liquidationPrice，effective = bid + 0.5 也低于 → 触发强平
        BigDecimal lowBid = liquidationPrice.subtract(new BigDecimal("10.00000000"));
        BigDecimal lowAsk = liquidationPrice.subtract(new BigDecimal("9.00000000"));
        PriceTickProcessingResult result = publishQuote("BTCUSDT", lowBid, lowAsk,
                liquidationPrice, OffsetDateTime.now());

        Assertions.assertEquals(1, result.triggeredActions(),
                "强平应触发 1 次，实际：" + result.triggeredActions());
        // position 状态: 3 = LIQUIDATED
        Assertions.assertEquals(3, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        // closePrice 应是 effective = lowBid + 0.5
        BigDecimal expectedClosePrice = lowBid.add(new BigDecimal("0.5"));
        Assertions.assertEquals(0,
                new BigDecimal(tradingTestSupportMapper.selectPositionClosePriceById(positionId))
                        .compareTo(expectedClosePrice),
                "强平 closePrice 应为 bid + bidExtraAtOpen = " + expectedClosePrice
                        + "，实际：" + tradingTestSupportMapper.selectPositionClosePriceById(positionId));
    }

    /**
     * TC-GM-019b: 当 base.bid 跌破 liq 但 effective = bid + bidExtraAtOpen 仍在 liq 之上，
     * 不应触发强平（验证 markup 在判定中真的生效，而不只是显示）。
     */
    @Test
    void TC_GM_019b_no_liquidation_when_effective_mark_still_above_liq() {
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("100.0"),  // 大 markup 让效果明显
                        new BigDecimal("100.0"),
                        true, T0)));

        long userId = 97002L;
        OrderPlacementResult open = openPosition(userId, "vip",
                "stage12-liq-019b",
                new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"));
        Long positionId = open.position().positionId();
        BigDecimal liquidationPrice = new BigDecimal(
                tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId));

        // base.bid 比 liquidationPrice 低 50，但 bidExtraAtOpen=100 → effective 比 liq 还高 50
        BigDecimal baseBid = liquidationPrice.subtract(new BigDecimal("50.00000000"));
        BigDecimal baseAsk = baseBid.add(new BigDecimal("10.00000000"));
        PriceTickProcessingResult result = publishQuote("BTCUSDT", baseBid, baseAsk,
                liquidationPrice, OffsetDateTime.now());

        Assertions.assertEquals(0, result.triggeredActions(),
                "effective bid 仍在 liq 之上时不应触发强平，actual triggers="
                        + result.triggeredActions());
        // position 状态仍是 1 = OPEN
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
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

    private PriceTickProcessingResult publishQuote(String symbol, BigDecimal bid, BigDecimal ask,
                                                    BigDecimal mark, OffsetDateTime ts) {
        return quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
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
