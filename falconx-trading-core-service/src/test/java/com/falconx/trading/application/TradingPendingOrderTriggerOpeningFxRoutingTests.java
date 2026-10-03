package com.falconx.trading.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.engine.SymbolTriggerActivityRegistry;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrder;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingOrderStatus;
import com.falconx.trading.entity.TradingOrderType;
import com.falconx.trading.entity.TradingPendingOrderStatus;
import com.falconx.trading.entity.TradingPendingOrderTrigger;
import com.falconx.trading.entity.TradingPendingOrderType;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * STAGE-14B Task 9c：验证「PENDING 开仓挂单触发」复用 Task 9a 已接 FX 的开仓流程。
 *
 * <p>调查结论（情形 A）：{@link TradingPendingOrderApplicationService#triggerOpening} 命中后
 * 唯一执行开仓的方式是构造 {@link PlaceMarketOrderCommand} 并调用
 * {@link TradingOrderPlacementApplicationService#placeMarketOrder}。后者在 Task 9a 已经接通
 * FX 换算（{@code evaluateMarketOrder}）、开仓落账三列真值与 {@code entry_fx_rate} 写入
 * （{@code applyOrderPlacementAccountChange}），并在 FX 不可用 / 余额不足时返回 REJECTED 决策。
 *
 * <p>因此挂单触发开仓的 FX 真值接通完全由 9a 链路自动覆盖，本测试只验证「挂单触发确实把开仓
 * 委托给 9a 链路、且不绕过它自造开仓 / 落账 / 汇率」，不重复实现 FX 生产代码。
 *
 * <p>FX 换算 / 三列真值 / entry_fx_rate 写入 / FX_RATE_UNAVAILABLE / INSUFFICIENT_AVAILABLE_BALANCE
 * 的下沉逐字段断言由 {@code DefaultTradingRiskServiceRiskControlTests} 与
 * {@code ApplyOrderPlacementAccountChangeTests}（9a UT）覆盖，本测试不重复。
 *
 * <p>边界：B 阶段挂单触发资金不足沿用既有开仓 INSUFFICIENT_AVAILABLE_BALANCE 校验；
 * master §6.4 的 30084 PENDING CANCELLED + 站内信完整状态机属 C/D 阶段，本测试不涉及。
 */
class TradingPendingOrderTriggerOpeningFxRoutingTests {

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
    @Mock private com.falconx.trading.repository.MarketSymbolSpecRepository marketSymbolSpecRepository;

    // 被委托的 9a 已接 FX 开仓链路（mock，断言 triggerOpening 把开仓交给它）
    @Mock private TradingOrderPlacementApplicationService orderPlacement;

    private TradingCoreServiceProperties properties;
    private TradingPendingOrderApplicationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new TradingCoreServiceProperties();
        properties.setSettlementToken(SETTLEMENT_TOKEN);
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
     * Task 9c-1：异币种（EURAUD，base/quote 均非账户币 USDT）开仓挂单命中后，触发器把开仓委托给
     * 9a 的 {@code placeMarketOrder}，且命令完整携带挂单的 symbol/side/qty/leverage/marginMode。
     * 由此 placeMarketOrder 内部的 FX 换算 margin/fee、三列真值与 entry_fx_rate 写入（9a）全部覆盖挂单触发。
     */
    @Test
    void openingTriggerDelegatesToFxAwarePlaceMarketOrderForCrossCurrencySymbol() {
        long pendingId = 5001L;
        TradingPendingOrderTrigger order = openingOrder(
                pendingId, "EURAUD", TradingOrderSide.BUY,
                new BigDecimal("1000"), new BigDecimal("20"), TradingMarginMode.ISOLATED,
                new BigDecimal("100.00000000"), new BigDecimal("1.00000000"));
        when(pendingOrderRepository.findByIdForUpdate(pendingId)).thenReturn(Optional.of(order));
        // 9a 链路成交（FILLED）：内部已做 FX 换算 + 三列真值 + entry_fx_rate（本测试不重复断言其细节）
        TradingOrder filled = orderFixture(9999L, TradingOrderStatus.FILLED, null);
        when(orderPlacement.placeMarketOrder(any(PlaceMarketOrderCommand.class)))
                .thenReturn(new OrderPlacementResult(filled, null, null, null, false, null));
        when(pendingOrderRepository.markTriggered(eq(pendingId), eq(9999L))).thenReturn(1);

        boolean triggered = service.triggerOpening(pendingId, orderPlacement);

        Assertions.assertTrue(triggered, "EURAUD 开仓挂单命中应触发成功");

        // 关键断言：开仓确实委托给 9a 的 placeMarketOrder，且命令字段来自挂单
        ArgumentCaptor<PlaceMarketOrderCommand> captor = ArgumentCaptor.forClass(PlaceMarketOrderCommand.class);
        verify(orderPlacement, times(1)).placeMarketOrder(captor.capture());
        PlaceMarketOrderCommand command = captor.getValue();
        Assertions.assertEquals("EURAUD", command.symbol(), "应按挂单 symbol 走 9a 开仓");
        Assertions.assertEquals(TradingOrderSide.BUY, command.side());
        Assertions.assertEquals(0, command.quantity().compareTo(new BigDecimal("1000")));
        Assertions.assertEquals(0, command.leverage().compareTo(new BigDecimal("20")));
        Assertions.assertEquals(TradingMarginMode.ISOLATED, command.marginMode());

        // triggerOpening 自身不写持仓 / 不算汇率 —— 完全交给 9a 链路（避免独立开仓路径绕过 FX）
        verify(tradingPositionRepository, never()).save(any());
        verify(pendingOrderRepository).markTriggered(eq(pendingId), eq(9999L));
    }

    /**
     * Task 9c-2：FX 不可用时挂单触发开仓被 9a 链路拒单（不会用错误汇率开仓）。
     * 模拟 placeMarketOrder 返回 REJECTED + FX_RATE_UNAVAILABLE：触发器不创建持仓、不绕过汇率，
     * 仅按 9a 的 REJECTED 订单事实落痕（B 阶段行为，30084 CANCELLED+站内信完整态留 C/D）。
     */
    @Test
    void openingTriggerDoesNotOpenWhenFxRateUnavailable() {
        long pendingId = 5002L;
        TradingPendingOrderTrigger order = openingOrder(
                pendingId, "EURAUD", TradingOrderSide.BUY,
                new BigDecimal("1000"), new BigDecimal("20"), TradingMarginMode.ISOLATED,
                new BigDecimal("100.00000000"), new BigDecimal("1.00000000"));
        when(pendingOrderRepository.findByIdForUpdate(pendingId)).thenReturn(Optional.of(order));
        // 9a 链路 FX 不可用：返回 REJECTED 订单（position=null），reason=FX_RATE_UNAVAILABLE
        TradingOrder rejected = orderFixture(8888L, TradingOrderStatus.REJECTED, "FX_RATE_UNAVAILABLE");
        when(orderPlacement.placeMarketOrder(any(PlaceMarketOrderCommand.class)))
                .thenReturn(new OrderPlacementResult(rejected, null, null, null, false, "FX_RATE_UNAVAILABLE"));
        when(pendingOrderRepository.markTriggered(anyLong(), any())).thenReturn(1);

        service.triggerOpening(pendingId, orderPlacement);

        // 仍走 9a placeMarketOrder（拒单逻辑在 9a 内），triggerOpening 不自造开仓、不写持仓
        verify(orderPlacement, times(1)).placeMarketOrder(any(PlaceMarketOrderCommand.class));
        verify(tradingPositionRepository, never()).save(any());
        // 没有用错误汇率开仓：9a 拒单时 placeMarketOrder 返回的 position 为 null，不存在任何带 entry_fx_rate 的新持仓
        Assertions.assertEquals("FX_RATE_UNAVAILABLE", rejected.rejectReason());
    }

    /**
     * Task 9c-3：余额不足时挂单触发被 9a 链路的开仓资金校验拦截（B 阶段沿用 INSUFFICIENT_AVAILABLE_BALANCE）。
     * 模拟 placeMarketOrder 返回 REJECTED + INSUFFICIENT_AVAILABLE_BALANCE：不创建持仓。
     */
    @Test
    void openingTriggerRejectedByReusedBalanceCheckWhenInsufficient() {
        long pendingId = 5003L;
        TradingPendingOrderTrigger order = openingOrder(
                pendingId, "EURAUD", TradingOrderSide.SELL,
                new BigDecimal("500"), new BigDecimal("10"), TradingMarginMode.ISOLATED,
                new BigDecimal("50.00000000"), new BigDecimal("0.50000000"));
        when(pendingOrderRepository.findByIdForUpdate(pendingId)).thenReturn(Optional.of(order));
        TradingOrder rejected = orderFixture(7777L, TradingOrderStatus.REJECTED, "INSUFFICIENT_AVAILABLE_BALANCE");
        when(orderPlacement.placeMarketOrder(any(PlaceMarketOrderCommand.class)))
                .thenReturn(new OrderPlacementResult(rejected, null, null, null, false, "INSUFFICIENT_AVAILABLE_BALANCE"));
        when(pendingOrderRepository.markTriggered(anyLong(), any())).thenReturn(1);

        service.triggerOpening(pendingId, orderPlacement);

        verify(orderPlacement, times(1)).placeMarketOrder(any(PlaceMarketOrderCommand.class));
        verify(tradingPositionRepository, never()).save(any());
        Assertions.assertEquals("INSUFFICIENT_AVAILABLE_BALANCE", rejected.rejectReason());
    }

    // ---- helpers ----

    private TradingPendingOrderTrigger openingOrder(long id, String symbol, TradingOrderSide side,
                                                    BigDecimal quantity, BigDecimal leverage,
                                                    TradingMarginMode marginMode,
                                                    BigDecimal frozenMargin, BigDecimal frozenFee) {
        return new TradingPendingOrderTrigger(
                id, "po-" + id, 700L,
                "default", BigDecimal.ZERO, BigDecimal.ZERO,
                symbol,
                TradingPendingOrderType.LIMIT,
                side,
                quantity,
                new BigDecimal("1.20000000"), null,
                leverage, marginMode,
                frozenMargin, frozenFee,
                TradingPendingOrderStatus.PENDING,
                null, null, "client-" + id, null,
                null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private TradingOrder orderFixture(long orderId, TradingOrderStatus status, String rejectReason) {
        OffsetDateTime now = OffsetDateTime.now();
        return new TradingOrder(
                orderId, "order-" + orderId, 700L,
                "EURAUD", TradingOrderSide.BUY, TradingOrderType.MARKET,
                new BigDecimal("1000"), new BigDecimal("1.20000000"),
                status == TradingOrderStatus.FILLED ? new BigDecimal("1.20000000") : null,
                new BigDecimal("20"),
                BigDecimal.ZERO.setScale(8), BigDecimal.ZERO.setScale(8),
                BigDecimal.ZERO.setScale(6),
                "client-" + orderId, status, rejectReason, now, now);
    }
}
