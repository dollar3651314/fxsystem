package com.falconx.trading.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingLedgerEntry;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.repository.TradingAccountRepository;
import com.falconx.trading.repository.TradingLedgerRepository;
import com.falconx.trading.service.TradingAccountService.PositionSettlementResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * STAGE-14B Task 9b：{@link DefaultTradingAccountService#settlePositionExit} 货币真值落账单测。
 *
 * <p>验证 balance 变更 / 负净值保护基于账户币 {@code realizedPnlInAccount}，
 * 而 t_ledger 三列留原币真值（{@code originalAmount} / {@code originalCurrency} / {@code fxRate}）。
 */
class SettlePositionExitCurrencyTests {

    private static final Long ACCOUNT_ID = 100L;
    private static final Long USER_ID = 7L;
    private static final String ACCOUNT_CURRENCY = "USDT";
    private static final String IDEMPOTENCY = "position-exit:200:MANUAL";
    private static final String REFERENCE_NO = "200";

    @Test
    void crossCurrencyProfit_balanceUsesInAccount_ledgerKeepsOriginalQuote() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerRepo.save(any(TradingLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        DefaultTradingAccountService service = new DefaultTradingAccountService(accountRepo, ledgerRepo);
        TradingAccount before = newAccount(new BigDecimal("500.00000000"), new BigDecimal("82.50000000"));
        OffsetDateTime now = OffsetDateTime.now();

        // EURAUD 盈利：realizedPnl(AUD) = 100，fxAtClose = 0.65 → inAccount = 65 USDT
        BigDecimal realizedPnlInQuote = new BigDecimal("100.00000000");
        BigDecimal realizedPnlInAccount = new BigDecimal("65.00000000");
        BigDecimal fxAtClose = new BigDecimal("0.65000000");

        PositionSettlementResult result = service.settlePositionExit(
                before,
                new BigDecimal("82.50000000"),
                realizedPnlInAccount,
                realizedPnlInQuote,
                "AUD",
                fxAtClose,
                TradingLedgerBizType.REALIZED_PNL,
                false,
                IDEMPOTENCY,
                REFERENCE_NO,
                now);

        // balance += inAccount（账户币）
        assertEquals(new BigDecimal("565.00000000"), result.account().balance());
        assertEquals(realizedPnlInAccount, result.appliedPnl());
        assertEquals(BigDecimal.ZERO.setScale(8), result.platformCoveredLoss());

        ArgumentCaptor<TradingLedgerEntry> captor = ArgumentCaptor.forClass(TradingLedgerEntry.class);
        verify(ledgerRepo, times(1)).save(captor.capture());
        TradingLedgerEntry ledger = captor.getValue();
        assertEquals(TradingLedgerBizType.REALIZED_PNL, ledger.bizType());
        // amount = 账户币 inAccount
        assertEquals(realizedPnlInAccount, ledger.amount());
        // 三列留原币真值
        assertEquals(realizedPnlInQuote, ledger.originalAmount());
        assertEquals("AUD", ledger.originalCurrency());
        assertEquals(fxAtClose, ledger.fxRateAtSettlement());
    }

    /**
     * STAGE-14B Task 9b 收口 Issue 1：负净值保护 + 异币种场景下，t_ledger 三列与 amount 不满足换算关系是【预期】。
     *
     * <p>realizedPnl(AUD)=-200、fxAtClose=0.65 → inAccount=-130 USDT，但账户 balance 仅 50 → 负净值保护封顶：
     * <ul>
     *   <li>amount = 封顶后账户币 appliedPnl = -50（仅扣账户实有 50 USDT）；</li>
     *   <li>original_amount = 完整原币 -200 AUD、fx = 0.65（留痕实际亏损全貌）；</li>
     *   <li>故 amount(-50) ≠ original_amount(-200) × fx(0.65)=-130 —— 这是审计预期，账本同时表达
     *       「实际亏 200 AUD、仅扣账户 50 USDT、平台补 80 USDT」三个事实，不应误判为 bug。</li>
     * </ul>
     */
    @Test
    void crossCurrencyLoss_negativeInAccount_protectionUsesAccountCurrency() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerRepo.save(any(TradingLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        DefaultTradingAccountService service = new DefaultTradingAccountService(accountRepo, ledgerRepo);
        TradingAccount before = newAccount(new BigDecimal("50.00000000"), new BigDecimal("82.50000000"));
        OffsetDateTime now = OffsetDateTime.now();

        // 亏损：realizedPnl(AUD) = -200，fxAtClose = 0.65 → inAccount = -130 USDT；balance 仅 50 → 负净值保护
        BigDecimal realizedPnlInQuote = new BigDecimal("-200.00000000");
        BigDecimal realizedPnlInAccount = new BigDecimal("-130.00000000");
        BigDecimal fxAtClose = new BigDecimal("0.65000000");

        PositionSettlementResult result = service.settlePositionExit(
                before,
                new BigDecimal("82.50000000"),
                realizedPnlInAccount,
                realizedPnlInQuote,
                "AUD",
                fxAtClose,
                TradingLedgerBizType.LIQUIDATION_PNL,
                true,
                IDEMPOTENCY,
                REFERENCE_NO,
                now);

        // 负净值保护基于账户币：balance 最多落到 0，appliedPnl = -50（账户币），平台兜底 = 130 - 50 = 80（账户币）
        assertEquals(BigDecimal.ZERO.setScale(8), result.account().balance());
        assertEquals(new BigDecimal("-50.00000000"), result.appliedPnl());
        assertEquals(new BigDecimal("80.00000000"), result.platformCoveredLoss());

        ArgumentCaptor<TradingLedgerEntry> captor = ArgumentCaptor.forClass(TradingLedgerEntry.class);
        verify(ledgerRepo, times(1)).save(captor.capture());
        TradingLedgerEntry ledger = captor.getValue();
        // amount = 实际记账的账户币 appliedPnl（负净值保护封顶）
        assertEquals(new BigDecimal("-50.00000000"), ledger.amount());
        // 三列留原币真值（完整 -200 AUD × 0.65），与封顶后 amount=-50 不满足换算关系 —— Issue 1 预期审计行为。
        assertEquals(realizedPnlInQuote, ledger.originalAmount());
        assertEquals("AUD", ledger.originalCurrency());
        assertEquals(fxAtClose, ledger.fxRateAtSettlement());
        // 显式断言三列非自洽为预期：amount(-50) ≠ original_amount(-200) × fx(0.65)=-130（用 compareTo 忽略 scale）。
        BigDecimal originalConverted = ledger.originalAmount().multiply(ledger.fxRateAtSettlement());
        assertEquals(0, originalConverted.compareTo(new BigDecimal("-130")),
                "original×fx 应为 -130");
        org.junit.jupiter.api.Assertions.assertNotEquals(0, ledger.amount().compareTo(originalConverted),
                "封顶后 amount(-50) 与 original×fx(-130) 不相等是负净值保护下的预期审计行为");
    }

    @Test
    void sameCurrencyDegrade_fxOne_inAccountEqualsInQuote() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerRepo.save(any(TradingLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        DefaultTradingAccountService service = new DefaultTradingAccountService(accountRepo, ledgerRepo);
        TradingAccount before = newAccount(new BigDecimal("500.00000000"), new BigDecimal("100.00000000"));
        OffsetDateTime now = OffsetDateTime.now();

        BigDecimal realizedPnl = new BigDecimal("42.00000000");

        PositionSettlementResult result = service.settlePositionExit(
                before,
                new BigDecimal("100.00000000"),
                realizedPnl,
                realizedPnl,
                ACCOUNT_CURRENCY,
                BigDecimal.ONE,
                TradingLedgerBizType.REALIZED_PNL,
                false,
                IDEMPOTENCY,
                REFERENCE_NO,
                now);

        assertEquals(new BigDecimal("542.00000000"), result.account().balance());

        ArgumentCaptor<TradingLedgerEntry> captor = ArgumentCaptor.forClass(TradingLedgerEntry.class);
        verify(ledgerRepo, times(1)).save(captor.capture());
        TradingLedgerEntry ledger = captor.getValue();
        assertEquals(realizedPnl, ledger.amount());
        assertEquals(realizedPnl, ledger.originalAmount());
        assertEquals(ACCOUNT_CURRENCY, ledger.originalCurrency());
        assertEquals(BigDecimal.ONE, ledger.fxRateAtSettlement());
    }

    private TradingAccount newAccount(BigDecimal balance, BigDecimal marginUsed) {
        OffsetDateTime now = OffsetDateTime.now();
        return new TradingAccount(
                ACCOUNT_ID, USER_ID, ACCOUNT_CURRENCY,
                balance.setScale(8),
                BigDecimal.ZERO.setScale(8),
                marginUsed.setScale(8),
                TradingMarginMode.ISOLATED,
                null, null,
                now, now);
    }
}
