package com.falconx.trading.application;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingLedgerEntry;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingLedgerRepository;
import com.falconx.trading.repository.TradingOutboxRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingSwapRateSnapshotRepository;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.service.model.TradingSwapRateRule;
import com.falconx.trading.service.model.TradingSwapRateSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * STAGE-14B Task 8 Part B：隔夜利息结算货币换算 + 落账三列真值单元测试。
 *
 * <p>验证 master §3.2「Swap(AC)=Swap(QC)×fx 快照」与「落账三列留痕
 * （original_amount/original_currency/fx_rate_at_settlement）」：
 * <ul>
 *   <li>同币种 fx=1 落账三列；</li>
 *   <li>异币种换算落账三列（amount=账户币、original=原币、fx≠1）；</li>
 *   <li>SymbolSpec 缺失 / quoteCurrency null 降级（fx=1、不抛、不阻断）；</li>
 *   <li>FX 不可用降级（fx=1、不阻断）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingSwapSettlementCurrencyConversionTests {

    @Mock private TradingPositionRepository tradingPositionRepository;
    @Mock private TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;
    @Mock private TradingSwapRateSnapshotRepository tradingSwapRateSnapshotRepository;
    @Mock private TradingLedgerRepository tradingLedgerRepository;
    @Mock private TradingOutboxRepository tradingOutboxRepository;
    @Mock private TradingAccountService tradingAccountService;
    @Mock private TradingCoreServiceProperties properties;
    @Mock private MarketSymbolSpecRepository marketSymbolSpecRepository;
    @Mock private FxRateService fxRateService;

    private TradingSwapSettlementApplicationService service;

    private static final long POSITION_ID = 9001L;
    private static final long USER_ID = 1001L;
    private static final String SYMBOL = "EURAUD";
    private static final OffsetDateTime ROLLOVER_AT = OffsetDateTime.parse("2026-05-29T00:00:00Z");
    private static final OffsetDateTime OPENED_AT = ROLLOVER_AT.minusDays(1);

    @BeforeEach
    void setUp() {
        service = new TradingSwapSettlementApplicationService(
                tradingPositionRepository,
                tradingQuoteSnapshotRepository,
                tradingSwapRateSnapshotRepository,
                tradingLedgerRepository,
                tradingOutboxRepository,
                tradingAccountService,
                properties,
                marketSymbolSpecRepository,
                fxRateService
        );
        properties.getStale(); // mock; getMaxAge stubbed below via stale()
    }

    /** 同币种（quoteCurrency==账户币）：fx=1，amount==original，落账三列 fx=ONE。 */
    @Test
    void shouldSettleIdentityWhenQuoteEqualsAccountCurrency() {
        stubCommonHappyPath("USDT"); // quoteCurrency = 账户币 USDT
        // qty=1 × price=1.6 × rate=0.01 = 0.016（SWAP_CHARGE，longRate 取负）
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> originalAmount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> originalCurrency = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> fxRate = ArgumentCaptor.forClass(BigDecimal.class);
        captureSettleSwap(amount, originalAmount, originalCurrency, fxRate);

        boolean settled = service.settlePositionAtRollover(POSITION_ID, ROLLOVER_AT);

        Assertions.assertTrue(settled);
        Assertions.assertEquals(0, amount.getValue().compareTo(new BigDecimal("0.016")), "账户币 amount=0.016");
        Assertions.assertEquals(0, originalAmount.getValue().compareTo(new BigDecimal("0.016")), "同币种 original==amount");
        Assertions.assertEquals("USDT", originalCurrency.getValue(), "original_currency=账户币");
        Assertions.assertEquals(0, fxRate.getValue().compareTo(BigDecimal.ONE), "同币种 fx=1");
        Mockito.verifyNoInteractions(fxRateService);
    }

    /** 异币种：Swap(QC) 换算成账户币，落账 amount=账户币、original=原币、fx≠1。 */
    @Test
    void shouldConvertSwapToAccountCurrencyForeignQuote() {
        stubCommonHappyPath("AUD"); // quoteCurrency=AUD、账户币 USDT
        Mockito.when(fxRateService.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.6500")));
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> originalAmount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> originalCurrency = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> fxRate = ArgumentCaptor.forClass(BigDecimal.class);
        captureSettleSwap(amount, originalAmount, originalCurrency, fxRate);

        boolean settled = service.settlePositionAtRollover(POSITION_ID, ROLLOVER_AT);

        Assertions.assertTrue(settled);
        // Swap(AUD)=0.016；Swap(USDT)=0.016×0.65=0.0104
        Assertions.assertEquals(0, originalAmount.getValue().compareTo(new BigDecimal("0.016")), "original=原币 AUD 0.016");
        Assertions.assertEquals("AUD", originalCurrency.getValue(), "original_currency=AUD");
        Assertions.assertEquals(0, fxRate.getValue().compareTo(new BigDecimal("0.65")), "fx=0.65");
        Assertions.assertEquals(0, amount.getValue().compareTo(new BigDecimal("0.0104")), "账户币 amount=0.0104");
    }

    /**
     * SymbolSpec 缺失 → 降级：fx=1、amount==original、original_currency=账户币、不抛、不阻断。
     * STAGE-14B Task 9b 收口 Issue 2：SymbolSpec 缺失时确实不知道 QC，original_currency 保持账户币
     * （与 FX 不可用分支改记 quoteCurrency 区分）。
     */
    @Test
    void shouldDegradeWhenSymbolSpecMissing() {
        stubCommonHappyPath("AUD");
        Mockito.when(marketSymbolSpecRepository.findByPlatformSymbol(SYMBOL)).thenReturn(Optional.empty());
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> originalAmount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> originalCurrency = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> fxRate = ArgumentCaptor.forClass(BigDecimal.class);
        captureSettleSwap(amount, originalAmount, originalCurrency, fxRate);

        boolean settled = service.settlePositionAtRollover(POSITION_ID, ROLLOVER_AT);

        Assertions.assertTrue(settled, "SymbolSpec 缺失不应阻断结算");
        Assertions.assertEquals(0, amount.getValue().compareTo(new BigDecimal("0.016")), "降级 amount=原值");
        Assertions.assertEquals(0, originalAmount.getValue().compareTo(new BigDecimal("0.016")), "降级 original=原值");
        Assertions.assertEquals("USDT", originalCurrency.getValue(), "降级 original_currency=账户币");
        Assertions.assertEquals(0, fxRate.getValue().compareTo(BigDecimal.ONE), "降级 fx=1");
        Mockito.verifyNoInteractions(fxRateService);
    }

    /**
     * quoteCurrency 为 null（过渡期旧快照）→ 降级，与 SymbolSpec 缺失一致。
     * STAGE-14B Task 9b 收口 Issue 2：QC 未回填同样不知道真实 QC，original_currency 保持账户币。
     */
    @Test
    void shouldDegradeWhenQuoteCurrencyNull() {
        stubCommonHappyPath(null);
        ArgumentCaptor<BigDecimal> fxRate = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> originalCurrency = ArgumentCaptor.forClass(String.class);
        captureSettleSwap(ArgumentCaptor.forClass(BigDecimal.class),
                ArgumentCaptor.forClass(BigDecimal.class), originalCurrency, fxRate);

        boolean settled = service.settlePositionAtRollover(POSITION_ID, ROLLOVER_AT);

        Assertions.assertTrue(settled, "quoteCurrency=null 不应阻断结算");
        Assertions.assertEquals(0, fxRate.getValue().compareTo(BigDecimal.ONE), "降级 fx=1");
        Assertions.assertEquals("USDT", originalCurrency.getValue(), "降级 original_currency=账户币");
        Mockito.verifyNoInteractions(fxRateService);
    }

    /**
     * FX 不可用（queryRate empty）→ 降级 fx=1，不阻断结算。
     * STAGE-14B Task 9b 收口 Issue 2：QC=AUD 已知仅 rate 查不到 → original_currency 如实记真实 quoteCurrency=AUD
     * （不再瞎写账户币 USDT），与 SymbolSpec 缺失 / QC null 分支（记账户币）区分。
     */
    @Test
    void shouldDegradeWhenFxUnavailable() {
        stubCommonHappyPath("AUD");
        Mockito.when(fxRateService.queryRate("AUD", "USDT")).thenReturn(Optional.empty());
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> originalAmount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> originalCurrency = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> fxRate = ArgumentCaptor.forClass(BigDecimal.class);
        captureSettleSwap(amount, originalAmount, originalCurrency, fxRate);

        boolean settled = service.settlePositionAtRollover(POSITION_ID, ROLLOVER_AT);

        Assertions.assertTrue(settled, "FX 不可用不应阻断 swap 被动结算");
        Assertions.assertEquals(0, fxRate.getValue().compareTo(BigDecimal.ONE), "FX 不可用降级 fx=1");
        Assertions.assertEquals(0, amount.getValue().compareTo(new BigDecimal("0.016")), "降级 amount=原币原值");
        Assertions.assertEquals(0, originalAmount.getValue().compareTo(new BigDecimal("0.016")), "降级 original=原币 settlementAmount 原值");
        Assertions.assertEquals("AUD", originalCurrency.getValue(), "FX 不可用降级 original_currency=真实 quoteCurrency=AUD");
    }

    /**
     * FX 换算后账户币舍入到 0（极端小 fxRate）→ skip 守卫：settled==false、settleSwap 从未被调用、不抛。
     */
    @Test
    void shouldSkipWhenSettlementRoundsToZeroAfterFx() {
        stubCommonHappyPath("AUD");
        // 原币 settlementAmount=0.016；fxRate 极小 → 0.016×1E-10=1.6E-12，8 位 HALF_UP 舍入到 0。
        Mockito.when(fxRateService.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("1E-10")));

        boolean settled = Assertions.assertDoesNotThrow(
                () -> service.settlePositionAtRollover(POSITION_ID, ROLLOVER_AT));

        Assertions.assertFalse(settled, "换算后账户币为 0 应跳过结算");
        Mockito.verify(tradingAccountService, Mockito.never()).settleSwap(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.any(),
                Mockito.any(TradingLedgerBizType.class), Mockito.anyString(), Mockito.anyString(),
                Mockito.any(OffsetDateTime.class));
    }

    // ─── 工具方法 ────────────────────────────────────────────────────────────

    /**
     * 装配走到 settleSwap 的 happy path：position OPEN、swap 规则存在、报价 fresh、有效价格、非零费率。
     *
     * @param quoteCurrency SymbolSpec 的 quoteCurrency；传 null 表示过渡期旧快照 quoteCurrency 为 null
     */
    private void stubCommonHappyPath(String quoteCurrency) {
        TradingPosition position = Mockito.mock(TradingPosition.class);
        Mockito.when(position.isTerminal()).thenReturn(false);
        Mockito.when(position.openedAt()).thenReturn(OPENED_AT);
        Mockito.when(position.symbol()).thenReturn(SYMBOL);
        Mockito.when(position.side()).thenReturn(TradingOrderSide.BUY);
        Mockito.when(position.quantity()).thenReturn(BigDecimal.ONE);
        Mockito.when(position.positionId()).thenReturn(POSITION_ID);
        Mockito.when(position.userId()).thenReturn(USER_ID);
        Mockito.when(tradingPositionRepository.findByIdForUpdate(POSITION_ID)).thenReturn(Optional.of(position));

        // longRate=-0.01（负 → SWAP_CHARGE）
        TradingSwapRateRule rule = new TradingSwapRateRule(
                LocalDate.parse("2026-01-01"), LocalTime.MIDNIGHT,
                new BigDecimal("-0.01"), new BigDecimal("0.01"));
        TradingSwapRateSnapshot snapshot = new TradingSwapRateSnapshot(SYMBOL, List.of(rule), ROLLOVER_AT);
        Mockito.when(tradingSwapRateSnapshotRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(snapshot));

        Mockito.when(tradingLedgerRepository.existsByUserIdAndIdempotencyKey(Mockito.eq(USER_ID), Mockito.anyString()))
                .thenReturn(false);

        // fresh 报价：bid/ask 给 1.6，ts 与 rolloverAt 同刻
        TradingQuoteSnapshot quote = Mockito.mock(TradingQuoteSnapshot.class);
        Mockito.when(quote.stale()).thenReturn(false);
        Mockito.when(quote.ts()).thenReturn(ROLLOVER_AT);
        Mockito.when(quote.bid()).thenReturn(new BigDecimal("1.6"));
        Mockito.when(quote.ask()).thenReturn(new BigDecimal("1.6"));
        Mockito.when(quote.source()).thenReturn("test");
        Mockito.when(tradingQuoteSnapshotRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(quote));

        // stale.maxAge
        TradingCoreServiceProperties.Stale stale = Mockito.mock(TradingCoreServiceProperties.Stale.class);
        Mockito.when(stale.getMaxAge()).thenReturn(Duration.ofHours(1));
        Mockito.when(properties.getStale()).thenReturn(stale);
        Mockito.when(properties.getSettlementToken()).thenReturn("USDT");

        // 账户币 USDT
        TradingAccount account = Mockito.mock(TradingAccount.class);
        Mockito.when(account.currency()).thenReturn("USDT");
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, "USDT")).thenReturn(account);

        // SymbolSpec：quoteCurrency 由参数控制
        SymbolSpec spec = new SymbolSpec(SYMBOL, 100, new BigDecimal("0.0005"), BigDecimal.ZERO,
                new BigDecimal("0.00000001"), new BigDecimal("1000000"), BigDecimal.ZERO, 5, 2,
                "EUR", quoteCurrency, 2);
        Mockito.when(marketSymbolSpecRepository.findByPlatformSymbol(SYMBOL)).thenReturn(Optional.of(spec));
    }

    /** stub settleSwap 捕获四个换算/留痕参数并返回一条 ledger。 */
    private void captureSettleSwap(ArgumentCaptor<BigDecimal> amount,
                                   ArgumentCaptor<BigDecimal> originalAmount,
                                   ArgumentCaptor<String> originalCurrency,
                                   ArgumentCaptor<BigDecimal> fxRate) {
        TradingLedgerEntry ledgerEntry = Mockito.mock(TradingLedgerEntry.class);
        Mockito.when(ledgerEntry.ledgerId()).thenReturn(7001L);
        Mockito.when(ledgerEntry.createdAt()).thenReturn(ROLLOVER_AT);
        Mockito.when(tradingAccountService.settleSwap(
                        Mockito.any(),
                        amount.capture(),
                        originalAmount.capture(),
                        originalCurrency.capture(),
                        fxRate.capture(),
                        Mockito.any(TradingLedgerBizType.class),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(OffsetDateTime.class)))
                .thenReturn(ledgerEntry);
    }
}
