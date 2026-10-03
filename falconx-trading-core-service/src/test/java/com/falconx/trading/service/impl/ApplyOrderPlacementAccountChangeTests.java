package com.falconx.trading.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingLedgerEntry;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.repository.TradingAccountRepository;
import com.falconx.trading.repository.TradingLedgerRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Sprint 3 C1 Task 6：验证 {@link DefaultTradingAccountService#applyOrderPlacementAccountChange}
 * 与原顺序调用 reserveMargin/chargeFee/confirmMarginUsed 行为等价。
 */
class ApplyOrderPlacementAccountChangeTests {

    private static final Long ACCOUNT_ID = 100L;
    private static final Long USER_ID = 7L;
    private static final String CURRENCY = "USDT";
    private static final String CLIENT_ORDER_ID = "cli-123";
    private static final String REFERENCE_NO = "ref-001";

    @Test
    void happyPath_writesThreeLedgersWithExpectedBizTypesAndKeys() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepo.batchInsert(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        DefaultTradingAccountService service = new DefaultTradingAccountService(accountRepo, ledgerRepo);
        TradingAccount before = newAccount(new BigDecimal("500.00000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal margin = new BigDecimal("100.00000000");
        BigDecimal fee = new BigDecimal("1.00000000");
        OffsetDateTime now = OffsetDateTime.now();

        TradingAccount result = service.applyOrderPlacementAccountChange(
                before, margin, fee, CURRENCY, margin, fee, BigDecimal.ONE, BigDecimal.ONE, CLIENT_ORDER_ID, REFERENCE_NO, now);

        // 最终账户状态：reserveMargin (frozen+=100) → chargeFee (balance-=1) → confirmMarginUsed (frozen-=100, marginUsed+=100)
        assertEquals(new BigDecimal("499.00000000"), result.balance());
        assertEquals(BigDecimal.ZERO.setScale(8), result.frozen());
        assertEquals(new BigDecimal("100.00000000"), result.marginUsed());

        // 单 UPDATE
        verify(accountRepo, times(1)).save(any(TradingAccount.class));

        // 单 batchInsert 写 3 条 ledger，biz_type / idempotency 顺序对齐
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TradingLedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepo, times(1)).batchInsert(captor.capture());
        verify(ledgerRepo, never()).save(any(TradingLedgerEntry.class));

        List<TradingLedgerEntry> ledgers = captor.getValue();
        assertEquals(3, ledgers.size());
        assertEquals(TradingLedgerBizType.ORDER_MARGIN_RESERVED, ledgers.get(0).bizType());
        assertEquals("order-margin-reserve:" + CLIENT_ORDER_ID, ledgers.get(0).idempotencyKey());
        assertEquals(margin, ledgers.get(0).amount());
        assertEquals(REFERENCE_NO, ledgers.get(0).referenceNo());

        assertEquals(TradingLedgerBizType.ORDER_FEE_CHARGED, ledgers.get(1).bizType());
        assertEquals("order-fee-charge:" + CLIENT_ORDER_ID, ledgers.get(1).idempotencyKey());
        assertEquals(fee, ledgers.get(1).amount());

        assertEquals(TradingLedgerBizType.ORDER_MARGIN_CONFIRMED, ledgers.get(2).bizType());
        assertEquals("order-margin-confirm:" + CLIENT_ORDER_ID, ledgers.get(2).idempotencyKey());
        assertEquals(margin, ledgers.get(2).amount());
    }

    @Test
    void ledgerBalanceSnapshots_matchSequentialOriginalBehavior() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepo.batchInsert(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        DefaultTradingAccountService service = new DefaultTradingAccountService(accountRepo, ledgerRepo);
        TradingAccount before = newAccount(new BigDecimal("500.00000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal margin = new BigDecimal("100.00000000");
        BigDecimal fee = new BigDecimal("1.00000000");

        service.applyOrderPlacementAccountChange(before, margin, fee, CURRENCY, margin, fee, BigDecimal.ONE, BigDecimal.ONE, CLIENT_ORDER_ID, REFERENCE_NO, OffsetDateTime.now());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TradingLedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepo).batchInsert(captor.capture());
        List<TradingLedgerEntry> ledgers = captor.getValue();

        // ledger 0 = reserveMargin step: balance 不变 500, frozen 0 → 100
        assertEquals(new BigDecimal("500.00000000"), ledgers.get(0).balanceBefore());
        assertEquals(new BigDecimal("500.00000000"), ledgers.get(0).balanceAfter());
        assertEquals(BigDecimal.ZERO.setScale(8), ledgers.get(0).frozenBefore());
        assertEquals(new BigDecimal("100.00000000"), ledgers.get(0).frozenAfter());

        // ledger 1 = chargeFee step: balance 500 → 499, frozen 不变 100
        assertEquals(new BigDecimal("500.00000000"), ledgers.get(1).balanceBefore());
        assertEquals(new BigDecimal("499.00000000"), ledgers.get(1).balanceAfter());
        assertEquals(new BigDecimal("100.00000000"), ledgers.get(1).frozenBefore());
        assertEquals(new BigDecimal("100.00000000"), ledgers.get(1).frozenAfter());

        // ledger 2 = confirmMarginUsed step: balance 不变 499, frozen 100 → 0, marginUsed 0 → 100
        assertEquals(new BigDecimal("499.00000000"), ledgers.get(2).balanceBefore());
        assertEquals(new BigDecimal("499.00000000"), ledgers.get(2).balanceAfter());
        assertEquals(new BigDecimal("100.00000000"), ledgers.get(2).frozenBefore());
        assertEquals(BigDecimal.ZERO.setScale(8), ledgers.get(2).frozenAfter());
        assertEquals(BigDecimal.ZERO.setScale(8), ledgers.get(2).marginUsedBefore());
        assertEquals(new BigDecimal("100.00000000"), ledgers.get(2).marginUsedAfter());
    }

    @Test
    void noBalanceCheckInternally_consistentWithOriginalChargeFeeBehavior() {
        // 关键不变量：复合方法与原顺序调用 reserveMargin/chargeFee/confirmMarginUsed
        // 行为完全一致 — 包括"都不做余额校验，依赖外层风控"。
        // TradingAccount.chargeFee 内部纯内存 subtract，不抛异常；
        // 因此 applyOrderPlacementAccountChange 余额不足时也不抛，而是产生负 balance。
        // 这是 by design：外层 TradingRiskService.evaluateMarketOrder 在调本方法前已校验。
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepo.batchInsert(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        DefaultTradingAccountService service = new DefaultTradingAccountService(accountRepo, ledgerRepo);
        TradingAccount before = newAccount(BigDecimal.ZERO.setScale(8), BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal margin = BigDecimal.ZERO.setScale(8);
        BigDecimal fee = new BigDecimal("1.00000000");

        // 应该正常执行不抛异常（与现状一致）
        TradingAccount result = service.applyOrderPlacementAccountChange(
                before, margin, fee, CURRENCY, margin, fee, BigDecimal.ONE, BigDecimal.ONE, CLIENT_ORDER_ID, REFERENCE_NO, OffsetDateTime.now());

        // 结果：balance = 0 - 1 = -1（与原 chargeFee 行为一致）
        assertEquals(new BigDecimal("-1.00000000"), result.balance());
        verify(accountRepo, times(1)).save(any(TradingAccount.class));
        verify(ledgerRepo, times(1)).batchInsert(anyList());
    }

    /**
     * STAGE-14B Task 5 Part B：批量 INSERT 的 3 条 ledger 都带占位货币三列。
     * 占位语义（master 设计：老数据 = 账户币 + fx=1）：
     *   originalAmount = 该笔写账金额（amount）
     *   originalCurrency = 账户币（CURRENCY）
     *   fxRateAtSettlement = 1
     */
    @Test
    void batchInsertLedgers_carryPlaceholderCurrencyColumns() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepo.batchInsert(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        DefaultTradingAccountService service = new DefaultTradingAccountService(accountRepo, ledgerRepo);
        TradingAccount before = newAccount(new BigDecimal("500.00000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal margin = new BigDecimal("100.00000000");
        BigDecimal fee = new BigDecimal("1.00000000");

        service.applyOrderPlacementAccountChange(before, margin, fee, CURRENCY, margin, fee, BigDecimal.ONE, BigDecimal.ONE, CLIENT_ORDER_ID, REFERENCE_NO, OffsetDateTime.now());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TradingLedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepo).batchInsert(captor.capture());
        List<TradingLedgerEntry> ledgers = captor.getValue();

        for (TradingLedgerEntry ledger : ledgers) {
            assertEquals(ledger.amount(), ledger.originalAmount());
            assertEquals(CURRENCY, ledger.originalCurrency());
            assertEquals(BigDecimal.ONE, ledger.fxRateAtSettlement());
        }
    }

    /**
     * STAGE-14B Task 5 Part B：单条 writeLedger 路径（creditDeposit）同样写占位货币三列。
     */
    @Test
    void writeLedgerSinglePath_carriesPlaceholderCurrencyColumns() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        TradingAccount account = newAccount(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(accountRepo.findByUserIdAndCurrencyForUpdate(USER_ID, CURRENCY))
                .thenReturn(java.util.Optional.of(account));
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepo.save(any(TradingLedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DefaultTradingAccountService service = new DefaultTradingAccountService(accountRepo, ledgerRepo);
        BigDecimal amount = new BigDecimal("250.00000000");

        service.creditDeposit(USER_ID, CURRENCY, amount, "dep:1", "ref-dep", OffsetDateTime.now());

        ArgumentCaptor<TradingLedgerEntry> captor = ArgumentCaptor.forClass(TradingLedgerEntry.class);
        verify(ledgerRepo).save(captor.capture());
        TradingLedgerEntry ledger = captor.getValue();
        assertEquals(amount, ledger.originalAmount());
        assertEquals(CURRENCY, ledger.originalCurrency());
        assertEquals(BigDecimal.ONE, ledger.fxRateAtSettlement());
    }

    /**
     * STAGE-14B Task 9a：异币种（fx≠1）开仓 margin/fee 落账三列真值。
     * 口径（master §3.2）：
     *   amount = 账户币 inAccount（驱动 balance/frozen 变更）
     *   original_amount = 原币 inQuote
     *   original_currency = quoteCurrency
     *   fx_rate_at_settlement = fxRate
     * 构造 AUD-quote、fx=0.63：margin inAccount=63 / inQuote=100；fee inAccount=6.3 / inQuote=10。
     */
    @Test
    void openLedgers_carryRealCurrencyColumnsForNonUsdQuote() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepo.batchInsert(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        DefaultTradingAccountService service = new DefaultTradingAccountService(accountRepo, ledgerRepo);
        TradingAccount before = newAccount(new BigDecimal("500.00000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal margin = new BigDecimal("63.00000000");
        BigDecimal fee = new BigDecimal("6.30000000");
        BigDecimal marginInQuote = new BigDecimal("100.00000000");
        BigDecimal feeInQuote = new BigDecimal("10.00000000");
        BigDecimal fxRate = new BigDecimal("0.63000000");
        String quoteCurrency = "AUD";

        service.applyOrderPlacementAccountChange(
                before, margin, fee, quoteCurrency, marginInQuote, feeInQuote, fxRate, fxRate,
                CLIENT_ORDER_ID, REFERENCE_NO, OffsetDateTime.now());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TradingLedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepo).batchInsert(captor.capture());
        List<TradingLedgerEntry> ledgers = captor.getValue();
        assertEquals(3, ledgers.size());

        // ledger 0 = ORDER_MARGIN_RESERVED：amount=inAccount、original=inQuote、currency=AUD、fx=0.63
        TradingLedgerEntry reserve = ledgers.get(0);
        assertEquals(TradingLedgerBizType.ORDER_MARGIN_RESERVED, reserve.bizType());
        assertEquals(margin, reserve.amount());
        assertEquals(marginInQuote, reserve.originalAmount());
        assertEquals(quoteCurrency, reserve.originalCurrency());
        assertEquals(fxRate, reserve.fxRateAtSettlement());

        // ledger 1 = ORDER_FEE_CHARGED：amount=fee.inAccount、original=fee.inQuote、currency=AUD、fx=0.63
        TradingLedgerEntry feeLedger = ledgers.get(1);
        assertEquals(TradingLedgerBizType.ORDER_FEE_CHARGED, feeLedger.bizType());
        assertEquals(fee, feeLedger.amount());
        assertEquals(feeInQuote, feeLedger.originalAmount());
        assertEquals(quoteCurrency, feeLedger.originalCurrency());
        assertEquals(fxRate, feeLedger.fxRateAtSettlement());

        // ledger 2 = ORDER_MARGIN_CONFIRMED：同 margin 真值留痕
        TradingLedgerEntry confirm = ledgers.get(2);
        assertEquals(TradingLedgerBizType.ORDER_MARGIN_CONFIRMED, confirm.bizType());
        assertEquals(margin, confirm.amount());
        assertEquals(marginInQuote, confirm.originalAmount());
        assertEquals(quoteCurrency, confirm.originalCurrency());
        assertEquals(fxRate, confirm.fxRateAtSettlement());
    }

    /**
     * STAGE-14B Task 9a 收口：margin 与 fee 在风控阶段各查一次 FX 快照，两次之间快照被刷新取到不同值。
     * 验证 margin 两笔（RESERVED/CONFIRMED）落 fxRate，fee 笔（FEE_CHARGED）落 feeFxRate，
     * 且各自账本三列 original_amount × fx_rate_at_settlement == amount 数学自洽。
     * 构造 AUD-quote：margin fx=0.63（inQuote=100 → inAccount=63）；fee fx=0.65（inQuote=10 → inAccount=6.5）。
     */
    @Test
    void openLedgers_marginAndFeeCarryDistinctFxRatesEachSelfConsistent() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepo.batchInsert(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        DefaultTradingAccountService service = new DefaultTradingAccountService(accountRepo, ledgerRepo);
        TradingAccount before = newAccount(new BigDecimal("500.00000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        // margin：inQuote=100、fx=0.63 → inAccount=63
        BigDecimal margin = new BigDecimal("63.00000000");
        BigDecimal marginInQuote = new BigDecimal("100.00000000");
        BigDecimal fxRate = new BigDecimal("0.63000000");
        // fee：inQuote=10、fx=0.65 → inAccount=6.5（fee 查询时刻 FX 快照已被刷新，与 margin 不同）
        BigDecimal fee = new BigDecimal("6.50000000");
        BigDecimal feeInQuote = new BigDecimal("10.00000000");
        BigDecimal feeFxRate = new BigDecimal("0.65000000");
        String quoteCurrency = "AUD";

        service.applyOrderPlacementAccountChange(
                before, margin, fee, quoteCurrency, marginInQuote, feeInQuote, fxRate, feeFxRate,
                CLIENT_ORDER_ID, REFERENCE_NO, OffsetDateTime.now());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TradingLedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepo).batchInsert(captor.capture());
        List<TradingLedgerEntry> ledgers = captor.getValue();
        assertEquals(3, ledgers.size());

        // margin RESERVED：落 fxRate（=0.63），original×fx==amount → 100×0.63==63
        TradingLedgerEntry reserve = ledgers.get(0);
        assertEquals(TradingLedgerBizType.ORDER_MARGIN_RESERVED, reserve.bizType());
        assertEquals(fxRate, reserve.fxRateAtSettlement());
        assertEquals(0, reserve.originalAmount().multiply(reserve.fxRateAtSettlement())
                .compareTo(reserve.amount()), "margin RESERVED original×fx 应等于 amount");

        // fee FEE_CHARGED：落 feeFxRate（=0.65，与 margin 不同），original×fx==amount → 10×0.65==6.5
        TradingLedgerEntry feeLedger = ledgers.get(1);
        assertEquals(TradingLedgerBizType.ORDER_FEE_CHARGED, feeLedger.bizType());
        assertEquals(feeFxRate, feeLedger.fxRateAtSettlement());
        assertEquals(0, feeLedger.originalAmount().multiply(feeLedger.fxRateAtSettlement())
                .compareTo(feeLedger.amount()), "fee FEE_CHARGED original×fx 应等于 amount");

        // margin CONFIRMED：仍落 fxRate（=0.63），与 RESERVED 同笔保证金，original×fx==amount
        TradingLedgerEntry confirm = ledgers.get(2);
        assertEquals(TradingLedgerBizType.ORDER_MARGIN_CONFIRMED, confirm.bizType());
        assertEquals(fxRate, confirm.fxRateAtSettlement());
        assertEquals(0, confirm.originalAmount().multiply(confirm.fxRateAtSettlement())
                .compareTo(confirm.amount()), "margin CONFIRMED original×fx 应等于 amount");

        // margin 与 fee 落的 fx 确实不同
        org.junit.jupiter.api.Assertions.assertNotEquals(
                reserve.fxRateAtSettlement(), feeLedger.fxRateAtSettlement(),
                "margin 与 fee 应各携带自身查询时刻的 fxRate");
    }

    /**
     * STAGE-14B Task 9a：USDT-quote（fx=1）开仓三列退化 —— original==amount、currency=USDT、fx=1。
     */
    @Test
    void openLedgers_degradeToFxOneForUsdtQuote() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepo.batchInsert(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        DefaultTradingAccountService service = new DefaultTradingAccountService(accountRepo, ledgerRepo);
        TradingAccount before = newAccount(new BigDecimal("500.00000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal margin = new BigDecimal("100.00000000");
        BigDecimal fee = new BigDecimal("1.00000000");

        service.applyOrderPlacementAccountChange(
                before, margin, fee, CURRENCY, margin, fee, BigDecimal.ONE, BigDecimal.ONE,
                CLIENT_ORDER_ID, REFERENCE_NO, OffsetDateTime.now());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TradingLedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepo).batchInsert(captor.capture());
        for (TradingLedgerEntry ledger : captor.getValue()) {
            assertEquals(ledger.amount(), ledger.originalAmount());
            assertEquals(CURRENCY, ledger.originalCurrency());
            assertEquals(BigDecimal.ONE, ledger.fxRateAtSettlement());
        }
    }

    // ---- helpers ----

    private TradingAccount newAccount(BigDecimal balance, BigDecimal frozen, BigDecimal marginUsed) {
        OffsetDateTime now = OffsetDateTime.now();
        return new TradingAccount(
                ACCOUNT_ID,
                USER_ID,
                CURRENCY,
                balance.setScale(8),
                frozen.setScale(8),
                marginUsed.setScale(8),
                TradingMarginMode.ISOLATED,
                null,
                null,
                now,
                now
        );
    }
}
