package com.falconx.trading.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.PositionCloseResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.PositionTriggerRuleEvaluator;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingLiquidationLogRepository;
import com.falconx.trading.repository.TradingOutboxRepository;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingTradeRepository;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.service.TradingAccountService.PositionSettlementResult;
import com.falconx.trading.service.TradingRiskObservabilityService;
import com.falconx.trading.service.TradingScheduleService;
import com.falconx.trading.websocket.TradingUserRealtimePushService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * STAGE-14B Task 9b：平仓 / 强平 realized PnL 货币真值落账单测（close service 侧）。
 *
 * <p>用反射直接调 private {@code settlePositionExit}，聚焦验证：
 * realizedPnl(QC) × fxAtClose → 账户币 inAccount，并以正确口径传入 account service
 * {@code settlePositionExit}（inAccount 驱动 balance / 三列留原币 QC + fxAtClose）；
 * t_position.realizedPnl 展示字段用账户币 inAccount；FX 降级不阻断（强平仍完成）。
 */
class TradingPositionCloseRealizedPnlCurrencyTests {

    private static final BigDecimal QTY = new BigDecimal("10000");       // 0.1 lot EUR
    private static final BigDecimal ENTRY = new BigDecimal("1.60000000"); // EURAUD
    private static final BigDecimal MARGIN = new BigDecimal("82.50000000");
    private static final BigDecimal LIQ_PRICE = new BigDecimal("1.50000000");

