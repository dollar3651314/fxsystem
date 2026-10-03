package com.falconx.trading.application;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.TradingAccountPositionResponse;
import com.falconx.trading.dto.TradingAccountResponse;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.entity.MarginLevelStatus;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.service.AccountEquityCalculator;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.service.MarginLevelMonitor;
import com.falconx.trading.service.impl.DefaultAccountEquityCalculator;
import com.falconx.trading.service.model.AccountMarginState;
import com.falconx.trading.websocket.TradingUserRealtimePayloadFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * STAGE-14E1 Task 2：{@link TradingAccountSnapshotApplicationService#toResponse} 实时算
 * equity / marginLevel / marginLevelStatus + openPositions 硬切双币单测。
 *
 * <p>master §7.5：account.update / 快照（与 REST GET account 共用 toResponse）随 position 一并由单币切双币：
 * 账户头加 {@code equity / marginLevel / marginLevelStatus}（口径复用 {@link AccountEquityCalculator}
 * + {@link MarginLevelMonitor}），嵌套 openPositions 由 {@link TradingAccountPositionResponse} 删 unrealizedPnl
 * 加 {@code quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin}
 * （口径对齐 TradingPositionItemResponse，复用 payload 工厂，FX 不可用降级 entryFxRate 不抛）。
 */
class TradingAccountSnapshotApplicationServiceTests {

    private static final String SETTLEMENT = "USDT";

    /**
     * EURAUD ISOLATED 仓 + fx：
     * uPnL(AUD)=(1.6498-1.6500)*10000=-2 → ×fx0.65=-1.30 USDT；equity/marginLevel 与 calculator 一致；
     * openPositions[0] 含双币字段（无旧 unrealizedPnl）。
     */
    @Test
    void toResponse_withOpenPosition_dualCurrencyAndRealtimeMarginLevel() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.65000000")));
        TradingQuoteSnapshotRepository quoteRepo = Mockito.mock(TradingQuoteSnapshotRepository.class);
        Mockito.when(quoteRepo.findBySymbol("EURAUD")).thenReturn(Optional.of(eurAudQuote()));
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        Mockito.when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(symbolSpec("AUD")));
        OpenPositionSnapshotStore store = Mockito.mock(OpenPositionSnapshotStore.class);
        TradingPosition position = eurAudPosition(TradingMarginMode.ISOLATED);
        Mockito.when(store.listOpenByUserId(1L)).thenReturn(List.of(position));

        AccountEquityCalculator calculator = new DefaultAccountEquityCalculator(fx);
        MarginLevelMonitor monitor = Mockito.mock(MarginLevelMonitor.class);
        Mockito.when(monitor.evaluate(ArgumentMatchers.eq(1L), ArgumentMatchers.any(AccountMarginState.class)))
                .thenReturn(MarginLevelStatus.HEALTHY);

        TradingAccountSnapshotApplicationService service = newService(
                store, quoteRepo, fx, specRepo, calculator, monitor);

        TradingAccount account = account(new BigDecimal("1000"), BigDecimal.ZERO);
        TradingAccountResponse response = service.toResponse(account);

        // 账户头：equity / marginLevel 与 calculator 同口径
        AccountMarginState expected = calculator.computeAccountMarginLevel(
                account,
                List.of(new AccountEquityCalculator.PositionMarkInput(
                        position, new BigDecimal("1.64980000"), "AUD")));
        Assertions.assertNotNull(response.equity(), "equity 非空");
        Assertions.assertEquals(0, response.equity().compareTo(expected.equity()),
                "equity 与 calculator 一致（balance+frozen+ΣuPnL(AC)）");
        Assertions.assertNotNull(response.marginLevel(), "有持仓 marginLevel 非空");
        Assertions.assertEquals(0, response.marginLevel().compareTo(expected.marginLevel()),
                "marginLevel 与 calculator 一致");
        Assertions.assertEquals(MarginLevelStatus.HEALTHY.name(), response.marginLevelStatus(),
                "marginLevelStatus 来自 MarginLevelMonitor");
        Assertions.assertEquals(TradingMarginMode.ISOLATED.name(), response.marginMode());

        // openPositions 双币
        Assertions.assertEquals(1, response.openPositions().size());
        TradingAccountPositionResponse p = response.openPositions().get(0);
        Assertions.assertEquals("AUD", p.quoteCurrency());
        Assertions.assertEquals(0, p.fxRate().compareTo(new BigDecimal("0.65")));
        Assertions.assertEquals(0, p.unrealizedPnlInQuote().compareTo(new BigDecimal("-2")));
        Assertions.assertEquals(0, p.unrealizedPnlInAccount().compareTo(new BigDecimal("-1.30")));
        Assertions.assertNotNull(p.isolatedMargin(), "ISOLATED 仓 isolatedMargin 非空");
        Assertions.assertEquals(0, p.isolatedMargin().compareTo(new BigDecimal("53.625")));
    }

    /** 无持仓：equity=balance+frozen、marginLevel=null（calculator 约定）、status 合理、openPositions 空。 */
    @Test
    void toResponse_noPosition_equityBalancePlusFrozen_marginLevelNull() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        TradingQuoteSnapshotRepository quoteRepo = Mockito.mock(TradingQuoteSnapshotRepository.class);
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        OpenPositionSnapshotStore store = Mockito.mock(OpenPositionSnapshotStore.class);
        Mockito.when(store.listOpenByUserId(1L)).thenReturn(List.of());

        AccountEquityCalculator calculator = new DefaultAccountEquityCalculator(fx);
        MarginLevelMonitor monitor = Mockito.mock(MarginLevelMonitor.class);
        Mockito.when(monitor.evaluate(ArgumentMatchers.eq(1L), ArgumentMatchers.any(AccountMarginState.class)))
                .thenReturn(MarginLevelStatus.HEALTHY);

        TradingAccountSnapshotApplicationService service = newService(
                store, quoteRepo, fx, specRepo, calculator, monitor);

        TradingAccount account = account(new BigDecimal("1000"), new BigDecimal("50"));
        TradingAccountResponse response = service.toResponse(account);

        Assertions.assertEquals(0, response.equity().compareTo(new BigDecimal("1050")),
                "无持仓 equity=balance+frozen");
        Assertions.assertNull(response.marginLevel(), "无持仓 marginLevel=null（calculator 约定）");
        Assertions.assertEquals(MarginLevelStatus.HEALTHY.name(), response.marginLevelStatus());
        Assertions.assertTrue(response.openPositions().isEmpty());
    }

    /** FX 不可用：不抛；equity/marginLevel 降级 null；openPositions 仍走 entryFxRate 降级双币。 */
    @Test
    void toResponse_fxUnavailable_doesNotThrow() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.empty());
        TradingQuoteSnapshotRepository quoteRepo = Mockito.mock(TradingQuoteSnapshotRepository.class);
        Mockito.when(quoteRepo.findBySymbol("EURAUD")).thenReturn(Optional.of(eurAudQuote()));
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        Mockito.when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(symbolSpec("AUD")));
        OpenPositionSnapshotStore store = Mockito.mock(OpenPositionSnapshotStore.class);
        Mockito.when(store.listOpenByUserId(1L)).thenReturn(List.of(eurAudPosition(TradingMarginMode.ISOLATED)));

        AccountEquityCalculator calculator = new DefaultAccountEquityCalculator(fx);
        MarginLevelMonitor monitor = Mockito.mock(MarginLevelMonitor.class);
        Mockito.when(monitor.evaluate(ArgumentMatchers.eq(1L), ArgumentMatchers.any(AccountMarginState.class)))
                .thenReturn(MarginLevelStatus.HEALTHY);

        TradingAccountSnapshotApplicationService service = newService(
                store, quoteRepo, fx, specRepo, calculator, monitor);

        TradingAccount account = account(new BigDecimal("1000"), BigDecimal.ZERO);
        TradingAccountResponse response = Assertions.assertDoesNotThrow(() -> service.toResponse(account));

        // 账户级：calculator 在任一仓 FX 不可用时把 equity/marginLevel 降级 null（不抛）
        Assertions.assertNull(response.equity(), "FX 不可用 equity 降级 null");
        Assertions.assertNull(response.marginLevel(), "FX 不可用 marginLevel 降级 null");
        // openPositions 双币走工厂 entryFxRate 降级（不抛）
        TradingAccountPositionResponse p = response.openPositions().get(0);
        Assertions.assertEquals(0, p.fxRate().compareTo(new BigDecimal("0.65")),
                "openPositions FX 不可用降级 entryFxRate=0.65");
        Assertions.assertEquals(0, p.unrealizedPnlInAccount().compareTo(new BigDecimal("-1.30")));
    }

    // --------------------------------------------------------------------- helpers

    private static TradingAccountSnapshotApplicationService newService(
            OpenPositionSnapshotStore store,
            TradingQuoteSnapshotRepository quoteRepo,
            FxRateService fx,
            MarketSymbolSpecRepository specRepo,
            AccountEquityCalculator calculator,
            MarginLevelMonitor monitor) {
        TradingCoreServiceProperties props = new TradingCoreServiceProperties();
        props.setSettlementToken(SETTLEMENT);
        TradingUserRealtimePayloadFactory factory =
                new TradingUserRealtimePayloadFactory(quoteRepo, fx, specRepo, SETTLEMENT);
        return new TradingAccountSnapshotApplicationService(
                Mockito.mock(com.falconx.trading.service.TradingAccountService.class),
                props, store, quoteRepo, calculator, monitor, factory, specRepo);
    }

    private static TradingAccount account(BigDecimal balance, BigDecimal frozen) {
        return new TradingAccount(1L, 1L, SETTLEMENT, balance, frozen, BigDecimal.ZERO,
                TradingMarginMode.ISOLATED, null, null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static TradingQuoteSnapshot eurAudQuote() {
        return new TradingQuoteSnapshot("EURAUD",
                new BigDecimal("1.6498"),
                new BigDecimal("1.6502"),
                new BigDecimal("1.6500"),
                OffsetDateTime.now(), "TEST", false);
    }

    private static SymbolSpec symbolSpec(String quoteCurrency) {
        return new SymbolSpec("EURAUD", null, null, null, null, null, null, null, null,
                "EUR", quoteCurrency, 1);
    }

    private static TradingPosition eurAudPosition(TradingMarginMode mode) {
        return new TradingPosition(
                1L, 1L, 1L, "EURAUD",
                TradingOrderSide.BUY,
                new BigDecimal("10000"),
                new BigDecimal("1.6500"),
                new BigDecimal("0.65000000"),
                new BigDecimal("0.005000"),
                1,
                new BigDecimal("200"),
                new BigDecimal("53.625"),
                mode,
                mode == TradingMarginMode.ISOLATED ? new BigDecimal("1.6000") : null,
                null, null, null, null, null,
                TradingPositionStatus.OPEN,
                BigDecimal.ZERO,
                "default",
                BigDecimal.ZERO, BigDecimal.ZERO,
                OffsetDateTime.now(), null, OffsetDateTime.now()
        );
    }
}
