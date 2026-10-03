package com.falconx.trading.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.engine.SymbolTriggerActivityRegistry;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPendingOrderTrigger;
import com.falconx.trading.entity.TradingPendingOrderType;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingAccountRepository;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.service.TradingGroupMarkupService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * PROD 精度统一 切片 3：挂单创建输入精度校验单元测试。
 *
 * <p>{@link TradingPendingOrderApplicationService#createOpening} 校验 quantity 小数位 ≤
 * {@code SymbolSpec.qtyPrecision}、用户给的价格字段（triggerPrice / limitPrice）小数位 ≤
 * {@code SymbolSpec.pricePrecision}，超出抛 {@link TradingBusinessException}
 * （{@code QTY_PRECISION_EXCEEDED} / {@code PRICE_PRECISION_EXCEEDED}）。
 */
class TradingPendingOrderInputPrecisionTests {

    private static final String SETTLEMENT_TOKEN = "USDT";

    @Mock private TradingPendingOrderTriggerRepository pendingOrderRepository;
    @Mock private TradingAccountService tradingAccountService;
    @Mock private TradingAccountRepository tradingAccountRepository;
    @Mock private TradingAccountSnapshotApplicationService accountSnapshotService;
    @Mock private TradingPositionRepository tradingPositionRepository;
    @Mock private TradingRiskConfigRepository tradingRiskConfigRepository;
    @Mock private TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;
    @Mock private IdGenerator idGenerator;
    @Mock private TradingGroupMarkupService tradingGroupMarkupService;
    @Mock private SymbolTriggerActivityRegistry triggerActivityRegistry;
    @Mock private MarketSymbolSpecRepository marketSymbolSpecRepository;

    private TradingCoreServiceProperties properties;
    private TradingPendingOrderApplicationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new TradingCoreServiceProperties();
        properties.setSettlementToken(SETTLEMENT_TOKEN);
        properties.setDefaultFeeRate(new BigDecimal("0.0005"));
        service = new TradingPendingOrderApplicationService(
                pendingOrderRepository,
                tradingAccountService,
                tradingAccountRepository,
                accountSnapshotService,
                tradingPositionRepository,
                tradingRiskConfigRepository,
                tradingQuoteSnapshotRepository,
                properties,
                idGenerator,
                tradingGroupMarkupService,
                triggerActivityRegistry,
                marketSymbolSpecRepository);
    }

    /**
     * limitPrice=40000.123（3 位小数）超过 pricePrecision=2 → 抛 PRICE_PRECISION_EXCEEDED，
     * 且不进入资金冻结 / 写挂单表。
     */
    @Test
    void shouldRejectWhenLimitPriceScaleExceedsPricePrecision() {
        stubSpec(2, 4);

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class, () ->
                service.createOpening(700L, "BTCUSDT", TradingOrderSide.BUY,
                        TradingPendingOrderType.LIMIT,
                        new BigDecimal("1.0"), new BigDecimal("40000.10"),
                        new BigDecimal("40000.123"), new BigDecimal("10"),
                        TradingMarginMode.ISOLATED, "client-1"));

        Assertions.assertEquals(TradingErrorCode.PRICE_PRECISION_EXCEEDED, ex.getErrorCode());
        verify(tradingAccountService, never()).reserveMargin(
                anyLong(), anyString(), any(), anyString(), anyString(), any());
        verify(pendingOrderRepository, never()).insert(any());
    }

    /**
     * quantity=1.12345（5 位小数）超过 qtyPrecision=4 → 抛 QTY_PRECISION_EXCEEDED，不写挂单表。
     */
    @Test
    void shouldRejectWhenQuantityScaleExceedsQtyPrecision() {
        stubSpec(2, 4);

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class, () ->
                service.createOpening(700L, "BTCUSDT", TradingOrderSide.BUY,
                        TradingPendingOrderType.LIMIT,
                        new BigDecimal("1.12345"), new BigDecimal("40000.10"),
                        new BigDecimal("40000.10"), new BigDecimal("10"),
                        TradingMarginMode.ISOLATED, "client-2"));

        Assertions.assertEquals(TradingErrorCode.QTY_PRECISION_EXCEEDED, ex.getErrorCode());
        verify(pendingOrderRepository, never()).insert(any());
    }

    /**
     * 精度合规（qty 2 位 ≤ 4、price 2 位 ≤ 2）→ 不被精度拦截，正常走到资金冻结 + 写挂单表。
     */
    @Test
    void shouldAcceptWhenQuantityAndPricesWithinPrecision() {
        stubSpec(2, 4);
        long pendingId = 9001L;
        when(idGenerator.nextId()).thenReturn(pendingId);
        TradingAccount account = account();
        when(tradingAccountService.getOrCreateAccountForUpdate(700L, SETTLEMENT_TOKEN)).thenReturn(account);
        when(accountSnapshotService.calculateAvailableForOrder(any(), any(), any(), any()))
                .thenReturn(null);
        when(tradingAccountService.reserveMargin(anyLong(), anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(account);
        when(tradingGroupMarkupService.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());

        TradingPendingOrderTrigger created = service.createOpening(
                700L, "BTCUSDT", TradingOrderSide.BUY,
                TradingPendingOrderType.LIMIT,
                new BigDecimal("1.25"), new BigDecimal("40000.10"),
                new BigDecimal("40000.05"), new BigDecimal("10"),
                TradingMarginMode.ISOLATED, "client-3");

        Assertions.assertNotNull(created);
        verify(pendingOrderRepository).insert(any());
        verify(triggerActivityRegistry).markPendingOrderActive("BTCUSDT");
    }

    // ---- helpers ----

    private void stubSpec(int pricePrecision, int qtyPrecision) {
        when(marketSymbolSpecRepository.findByPlatformSymbol("BTCUSDT"))
                .thenReturn(Optional.of(new SymbolSpec(
                        "BTCUSDT", 100, new BigDecimal("0.0005"), BigDecimal.ZERO,
                        new BigDecimal("0.00000001"), new BigDecimal("1000000"), BigDecimal.ZERO,
                        pricePrecision, qtyPrecision, "BTC", "USDT", 1)));
    }

    private TradingAccount account() {
        return new TradingAccount(1L, 700L, "USDT",
                new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO,
                TradingMarginMode.ISOLATED, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }
}