    @Test
    void crossCurrencyProfit_realizedPnlConvertedToAccountCurrency() throws Exception {
        Mocks m = new Mocks();
        // EURAUD，quoteCurrency=AUD，账户币 USDT，fxAtClose(AUD→USDT)=0.65
        m.stubSymbolSpec("EURAUD", "EUR", "AUD");
        when(m.fxRateService.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.65")));

        TradingPositionCloseApplicationService service = m.buildService(true);
        // BUY 持仓平在 1.61（含 markup 0），realizedPnl(AUD) = (1.61-1.60)×10000 = 100 AUD
        TradingPosition position = m.newPosition(TradingOrderSide.BUY, "EURAUD");
        TradingQuoteSnapshot quote = m.newQuote("EURAUD", new BigDecimal("1.61000000"));

        TransactionSynchronizationManager.initSynchronization();
        PositionCloseResult result;
        try {
            result = (PositionCloseResult) m.invokeSettle(service, position, quote, TradingPositionCloseReason.MANUAL);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // account service 收到：inAccount = 100×0.65 = 65 USDT；三列原币 (100 AUD, "AUD", 0.65)
        ArgumentCaptor<BigDecimal> inAccount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> originalAmount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> originalCurrency = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> fxRate = ArgumentCaptor.forClass(BigDecimal.class);
        verify(m.accountService).settlePositionExit(
                any(), any(), inAccount.capture(), originalAmount.capture(), originalCurrency.capture(),
                fxRate.capture(), eq(TradingLedgerBizType.REALIZED_PNL), eq(false), anyString(), anyString(), any());

        assertEquals(new BigDecimal("65.00000000"), inAccount.getValue());
        assertEquals(new BigDecimal("100.00000000"), originalAmount.getValue());
        assertEquals("AUD", originalCurrency.getValue());
        assertEquals(new BigDecimal("0.65"), fxRate.getValue());

        // t_position.realizedPnl 展示字段 = 账户币 inAccount
        assertNotNull(result);
        assertEquals(new BigDecimal("65.00000000"), result.position().realizedPnl());
    }

    @Test
    void crossCurrencyLoss_negativeInAccount_halfUpCorrect() throws Exception {
        Mocks m = new Mocks();
        m.stubSymbolSpec("EURAUD", "EUR", "AUD");
        when(m.fxRateService.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.65")));

        TradingPositionCloseApplicationService service = m.buildService(true);
        // BUY 持仓平在 1.58，realizedPnl(AUD) = (1.58-1.60)×10000 = -200 AUD → inAccount = -130 USDT
        TradingPosition position = m.newPosition(TradingOrderSide.BUY, "EURAUD");
        TradingQuoteSnapshot quote = m.newQuote("EURAUD", new BigDecimal("1.58000000"));

        TransactionSynchronizationManager.initSynchronization();
        PositionCloseResult result;
        try {
            result = (PositionCloseResult) m.invokeSettle(service, position, quote, TradingPositionCloseReason.MANUAL);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        ArgumentCaptor<BigDecimal> inAccount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> originalAmount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(m.accountService).settlePositionExit(
                any(), any(), inAccount.capture(), originalAmount.capture(), eq("AUD"),
                eq(new BigDecimal("0.65")), eq(TradingLedgerBizType.REALIZED_PNL), eq(false), anyString(), anyString(), any());

        assertEquals(new BigDecimal("-130.00000000"), inAccount.getValue());
        assertEquals(new BigDecimal("-200.00000000"), originalAmount.getValue());
        assertEquals(new BigDecimal("-130.00000000"), result.position().realizedPnl());
    }

    @Test
    void liquidation_crossCurrency_bizTypeLiquidationPnl_threeColumnsTrue() throws Exception {
        Mocks m = new Mocks();
        m.stubSymbolSpec("EURAUD", "EUR", "AUD");
        when(m.fxRateService.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.65")));

        TradingPositionCloseApplicationService service = m.buildService(true);
        TradingPosition position = m.newPosition(TradingOrderSide.BUY, "EURAUD");
        TradingQuoteSnapshot quote = m.newQuote("EURAUD", new BigDecimal("1.58000000"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            m.invokeSettle(service, position, quote, TradingPositionCloseReason.LIQUIDATION);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // 强平：biz_type=LIQUIDATION_PNL、protectNegativeBalance=true、三列原币真值
        verify(m.accountService).settlePositionExit(
                any(), any(), eq(new BigDecimal("-130.00000000")), eq(new BigDecimal("-200.00000000")),
                eq("AUD"), eq(new BigDecimal("0.65")), eq(TradingLedgerBizType.LIQUIDATION_PNL),
                eq(true), anyString(), anyString(), any());
    }

    @Test
    void sameCurrency_usdtQuote_degradesToFxOne_noQuery() throws Exception {
        Mocks m = new Mocks();
        // 同币种：quoteCurrency=USDT=账户币 → fx=1，inAccount==inQuote，不查询 FX
        m.stubSymbolSpec("BTCUSDT", "BTC", "USDT");

        TradingPositionCloseApplicationService service = m.buildService(true);
        TradingPosition position = m.newPosition(TradingOrderSide.BUY, "BTCUSDT");
        TradingQuoteSnapshot quote = m.newQuote("BTCUSDT", new BigDecimal("1.61000000"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            m.invokeSettle(service, position, quote, TradingPositionCloseReason.MANUAL);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(m.fxRateService, org.mockito.Mockito.never()).queryRate(anyString(), anyString());
        verify(m.accountService).settlePositionExit(
                any(), any(), eq(new BigDecimal("100.00000000")), eq(new BigDecimal("100.00000000")),
                eq("USDT"), eq(BigDecimal.ONE), eq(TradingLedgerBizType.REALIZED_PNL),
                eq(false), anyString(), anyString(), any());
    }

    @Test
    void fxUnavailable_degradesAndDoesNotBlockLiquidation() throws Exception {
        Mocks m = new Mocks();
        m.stubSymbolSpec("EURAUD", "EUR", "AUD");
        // FX 不可用 → 降级，但强平必须仍完成
        when(m.fxRateService.queryRate("AUD", "USDT")).thenReturn(Optional.empty());

        TradingPositionCloseApplicationService service = m.buildService(true);
        TradingPosition position = m.newPosition(TradingOrderSide.BUY, "EURAUD");
        TradingQuoteSnapshot quote = m.newQuote("EURAUD", new BigDecimal("1.58000000"));

        TransactionSynchronizationManager.initSynchronization();
        PositionCloseResult result;
        try {
            result = (PositionCloseResult) m.invokeSettle(service, position, quote, TradingPositionCloseReason.LIQUIDATION);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // STAGE-14B Task 9b 收口 Issue 2：FX 不可用降级（QC=AUD 已知仅 rate 查不到）→ inAccount=原值 -200、fx=1，
        //   但 originalCurrency 如实记真实 quoteCurrency=AUD（不再瞎写账户币 USDT，避免原币值标账户币的审计误导）；
        //   强平仍完成（不抛异常）。
        assertNotNull(result);
        assertEquals(TradingPositionStatus.LIQUIDATED, result.position().status());
        verify(m.accountService).settlePositionExit(
                any(), any(), eq(new BigDecimal("-200.00000000")), eq(new BigDecimal("-200.00000000")),
                eq("AUD"), eq(BigDecimal.ONE), eq(TradingLedgerBizType.LIQUIDATION_PNL),
                eq(true), anyString(), anyString(), any());
    }

    @Test
    void symbolSpecMissing_degradesAndDoesNotBlock() throws Exception {
        Mocks m = new Mocks();
        when(m.symbolSpecRepo.findByPlatformSymbol(anyString())).thenReturn(Optional.empty());

        TradingPositionCloseApplicationService service = m.buildService(true);
        TradingPosition position = m.newPosition(TradingOrderSide.BUY, "EURAUD");
        TradingQuoteSnapshot quote = m.newQuote("EURAUD", new BigDecimal("1.61000000"));

        TransactionSynchronizationManager.initSynchronization();
        PositionCloseResult result;
        try {
            result = (PositionCloseResult) m.invokeSettle(service, position, quote, TradingPositionCloseReason.MANUAL);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(m.fxRateService, org.mockito.Mockito.never()).queryRate(anyString(), anyString());
        assertNotNull(result);
        // STAGE-14B Task 9b 收口 Issue 2：SymbolSpec 缺失降级 → 确实不知道 QC，originalCurrency 仍记账户币 USDT
        //   （与 FX 不可用分支区别：那时 QC 已知才改记 quoteCurrency）。inAccount=inQuote 原值 100、fx=1。
        verify(m.accountService).settlePositionExit(
                any(), any(), eq(new BigDecimal("100.00000000")), eq(new BigDecimal("100.00000000")),
                eq("USDT"), eq(BigDecimal.ONE), eq(TradingLedgerBizType.REALIZED_PNL),
                eq(false), anyString(), anyString(), any());
    }

    // ===== mock fixture =====
    private static final class Mocks {
        final TradingCoreServiceProperties properties = new TradingCoreServiceProperties();
        final TradingPositionRepository positionRepo = mock(TradingPositionRepository.class);
        final TradingTradeRepository tradeRepo = mock(TradingTradeRepository.class);
        final TradingQuoteSnapshotRepository quoteRepo = mock(TradingQuoteSnapshotRepository.class);
        final TradingAccountService accountService = mock(TradingAccountService.class);
        final TradingRiskObservabilityService riskObsService = mock(TradingRiskObservabilityService.class);
        final TradingOutboxRepository outboxRepo = mock(TradingOutboxRepository.class);
        final TradingLiquidationLogRepository liqLogRepo = mock(TradingLiquidationLogRepository.class);
        final TradingScheduleService scheduleService = mock(TradingScheduleService.class);
        final OpenPositionSnapshotStore snapshotStore = mock(OpenPositionSnapshotStore.class);
        final PositionTriggerRuleEvaluator triggerEval = mock(PositionTriggerRuleEvaluator.class);
        final TradingUserRealtimePushService realtimeService = mock(TradingUserRealtimePushService.class);
        final TradingPendingOrderTriggerRepository pendingRepo = mock(TradingPendingOrderTriggerRepository.class);
        final IdGenerator idGenerator = mock(IdGenerator.class);
        final MarketSymbolSpecRepository symbolSpecRepo = mock(MarketSymbolSpecRepository.class);
        final FxRateService fxRateService = mock(FxRateService.class);

        Mocks() {
            when(accountService.getExistingAccountForUpdate(anyLong(), anyString())).thenReturn(newAccount());
            // appliedPnl 与 platformCoveredLoss 在本套测试不强校验，回 inAccount 原值占位即可。
            when(accountService.settlePositionExit(
                    any(), any(), any(), any(), anyString(), any(), any(), anyBoolean(),
                    anyString(), anyString(), any()))
                    .thenAnswer(inv -> new PositionSettlementResult(
                            newAccount(), inv.getArgument(2), BigDecimal.ZERO.setScale(8)));
            when(positionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(tradeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(idGenerator.nextId()).thenReturn(7777L);
        }

        void stubSymbolSpec(String symbol, String base, String quote) {
            // STAGE-14C2 Task 1：crypto base（BTC 等）→1，其余 FX 对→2
            Integer category = ("BTC".equals(base) || "ETH".equals(base) || "XRP".equals(base)) ? 1 : 2;
            when(symbolSpecRepo.findByPlatformSymbol(symbol)).thenReturn(Optional.of(
                    new SymbolSpec(symbol, 200, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ONE, new BigDecimal("100000"), BigDecimal.ZERO,
                            5, 2, base, quote, category)));
        }

        TradingPositionCloseApplicationService buildService(boolean asyncEnabled) {
            return new TradingPositionCloseApplicationService(
                    properties, positionRepo, tradeRepo, quoteRepo, accountService,
                    riskObsService, outboxRepo, liqLogRepo, scheduleService,
                    snapshotStore, triggerEval, realtimeService,
                    pendingRepo, null, idGenerator, asyncEnabled,
                    symbolSpecRepo, fxRateService);
        }

        Object invokeSettle(TradingPositionCloseApplicationService service,
                            TradingPosition position,
                            TradingQuoteSnapshot quote,
                            TradingPositionCloseReason reason) throws Exception {
            Method mth = TradingPositionCloseApplicationService.class.getDeclaredMethod(
                    "settlePositionExit",
                    TradingPosition.class, TradingQuoteSnapshot.class,
                    TradingPositionCloseReason.class, OffsetDateTime.class);
            mth.setAccessible(true);
            return mth.invoke(service, position, quote, reason, OffsetDateTime.now());
        }

        TradingPosition newPosition(TradingOrderSide side, String symbol) {
            OffsetDateTime now = OffsetDateTime.now();
            return new TradingPosition(
                    200L, 100L, 7L, symbol, side,
                    QTY, ENTRY, BigDecimal.ONE, new BigDecimal("0.005000"), 1, new BigDecimal("200"), MARGIN,
                    TradingMarginMode.ISOLATED, LIQ_PRICE,
                    null, null, null, null, null,
                    TradingPositionStatus.OPEN,
                    BigDecimal.ZERO, "default", BigDecimal.ZERO, BigDecimal.ZERO,
                    now, null, now);
        }

        TradingQuoteSnapshot newQuote(String symbol, BigDecimal price) {
            return new TradingQuoteSnapshot(
                    symbol, price, price, price,
                    OffsetDateTime.now(), "TEST", false,
                    TradingQuoteQualityStatus.FRESH, null);
        }

        TradingAccount newAccount() {
            OffsetDateTime now = OffsetDateTime.now();
            return new TradingAccount(
                    100L, 7L, "USDT",
                    new BigDecimal("500.00000000"),
                    BigDecimal.ZERO.setScale(8),
                    MARGIN,
                    TradingMarginMode.ISOLATED,
                    null, null,
                    now, now);
        }
    }
}
