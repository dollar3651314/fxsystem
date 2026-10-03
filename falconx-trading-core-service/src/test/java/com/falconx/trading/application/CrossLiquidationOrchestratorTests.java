package com.falconx.trading.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.dto.PositionCloseResult;
import com.falconx.trading.engine.AccountMarginStateCache;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.service.AccountEquityCalculator;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.service.MarginLevelMonitor;
import com.falconx.trading.service.model.AccountMarginState;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * {@link CrossLiquidationOrchestrator} 单元测试（STAGE-14D2 Task 4）。
 *
 * <p>锁定 master §6.3 CROSS 账户级强平：账户级 ML ≤ stopOut → 按 |uPnL(AC)| 降序逐仓平 + 每平一仓重算 ML
 * → ML 恢复（> stopOut）即止步；ML > stopOut 不平；Redisson user-level 锁 tryLock 失败跳过本次（不平）。
 */
class CrossLiquidationOrchestratorTests {

    private static final String ACCOUNT_CURRENCY = "USDT";
    private static final long USER_ID = 7001L;

    /** 测试夹具：打包 orchestrator + 全部 mock 依赖。 */
    private record Fixture(CrossLiquidationOrchestrator orchestrator,
                           OpenPositionSnapshotStore snapshotStore,
                           AccountMarginStateCache accountCache,
                           AccountEquityCalculator equityCalculator,
                           MarketSymbolSpecRepository symbolSpecRepository,
                           TradingQuoteSnapshotRepository quoteRepository,
                           FxRateService fxRateService,
                           MarginLevelMonitor marginLevelMonitor,
                           TradingPositionCloseApplicationService closeService,
                           TradingNotificationApplicationService notificationService,
                           RedissonClient redisson,
                           RLock lock) {
    }

