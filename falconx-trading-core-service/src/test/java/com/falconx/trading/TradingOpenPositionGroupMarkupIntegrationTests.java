package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.event.MarketGroupMarkupItem;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
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
 * STAGE-12-GROUP-MARKUP TC-GM-014~015: 开仓时 fillPrice + position 落库冻结 markup 集成测试。
 *
 * <p>用 @MockitoBean 替换 {@link TradingGroupMarkupService}，避开对 market-service RPC 的依赖。
 * 验证：
 * <ul>
 *   <li>TC-GM-014 vip 组 BUY 开仓 fillPrice = base.ask + askExtra</li>
 *   <li>TC-GM-014b SELL 开仓 fillPrice = base.bid + bidExtra</li>
 *   <li>TC-GM-015 t_position 落 group_code_at_open / bid_extra_at_open / ask_extra_at_open 冻结值</li>
 *   <li>TC-GM-015b 默认组 / 无 markup 配置 → bidExtra/askExtra=0，groupCode=default</li>
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
class TradingOpenPositionGroupMarkupIntegrationTests {

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

        // 默认 mock：任何 find 都返回空（即 0 加点），由各用例单独 override
        Mockito.reset(tradingGroupMarkupService);
        Mockito.when(tradingGroupMarkupService.find(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Optional.empty());
    }

    /** TC-GM-014: vip 组 BUY 开仓 fillPrice = base.ask + askExtra（含 markup）。 */
    @Test
    void TC_GM_014_vip_group_buy_fill_price_includes_ask_extra() {
        // markup: bid_extra=0.5, ask_extra=1.0 for vip+BTCUSDT
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("0.5"), new BigDecimal("1.0"),
                        true, T0)));

        long userId = 95001L;
        OrderPlacementResult result = openPositionWithGroup(userId, "vip", TradingOrderSide.BUY,
                "stage12-vip-buy-001",
                new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"));

        Assertions.assertNotNull(result.position(), "vip 组开仓应成功");
        // fillPrice = ask(10000.00) + askExtra(1.0) = 10001.0
        Assertions.assertEquals(0, result.position().entryPrice().compareTo(new BigDecimal("10001.00000000")),
                "vip 组 BUY fillPrice 应为 ask+askExtra=10001，实际：" + result.position().entryPrice());
    }

    /** TC-GM-014b: vip 组 SELL 开仓 fillPrice = base.bid + bidExtra。 */
    @Test
    void TC_GM_014b_vip_group_sell_fill_price_includes_bid_extra() {
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("0.5"), new BigDecimal("1.0"),
                        true, T0)));

        long userId = 95002L;
        OrderPlacementResult result = openPositionWithGroup(userId, "vip", TradingOrderSide.SELL,
                "stage12-vip-sell-001",
                new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"));

        Assertions.assertNotNull(result.position());
        // SELL fillPrice = bid(9990.00) + bidExtra(0.5) = 9990.5
        Assertions.assertEquals(0, result.position().entryPrice().compareTo(new BigDecimal("9990.50000000")),
                "vip 组 SELL fillPrice 应为 bid+bidExtra=9990.5，实际：" + result.position().entryPrice());
    }

    /** TC-GM-015: t_position 落 group_code_at_open / bid_extra_at_open / ask_extra_at_open 冻结值。 */
    @Test
    void TC_GM_015_position_freezes_group_code_bid_ask_extra() {
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("0.5"), new BigDecimal("1.0"),
                        true, T0)));

        long userId = 95003L;
        OrderPlacementResult result = openPositionWithGroup(userId, "vip", TradingOrderSide.BUY,
                "stage12-freeze-001",
                new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"));

        TradingPosition position = result.position();
        Assertions.assertEquals("vip", position.groupCodeAtOpen(),
                "position 应冻结 groupCode=vip");
        Assertions.assertEquals(0, position.bidExtraAtOpen().compareTo(new BigDecimal("0.5")),
                "position 应冻结 bidExtraAtOpen=0.5，实际：" + position.bidExtraAtOpen());
        Assertions.assertEquals(0, position.askExtraAtOpen().compareTo(new BigDecimal("1.0")),
                "position 应冻结 askExtraAtOpen=1.0，实际：" + position.askExtraAtOpen());
    }

    /** TC-GM-015b: 默认组（无 markup 配置）→ bid/ask Extra 落库 = 0，groupCode = default。 */
    @Test
    void TC_GM_015b_default_group_falls_back_to_zero_markup() {
        // 不 mock find，使用 @BeforeEach 默认的 Optional.empty()

        long userId = 95004L;
        OrderPlacementResult result = openPositionWithGroup(userId, null, TradingOrderSide.BUY,
                "stage12-default-001",
                new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"));

        TradingPosition position = result.position();
        // fillPrice 不含 markup
        Assertions.assertEquals(0, position.entryPrice().compareTo(new BigDecimal("10000.00000000")),
                "默认组 fillPrice 应等于 base.ask=10000，实际：" + position.entryPrice());
        Assertions.assertEquals("default", position.groupCodeAtOpen());
        Assertions.assertEquals(0, position.bidExtraAtOpen().compareTo(BigDecimal.ZERO));
        Assertions.assertEquals(0, position.askExtraAtOpen().compareTo(BigDecimal.ZERO));
    }

    private OrderPlacementResult openPositionWithGroup(long userId, String groupCode,
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