    private Fixture newFixture(boolean lockAcquired) {
        OpenPositionSnapshotStore snapshotStore = mock(OpenPositionSnapshotStore.class);
        AccountMarginStateCache accountCache = mock(AccountMarginStateCache.class);
        AccountEquityCalculator equityCalculator = mock(AccountEquityCalculator.class);
        MarketSymbolSpecRepository symbolSpecRepository = mock(MarketSymbolSpecRepository.class);
        TradingQuoteSnapshotRepository quoteRepository = mock(TradingQuoteSnapshotRepository.class);
        FxRateService fxRateService = mock(FxRateService.class);
        MarginLevelMonitor marginLevelMonitor = mock(MarginLevelMonitor.class);
        TradingPositionCloseApplicationService closeService =
                mock(TradingPositionCloseApplicationService.class);
        TradingNotificationApplicationService notificationService =
                mock(TradingNotificationApplicationService.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);

        when(redisson.getLock(eq("cross-liq:" + USER_ID))).thenReturn(lock);
        try {
            when(lock.tryLock(eq(0L), any(TimeUnit.class))).thenReturn(lockAcquired);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        when(lock.isHeldByCurrentThread()).thenReturn(lockAcquired);
        // stopOut 阈值来源：MarginLevelMonitor.currentStopOutLevel（默认 0.30）
        when(marginLevelMonitor.currentStopOutLevel()).thenReturn(new BigDecimal("0.30"));

        CrossLiquidationOrchestrator orchestrator = new CrossLiquidationOrchestrator(
                snapshotStore, accountCache, equityCalculator, symbolSpecRepository,
                quoteRepository, fxRateService, marginLevelMonitor, closeService, notificationService,
                redisson, ACCOUNT_CURRENCY);
        return new Fixture(orchestrator, snapshotStore, accountCache, equityCalculator,
                symbolSpecRepository, quoteRepository, fxRateService, marginLevelMonitor,
                closeService, notificationService, redisson, lock);
    }

    private TradingAccount account() {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        return new TradingAccount(9001L, USER_ID, ACCOUNT_CURRENCY,
                new BigDecimal("1000.00000000"), BigDecimal.ZERO, new BigDecimal("3000.00000000"),
                TradingMarginMode.CROSS, null, null, now, now);
    }

    private TradingPosition crossPosition(long positionId, String symbol) {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        return new TradingPosition(
                positionId, positionId + 100, USER_ID, symbol, TradingOrderSide.BUY,
                new BigDecimal("1.00000000"), new BigDecimal("100.00000000"), BigDecimal.ONE,
                new BigDecimal("0.010000"), 1, new BigDecimal("10"), new BigDecimal("1000.00000000"),
                TradingMarginMode.CROSS,
                null, null, null, null, null, null,
                TradingPositionStatus.OPEN, BigDecimal.ZERO, "default",
                BigDecimal.ZERO, BigDecimal.ZERO, now, null, now);
    }

    private TradingQuoteSnapshot quote(String symbol) {
        return new TradingQuoteSnapshot(symbol,
                new BigDecimal("100.00000000"), new BigDecimal("100.02000000"),
                new BigDecimal("100.01000000"),
                OffsetDateTime.parse("2026-06-01T08:00:00Z"), "TM_QUOTE", false,
                TradingQuoteQualityStatus.FRESH, null);
    }

    private SymbolSpec spec(String symbol) {
        return new SymbolSpec(symbol, 100, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.00000001"), new BigDecimal("1000000000"), BigDecimal.ZERO,
                8, 8, "BTC", ACCOUNT_CURRENCY, 1);
    }

    private void stubSpecAndQuote(Fixture f, String... symbols) {
        for (String s : symbols) {
            when(f.symbolSpecRepository.findByPlatformSymbol(s)).thenReturn(Optional.of(spec(s)));
            when(f.quoteRepository.findBySymbol(s)).thenReturn(Optional.of(quote(s)));
        }
    }

    private PositionCloseResult closeResult(TradingPosition pos) {
        TradingPosition closed = pos.close(TradingPositionStatus.LIQUIDATED,
                TradingPositionCloseReason.CROSS_STOP_OUT,
                new BigDecimal("100.01000000"), new BigDecimal("-100.00000000"),
                OffsetDateTime.parse("2026-06-01T08:00:00Z"));
        return new PositionCloseResult(closed, null, null, null);
    }

    /** ML≤30%，3 仓 |uPnL| -500/-300/-100 → 平 -500 后仍≤30% 平 -300，恢复则止步（断言顺序 + 平 2 仓不平第 3）。 */
    @Test
    void shouldLiquidateInUpnlDescOrderUntilRecovered() {
        Fixture f = newFixture(true);
        TradingPosition pSmall = crossPosition(301L, "AAAUSDT");  // |uPnL|=100
        TradingPosition pMid = crossPosition(302L, "BBBUSDT");    // |uPnL|=300
        TradingPosition pBig = crossPosition(303L, "CCCUSDT");    // |uPnL|=500
        stubSpecAndQuote(f, "AAAUSDT", "BBBUSDT", "CCCUSDT");

        // 初始 3 仓（乱序），账户读
        when(f.snapshotStore.listOpenByUserId(USER_ID))
                .thenReturn(List.of(pSmall, pBig, pMid))   // 评估首轮：3 仓
                .thenReturn(List.of(pSmall, pMid))         // 平 pBig 后：剩 2 仓
                .thenReturn(List.of(pSmall));              // 平 pMid 后：剩 1 仓
        when(f.accountCache.getAccount(USER_ID, ACCOUNT_CURRENCY)).thenReturn(account());

        // uPnL(AC)：pBig=-500, pMid=-300, pSmall=-100（用 fxRateService 同币种=1，PnL 由 mark-entry 决定，
        //   这里直接 stub FxRateService 不需要；uPnL 排序用 calculatePositionPnlInAccount 真实算 → 故用真 quote/entry。
        //   但为隔离排序逻辑，改为通过 equityCalculator 不影响排序——排序在 orchestrator 内用 PricingSupport 真实算，
        //   因此用 mark 价造 uPnL：BUY 持仓 uPnL=(mark-entry)*qty。entry=100，qty=1，mark 越低亏越大。
        //   为简化，本用例直接让排序基于 quote mark 差：故给不同 symbol 不同 quote。
        when(f.quoteRepository.findBySymbol("CCCUSDT")).thenReturn(Optional.of(markQuote("CCCUSDT", "99.50")));  // -0.5*qty... 需放大
        // 用更直观的方式：mark 让 |uPnL| 明显不同
        when(f.quoteRepository.findBySymbol("CCCUSDT")).thenReturn(Optional.of(markQuote("CCCUSDT", "95.00"))); // uPnL=-5
        when(f.quoteRepository.findBySymbol("BBBUSDT")).thenReturn(Optional.of(markQuote("BBBUSDT", "97.00"))); // uPnL=-3
        when(f.quoteRepository.findBySymbol("AAAUSDT")).thenReturn(Optional.of(markQuote("AAAUSDT", "99.00"))); // uPnL=-1

        // ML 序列：首轮（3 仓）20% ≤30 → 平第 1（pBig）；重算（2 仓）25% ≤30 → 平第 2（pMid）；重算（1 仓）40% >30 → 止步
        when(f.equityCalculator.computeAccountMarginLevel(any(), any()))
                .thenReturn(ml("20.00"))
                .thenReturn(ml("25.00"))
                .thenReturn(ml("40.00"));

        when(f.closeService.closePositionByTrigger(eq(303L), eq(TradingPositionCloseReason.CROSS_STOP_OUT), any()))
                .thenReturn(closeResult(pBig));
        when(f.closeService.closePositionByTrigger(eq(302L), eq(TradingPositionCloseReason.CROSS_STOP_OUT), any()))
                .thenReturn(closeResult(pMid));

        f.orchestrator.evaluateAndLiquidate(USER_ID, ACCOUNT_CURRENCY);

        // 平 2 仓：pBig(303) 先，pMid(302) 后；pSmall(301) 不平
        InOrder order = inOrder(f.closeService);
        order.verify(f.closeService).closePositionByTrigger(eq(303L), eq(TradingPositionCloseReason.CROSS_STOP_OUT), any());
        order.verify(f.closeService).closePositionByTrigger(eq(302L), eq(TradingPositionCloseReason.CROSS_STOP_OUT), any());
        verify(f.closeService, never()).closePositionByTrigger(eq(301L), any(), any());
        // 每平一仓失效账户缓存
        verify(f.accountCache, times(2)).invalidate(USER_ID);

        // STAGE-14D2 Task 5：强平 2 仓 → 发 1 条账户级 CROSS_STOP_OUT_TRIGGERED 汇总通知
        //   （count=2，symbols 含两强平 symbol，marginLevel=触发时 ML=20.00），relatedKey=ACCOUNT。
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(f.notificationService, times(1)).send(
                eq("CROSS_STOP_OUT_TRIGGERED"), eq(USER_ID), eq("CROSS_STOP_OUT_TRIGGERED"),
                paramsCaptor.capture(), eq("ACCOUNT"), eq(9001L), any());
        Map<String, String> params = paramsCaptor.getValue();
        Assertions.assertEquals("2", params.get("count"), "强平仓数应为 2");
        Assertions.assertEquals("20.00", params.get("marginLevel"), "marginLevel 应为触发时账户 ML");
        Assertions.assertTrue(params.get("symbols").contains("CCCUSDT")
                        && params.get("symbols").contains("BBBUSDT"),
                "symbols 应含两强平 symbol，实际=" + params.get("symbols"));

        // 锁释放
        try {
            verify(f.lock).unlock();
        } catch (Exception ignored) {
        }
    }

    private TradingQuoteSnapshot markQuote(String symbol, String mark) {
        BigDecimal m = new BigDecimal(mark);
        return new TradingQuoteSnapshot(symbol, m, m,
                m, OffsetDateTime.parse("2026-06-01T08:00:00Z"), "TM_QUOTE", false,
                TradingQuoteQualityStatus.FRESH, null);
    }

    private AccountMarginState ml(String percent) {
        return new AccountMarginState(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal(percent));
    }

    /** ML > 30% → 不平任何仓。 */
    @Test
    void shouldNotLiquidateWhenMarginLevelHealthy() {
        Fixture f = newFixture(true);
        TradingPosition p = crossPosition(401L, "AAAUSDT");
        stubSpecAndQuote(f, "AAAUSDT");
        when(f.snapshotStore.listOpenByUserId(USER_ID)).thenReturn(List.of(p));
        when(f.accountCache.getAccount(USER_ID, ACCOUNT_CURRENCY)).thenReturn(account());
        when(f.equityCalculator.computeAccountMarginLevel(any(), any())).thenReturn(ml("150.00"));

        f.orchestrator.evaluateAndLiquidate(USER_ID, ACCOUNT_CURRENCY);

        verify(f.closeService, never()).closePositionByTrigger(any(), any(), any());
        // 未强平 → 不发账户级通知。
        verify(f.notificationService, never()).send(any(), anyLong(), any(), any(), any(), any(), any());
    }

    /** ML == null（FX 不可用降级 / 无持仓）→ 不平。 */
    @Test
    void shouldNotLiquidateWhenMarginLevelNull() {
        Fixture f = newFixture(true);
        TradingPosition p = crossPosition(402L, "AAAUSDT");
        stubSpecAndQuote(f, "AAAUSDT");
        when(f.snapshotStore.listOpenByUserId(USER_ID)).thenReturn(List.of(p));
        when(f.accountCache.getAccount(USER_ID, ACCOUNT_CURRENCY)).thenReturn(account());
        when(f.equityCalculator.computeAccountMarginLevel(any(), any()))
                .thenReturn(new AccountMarginState(null, BigDecimal.ZERO, null));

        f.orchestrator.evaluateAndLiquidate(USER_ID, ACCOUNT_CURRENCY);

        verify(f.closeService, never()).closePositionByTrigger(any(), any(), any());
    }

    /** tryLock 失败 → 直接跳过（不读持仓、不平、不 unlock）。 */
    @Test
    void shouldSkipWhenLockNotAcquired() {
        Fixture f = newFixture(false);

        f.orchestrator.evaluateAndLiquidate(USER_ID, ACCOUNT_CURRENCY);

        verify(f.snapshotStore, never()).listOpenByUserId(anyLong());
        verify(f.closeService, never()).closePositionByTrigger(any(), any(), any());
        try {
            verify(f.lock, never()).unlock();
        } catch (Exception ignored) {
        }
    }

    /** 平第一仓后 ML 恢复 → 只平 1 仓（不平剩余）。 */
    @Test
    void shouldStopAfterFirstWhenRecovered() {
        Fixture f = newFixture(true);
        TradingPosition pBig = crossPosition(501L, "CCCUSDT");
        TradingPosition pSmall = crossPosition(502L, "AAAUSDT");
        stubSpecAndQuote(f, "CCCUSDT", "AAAUSDT");
        when(f.quoteRepository.findBySymbol("CCCUSDT")).thenReturn(Optional.of(markQuote("CCCUSDT", "95.00")));
        when(f.quoteRepository.findBySymbol("AAAUSDT")).thenReturn(Optional.of(markQuote("AAAUSDT", "99.00")));
        when(f.snapshotStore.listOpenByUserId(USER_ID))
                .thenReturn(List.of(pSmall, pBig))
                .thenReturn(List.of(pSmall));
        when(f.accountCache.getAccount(USER_ID, ACCOUNT_CURRENCY)).thenReturn(account());
        // 首轮 25% ≤30 → 平 pBig；重算 80% >30 → 止步
        when(f.equityCalculator.computeAccountMarginLevel(any(), any()))
                .thenReturn(ml("25.00"))
                .thenReturn(ml("80.00"));
        when(f.closeService.closePositionByTrigger(eq(501L), eq(TradingPositionCloseReason.CROSS_STOP_OUT), any()))
                .thenReturn(closeResult(pBig));

        f.orchestrator.evaluateAndLiquidate(USER_ID, ACCOUNT_CURRENCY);

        verify(f.closeService, times(1)).closePositionByTrigger(eq(501L), eq(TradingPositionCloseReason.CROSS_STOP_OUT), any());
        verify(f.closeService, never()).closePositionByTrigger(eq(502L), any(), any());
    }

    /** 空持仓 → 直接返回不平（仍释放锁）。 */
    @Test
    void shouldReturnWhenNoOpenPositions() {
        Fixture f = newFixture(true);
        when(f.snapshotStore.listOpenByUserId(USER_ID)).thenReturn(List.of());

        f.orchestrator.evaluateAndLiquidate(USER_ID, ACCOUNT_CURRENCY);

        verify(f.closeService, never()).closePositionByTrigger(any(), any(), any());
        try {
            verify(f.lock).unlock();
        } catch (Exception ignored) {
        }
    }
}
