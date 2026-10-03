package com.falconx.trading.application;

import com.falconx.common.error.CommonErrorCode;
import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.infrastructure.id.PublicIdentifierFormatter;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPendingOrderStatus;
import com.falconx.trading.entity.TradingPendingOrderTrigger;
import com.falconx.trading.entity.TradingPendingOrderTriggerKind;
import com.falconx.trading.entity.TradingPendingOrderType;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingRiskConfig;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingAccountRepository;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.market.contract.event.MarketGroupMarkupItem;
import com.falconx.trading.engine.SymbolTriggerActivityRegistry;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.service.TradingGroupMarkupService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-3-PENDING-ORDER：挂单应用服务。
 *
 * <p>本期实现：
 * <ul>
 *   <li>开仓挂单创建（LIMIT / STOP / STOP_LIMIT）+ 资金冻结</li>
 *   <li>SL_TP UPSERT（持仓 PATCH /sl-tp 走这里）</li>
 *   <li>撤单（释放资金）</li>
 *   <li>修改触发价 / 限价 / 数量（仅 PENDING）</li>
 * </ul>
 *
 * <p>触发执行逻辑由 {@code PendingOrderTriggerEvaluator} 选中后调
 * {@code TradingOrderPlacementApplicationService} / {@code TradingPositionCloseApplicationService}
 * 完成；本服务只管"挂单生命周期"，不参与触发后的市价开仓 / 反向平仓。
 */
@Service
public class TradingPendingOrderApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingPendingOrderApplicationService.class);

    private final TradingPendingOrderTriggerRepository pendingOrderRepository;
    private final TradingAccountService tradingAccountService;
    private final TradingAccountRepository tradingAccountRepository;
    private final TradingAccountSnapshotApplicationService accountSnapshotService;
    private final TradingPositionRepository tradingPositionRepository;
    private final TradingRiskConfigRepository tradingRiskConfigRepository;
    private final TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;
    private final TradingCoreServiceProperties properties;
    private final IdGenerator idGenerator;
    private final TradingGroupMarkupService tradingGroupMarkupService;
    private final SymbolTriggerActivityRegistry triggerActivityRegistry;
    private final MarketSymbolSpecRepository marketSymbolSpecRepository;

    public TradingPendingOrderApplicationService(TradingPendingOrderTriggerRepository pendingOrderRepository,
                                                  TradingAccountService tradingAccountService,
                                                  TradingAccountRepository tradingAccountRepository,
                                                  TradingAccountSnapshotApplicationService accountSnapshotService,
                                                  TradingPositionRepository tradingPositionRepository,
                                                  TradingRiskConfigRepository tradingRiskConfigRepository,
                                                  TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository,
                                                  TradingCoreServiceProperties properties,
                                                  IdGenerator idGenerator,
                                                  TradingGroupMarkupService tradingGroupMarkupService,
                                                  SymbolTriggerActivityRegistry triggerActivityRegistry,
                                                  MarketSymbolSpecRepository marketSymbolSpecRepository) {
        this.pendingOrderRepository = pendingOrderRepository;
        this.tradingAccountService = tradingAccountService;
        this.tradingAccountRepository = tradingAccountRepository;
        this.accountSnapshotService = accountSnapshotService;
        this.tradingPositionRepository = tradingPositionRepository;
        this.tradingRiskConfigRepository = tradingRiskConfigRepository;
        this.tradingQuoteSnapshotRepository = tradingQuoteSnapshotRepository;
        this.properties = properties;
        this.idGenerator = idGenerator;
        this.tradingGroupMarkupService = tradingGroupMarkupService;
        this.triggerActivityRegistry = triggerActivityRegistry;
        this.marketSymbolSpecRepository = marketSymbolSpecRepository;
    }

    /**
     * 创建开仓挂单（LIMIT / STOP / STOP_LIMIT）。
     */
    /**
     * STAGE-12-GROUP-MARKUP：向后兼容 10 参数 overload，groupCode 默认 "default"。
     */
    @Transactional
    public TradingPendingOrderTrigger createOpening(Long userId,
                                                     String symbol,
                                                     TradingOrderSide side,
                                                     TradingPendingOrderType orderType,
                                                     BigDecimal quantity,
                                                     BigDecimal triggerPrice,
                                                     BigDecimal limitPrice,
                                                     BigDecimal leverage,
                                                     TradingMarginMode marginMode,
                                                     String clientOrderId) {
        return createOpening(userId, "default", symbol, side, orderType, quantity,
                triggerPrice, limitPrice, leverage, marginMode, clientOrderId);
    }

    @Transactional
    public TradingPendingOrderTrigger createOpening(Long userId,
                                                     String groupCode,
                                                     String symbol,
                                                     TradingOrderSide side,
                                                     TradingPendingOrderType orderType,
                                                     BigDecimal quantity,
                                                     BigDecimal triggerPrice,
                                                     BigDecimal limitPrice,
                                                     BigDecimal leverage,
                                                     TradingMarginMode marginMode,
                                                     String clientOrderId) {
        if (!orderType.isOpening()) {
            throw new IllegalArgumentException("createOpening only accepts LIMIT/STOP/STOP_LIMIT");
        }
        // 0. 输入精度校验（PROD 精度统一）：quantity 小数位 ≤ qtyPrecision，
        //    用户给的价格字段（triggerPrice / limitPrice）小数位 ≤ pricePrecision，防超精度脏数据入库。
        validateInputPrecision(symbol, quantity, triggerPrice, limitPrice);
        // 1. 距离阈值校验
        validateTriggerDistance(symbol, triggerPrice);

        // 2. 估算冻结：notional × maintenanceMarginRate × (1/leverage) + estFee
        // 这里以 trigger_price 估算（市价开仓时实际可能不同，差额由市价单流程兜底）
        BigDecimal notional = quantity.multiply(triggerPrice);
        BigDecimal frozenMargin = notional.divide(leverage, 8, java.math.RoundingMode.HALF_UP);
        BigDecimal frozenFee = notional.multiply(properties.getDefaultFeeRate()).setScale(8, java.math.RoundingMode.HALF_UP);

        // 3. available 校验（CROSS 防御抽象）
        TradingAccount account = tradingAccountService.getOrCreateAccountForUpdate(userId, properties.getSettlementToken());
        TradingMarginMode effectiveMode = marginMode != null ? marginMode
                : (account.marginMode() != null ? account.marginMode() : TradingMarginMode.ISOLATED);
        String availabilityReject = accountSnapshotService.calculateAvailableForOrder(
                account, effectiveMode, frozenMargin, frozenFee);
        if (availabilityReject != null) {
            throw new TradingBusinessException(
                    "MARGIN_MODE_NOT_SUPPORTED".equals(availabilityReject)
                            ? TradingErrorCode.MARGIN_MODE_NOT_SUPPORTED
                            : TradingErrorCode.PENDING_ORDER_INSUFFICIENT_FUNDS,
                    Map.of("symbol", symbol, "required", frozenMargin.add(frozenFee), "available", account.available()));
        }

        // 4. 冻结资金（balance 不变，frozen 加）
        OffsetDateTime now = OffsetDateTime.now();
        account = tradingAccountService.reserveMargin(
                userId, properties.getSettlementToken(),
                frozenMargin.add(frozenFee),
                "pending-order-reserve:" + clientOrderId,
                clientOrderId, now);

        // 5. 写挂单表
        long id = idGenerator.nextId();
        // STAGE-12-GROUP-MARKUP: 冻结挂单创建时的组级 markup
        String resolvedGroupCode = (groupCode == null || groupCode.isBlank()) ? "default" : groupCode.trim();
        MarketGroupMarkupItem markup = tradingGroupMarkupService
                .find(resolvedGroupCode, symbol)
                .orElse(null);
        BigDecimal bidExtraAtCreate = markup == null ? BigDecimal.ZERO : markup.bidExtra();
        BigDecimal askExtraAtCreate = markup == null ? BigDecimal.ZERO : markup.askExtra();
        TradingPendingOrderTrigger order = new TradingPendingOrderTrigger(
                id,
                PublicIdentifierFormatter.orderNo(id),
                userId,
                resolvedGroupCode, bidExtraAtCreate, askExtraAtCreate,
                symbol, orderType, side, quantity,
                triggerPrice, limitPrice, leverage, effectiveMode,
                frozenMargin, frozenFee,
                TradingPendingOrderStatus.PENDING,
                null, null, clientOrderId, null,
                null, null, null,
                now, now);
        pendingOrderRepository.insert(order);
        triggerActivityRegistry.markPendingOrderActive(symbol);
        log.info("trading.pending-order.created id={} userId={} symbol={} orderType={} side={} triggerPrice={} qty={} frozenMargin={}",
                id, userId, symbol, orderType, side, triggerPrice, quantity, frozenMargin);
        return order;
    }

    /**
     * UPSERT 持仓 SL/TP。
     *
     * <p>调用方式：PATCH /api/v1/trading/positions/{id}/sl-tp Body 含 takeProfitPrice / stopLossPrice，
     * 任一字段为 null 表示清空对应 SL_TP 挂单。
     */
    @Transactional
    public void upsertSlTpForPosition(Long positionId,
                                       BigDecimal takeProfitPrice,
                                       BigDecimal stopLossPrice,
                                       boolean tpProvided,
                                       boolean slProvided) {
        TradingPosition position = tradingPositionRepository.findByIdForUpdate(positionId)
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.POSITION_NOT_FOUND, Map.of("positionId", positionId)));
        if (position.isTerminal()) {
            throw new TradingBusinessException(
                    TradingErrorCode.POSITION_ALREADY_CLOSED, Map.of("positionId", positionId));
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (tpProvided) {
            pendingOrderRepository.cancelSlTpByPositionId(positionId,
                    TradingPendingOrderTriggerKind.TAKE_PROFIT.code(), "REPLACED_BY_USER");
            if (takeProfitPrice != null) {
                createSlTpRow(position, takeProfitPrice, TradingPendingOrderTriggerKind.TAKE_PROFIT, now);
            }
        }
        if (slProvided) {
            pendingOrderRepository.cancelSlTpByPositionId(positionId,
                    TradingPendingOrderTriggerKind.STOP_LOSS.code(), "REPLACED_BY_USER");
            if (stopLossPrice != null) {
                createSlTpRow(position, stopLossPrice, TradingPendingOrderTriggerKind.STOP_LOSS, now);
            }
        }
        log.info("trading.pending-order.sl-tp.upserted positionId={} tp={} sl={}",
                positionId, takeProfitPrice, stopLossPrice);
    }

    private void createSlTpRow(TradingPosition position, BigDecimal triggerPrice,
                                TradingPendingOrderTriggerKind kind, OffsetDateTime now) {
        long id = idGenerator.nextId();
        // SL_TP.side = 持仓反方向（触发后的平仓方向）
        TradingOrderSide closingSide = position.side() == TradingOrderSide.BUY ? TradingOrderSide.SELL : TradingOrderSide.BUY;
        // STAGE-12-GROUP-MARKUP: SL/TP 触发器继承持仓的 groupCode / bidExtra / askExtra 冻结值，
        // 保证平仓时按持仓开仓时的组加点口径触发。
        TradingPendingOrderTrigger row = new TradingPendingOrderTrigger(
                id,
                PublicIdentifierFormatter.orderNo(id),
                position.userId(),
                position.groupCodeAtOpen() == null ? "default" : position.groupCodeAtOpen(),
                position.bidExtraAtOpen() == null ? BigDecimal.ZERO : position.bidExtraAtOpen(),
                position.askExtraAtOpen() == null ? BigDecimal.ZERO : position.askExtraAtOpen(),
                position.symbol(),
                TradingPendingOrderType.SL_TP, closingSide, position.quantity(),
                triggerPrice, null, position.leverage(),
                position.marginMode(),
                BigDecimal.ZERO.setScale(8), BigDecimal.ZERO.setScale(8),
                TradingPendingOrderStatus.PENDING,
                position.positionId(), kind, "sl-tp-" + position.positionId() + "-" + kind.code(),
                null, null, null, null,
                now, now);
        pendingOrderRepository.insert(row);
        triggerActivityRegistry.markPendingOrderActive(position.symbol());
    }

    /**
     * 用户撤单（仅开仓挂单走这个；SL_TP 撤销走 upsertSlTpForPosition 传 null）。
     */
    @Transactional
    public TradingPendingOrderTrigger cancel(Long userId, Long pendingOrderId, String reason) {
        TradingPendingOrderTrigger order = pendingOrderRepository.findByIdForUpdate(pendingOrderId)
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.PENDING_ORDER_NOT_FOUND, Map.of("id", pendingOrderId)));
        if (!order.userId().equals(userId)) {
            throw new TradingBusinessException(
                    TradingErrorCode.PENDING_ORDER_NOT_FOUND, Map.of("id", pendingOrderId));
        }
        if (order.status() != TradingPendingOrderStatus.PENDING) {
            throw new TradingBusinessException(
                    TradingErrorCode.PENDING_ORDER_INVALID_STATE,
                    Map.of("id", pendingOrderId, "currentStatus", order.status().name()));
        }
        int updated = pendingOrderRepository.markCancelled(pendingOrderId, reason);
        if (updated == 0) {
            throw new TradingBusinessException(
                    TradingErrorCode.PENDING_ORDER_INVALID_STATE,
                    Map.of("id", pendingOrderId, "currentStatus", "CONCURRENT_CHANGE"));
        }
        if (pendingOrderRepository.countPendingBySymbol(order.symbol()) == 0) {
            triggerActivityRegistry.markPendingOrderInactive(order.symbol());
        }
        // 释放冻结资金（SL_TP frozen 都是 0，跳过）
        BigDecimal totalFrozen = order.frozenMargin().add(order.frozenFee());
        if (totalFrozen.signum() > 0) {
            tradingAccountService.releaseFrozen(
                    userId, properties.getSettlementToken(),
                    totalFrozen,
                    "pending-order-release:" + order.orderNo(),
                    order.orderNo(),
                    OffsetDateTime.now());
        }
        log.info("trading.pending-order.cancelled id={} userId={} reason={}", pendingOrderId, userId, reason);
        return pendingOrderRepository.findById(pendingOrderId).orElseThrow();
    }

    /**
     * 修改挂单（仅 PENDING 状态；triggerPrice 修改后再次校验距离）。
     */
    @Transactional
    public TradingPendingOrderTrigger modify(Long userId, Long pendingOrderId,
                                              BigDecimal newTriggerPrice,
                                              BigDecimal newLimitPrice,
                                              BigDecimal newQuantity) {
        TradingPendingOrderTrigger order = pendingOrderRepository.findByIdForUpdate(pendingOrderId)
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.PENDING_ORDER_NOT_FOUND, Map.of("id", pendingOrderId)));
        if (!order.userId().equals(userId)) {
            throw new TradingBusinessException(
                    TradingErrorCode.PENDING_ORDER_NOT_FOUND, Map.of("id", pendingOrderId));
        }
        if (order.status() != TradingPendingOrderStatus.PENDING) {
            throw new TradingBusinessException(
                    TradingErrorCode.PENDING_ORDER_INVALID_STATE,
                    Map.of("id", pendingOrderId, "currentStatus", order.status().name()));
        }
        BigDecimal triggerPrice = newTriggerPrice != null ? newTriggerPrice : order.triggerPrice();
        BigDecimal limitPrice = newLimitPrice != null ? newLimitPrice : order.limitPrice();
        BigDecimal quantity = newQuantity != null ? newQuantity : order.quantity();
        validateTriggerDistance(order.symbol(), triggerPrice);
        int updated = pendingOrderRepository.updateTriggerPrices(pendingOrderId, triggerPrice, limitPrice, quantity);
        if (updated == 0) {
            throw new TradingBusinessException(
                    TradingErrorCode.PENDING_ORDER_INVALID_STATE,
                    Map.of("id", pendingOrderId, "currentStatus", "CONCURRENT_CHANGE"));
        }
        log.info("trading.pending-order.modified id={} userId={} triggerPrice={} limitPrice={} qty={}",
                pendingOrderId, userId, triggerPrice, limitPrice, quantity);
        return pendingOrderRepository.findById(pendingOrderId).orElseThrow();
    }

    /**
     * 触发执行入口。被 PendingOrderTriggerEvaluator 在 QuoteDrivenEngine 内选中后调用。
     *
     * <p>开仓挂单：FOR UPDATE 锁挂单 + 释放 frozen + 调用 OrderPlacement 转市价单 + 标记 TRIGGERED；
     * 平仓挂单：FOR UPDATE 锁挂单 + 调用 PositionClose 反向平 parent_position_id + 标记 TRIGGERED。
     *
     * <p>资金释放与市价单的资金占用是两个独立账务事件（用不同 idempotency key）；
     * 市价单失败时由 OrderPlacement 自身的 REJECTED 路径处理，挂单标记 REJECTED 释放冻结。
     *
     * @return true 表示成功触发并占有；false 表示已被其他链路触发或撤销（竞态）
     */
    @Transactional
    public boolean triggerOpening(Long pendingOrderId,
                                   com.falconx.trading.application.TradingOrderPlacementApplicationService orderPlacement) {
        TradingPendingOrderTrigger order = pendingOrderRepository.findByIdForUpdate(pendingOrderId).orElse(null);
        if (order == null || order.status() != TradingPendingOrderStatus.PENDING) {
            return false;
        }
        if (!order.orderType().isOpening()) {
            log.warn("trading.pending-order.trigger.skip id={} reason=not-opening orderType={}", pendingOrderId, order.orderType());
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();
        // 释放挂单冻结（账本单独记录）
        BigDecimal totalFrozen = order.frozenMargin().add(order.frozenFee());
        if (totalFrozen.signum() > 0) {
            tradingAccountService.releaseFrozen(
                    order.userId(), properties.getSettlementToken(),
                    totalFrozen, "pending-order-trigger-release:" + order.orderNo(),
                    order.orderNo(), now);
        }
        // 转市价单（自身资金流程会重新冻结 + 风控判定 + REJECTED 兜底）
        com.falconx.trading.command.PlaceMarketOrderCommand command =
                new com.falconx.trading.command.PlaceMarketOrderCommand(
                        order.userId(), order.symbol(), order.side(),
                        order.quantity(), order.leverage(), order.marginMode(),
                        null, null,
                        "trigger-" + order.orderNo());
        com.falconx.trading.dto.OrderPlacementResult result;
        try {
            result = orderPlacement.placeMarketOrder(command);
        } catch (RuntimeException ex) {
            log.error("trading.pending-order.trigger.failed id={} orderNo={} message={}",
                    pendingOrderId, order.orderNo(), ex.getMessage(), ex);
            pendingOrderRepository.markRejected(pendingOrderId, "TRIGGER_MARKET_ORDER_FAILED: " + ex.getMessage());
            return false;
        }
        if (result == null || result.order() == null) {
            pendingOrderRepository.markRejected(pendingOrderId, "TRIGGER_MARKET_ORDER_NO_RESULT");
            return false;
        }
        Long triggeredOrderId = result.order().orderId();
        int updated = pendingOrderRepository.markTriggered(pendingOrderId, triggeredOrderId);
        if (updated == 0) {
            log.warn("trading.pending-order.trigger.race id={} expectedStatus=PENDING actualStatus=<changed>", pendingOrderId);
            return false;
        }
        log.info("trading.pending-order.triggered id={} orderNo={} triggeredOrderId={} orderStatus={}",
                pendingOrderId, order.orderNo(), triggeredOrderId, result.order().status());
        return true;
    }

    @Transactional
    public boolean triggerSlTp(Long pendingOrderId,
                                com.falconx.trading.application.TradingPositionCloseApplicationService positionClose,
                                com.falconx.trading.entity.TradingQuoteSnapshot quote) {
        TradingPendingOrderTrigger order = pendingOrderRepository.findByIdForUpdate(pendingOrderId).orElse(null);
        if (order == null || order.status() != TradingPendingOrderStatus.PENDING) {
            return false;
        }
        if (order.orderType() != TradingPendingOrderType.SL_TP || order.parentPositionId() == null) {
            log.warn("trading.pending-order.trigger.skip id={} reason=not-sl-tp orderType={}", pendingOrderId, order.orderType());
            return false;
        }
        com.falconx.trading.entity.TradingPositionCloseReason closeReason =
                order.triggerKind() == TradingPendingOrderTriggerKind.TAKE_PROFIT
                        ? com.falconx.trading.entity.TradingPositionCloseReason.TAKE_PROFIT
                        : com.falconx.trading.entity.TradingPositionCloseReason.STOP_LOSS;
        com.falconx.trading.dto.PositionCloseResult result;
        try {
            result = positionClose.closePositionByTrigger(order.parentPositionId(), closeReason, quote);
        } catch (RuntimeException ex) {
            log.error("trading.pending-order.sl-tp.trigger.failed id={} positionId={} message={}",
                    pendingOrderId, order.parentPositionId(), ex.getMessage(), ex);
            pendingOrderRepository.markRejected(pendingOrderId, "TRIGGER_POSITION_CLOSE_FAILED: " + ex.getMessage());
            return false;
        }
        if (result == null) {
            // closePositionByTrigger 已对持仓做二次校验或并发判定，返回 null 视为本次未触达
            log.info("trading.pending-order.sl-tp.trigger.no-effect id={} positionId={} reason={}",
                    pendingOrderId, order.parentPositionId(), closeReason);
            return false;
        }
        int updated = pendingOrderRepository.markTriggered(pendingOrderId, null);
        if (updated == 0) {
            log.warn("trading.pending-order.sl-tp.trigger.race id={}", pendingOrderId);
            return false;
        }
        // 触发后级联撤掉同持仓的另一个 SL_TP（TP 触发了 SL 就不再需要）
        pendingOrderRepository.cancelAllSlTpByPositionId(order.parentPositionId(),
                "PARENT_CLOSED_BY_" + closeReason);
        log.info("trading.pending-order.sl-tp.triggered id={} positionId={} closeReason={}",
                pendingOrderId, order.parentPositionId(), closeReason);
        return true;
    }

    public TradingPendingOrderTrigger findById(Long id) {
        return pendingOrderRepository.findById(id).orElse(null);
    }

    public java.util.List<TradingPendingOrderTrigger> listUserOpeningPending(Long userId, String symbol,
                                                                              Integer statusCode,
                                                                              int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return pendingOrderRepository.findUserOpeningPending(userId, symbol, statusCode, offset, pageSize);
    }

    public long countUserOpeningPending(Long userId, String symbol, Integer statusCode) {
        return pendingOrderRepository.countUserOpeningPending(userId, symbol, statusCode);
    }

    /**
     * 输入精度校验（PROD 精度统一）：quantity 小数位不得超过 {@code SymbolSpec.qtyPrecision}，
     * 用户给的价格字段（triggerPrice / limitPrice，非空者）不得超过 {@code SymbolSpec.pricePrecision}。
     *
     * <p>SymbolSpec 复用 {@link DefaultTradingRiskService} 同款获取途径
     * （{@link MarketSymbolSpecRepository#findByPlatformSymbol} 读 Redis 共享快照，不跨 owner 直查 market schema）。
     * spec 缺失或对应 precision 为 null（过渡期旧快照）时跳过该维度校验，与市价单 spec.qtyPrecision()==null 时
     * 不强校验一致。
     */
    private void validateInputPrecision(String symbol, BigDecimal quantity,
                                        BigDecimal triggerPrice, BigDecimal limitPrice) {
        SymbolSpec spec = marketSymbolSpecRepository.findByPlatformSymbol(symbol).orElse(null);
        if (spec == null) {
            return;
        }
        if (spec.qtyPrecision() != null && quantity != null && scaleOf(quantity) > spec.qtyPrecision()) {
            throw new TradingBusinessException(
                    TradingErrorCode.QTY_PRECISION_EXCEEDED,
                    Map.of("symbol", symbol, "quantity", quantity, "qtyPrecision", spec.qtyPrecision()));
        }
        if (spec.pricePrecision() != null) {
            if (triggerPrice != null && scaleOf(triggerPrice) > spec.pricePrecision()) {
                throw new TradingBusinessException(
                        TradingErrorCode.PRICE_PRECISION_EXCEEDED,
                        Map.of("symbol", symbol, "price", triggerPrice, "pricePrecision", spec.pricePrecision()));
            }
            if (limitPrice != null && scaleOf(limitPrice) > spec.pricePrecision()) {
                throw new TradingBusinessException(
                        TradingErrorCode.PRICE_PRECISION_EXCEEDED,
                        Map.of("symbol", symbol, "price", limitPrice, "pricePrecision", spec.pricePrecision()));
            }
        }
    }

    /** BigDecimal 有效小数位（去尾零后；整数/负 scale 归 0）。 */
    private static int scaleOf(BigDecimal v) {
        return Math.max(0, v.stripTrailingZeros().scale());
    }

    private void validateTriggerDistance(String symbol, BigDecimal triggerPrice) {
        TradingRiskConfig riskConfig = tradingRiskConfigRepository.findBySymbol(symbol).orElse(null);
        if (riskConfig == null) return;
        BigDecimal minRatio = riskConfig.pendingOrderMinDistanceRatio();
        if (minRatio == null || minRatio.signum() <= 0) return;
        TradingQuoteSnapshot quote = tradingQuoteSnapshotRepository.findBySymbol(symbol).orElse(null);
        if (quote == null || quote.mark() == null) {
            // 没有 quote 时不强校验距离；触发引擎评估时若无 quote 也不会触发
            return;
        }
        BigDecimal markPrice = quote.mark();
        BigDecimal diff = triggerPrice.subtract(markPrice).abs();
        BigDecimal actualRatio = diff.divide(markPrice, 8, java.math.RoundingMode.HALF_UP);
        if (actualRatio.compareTo(minRatio) < 0) {
            throw new TradingBusinessException(
                    TradingErrorCode.PENDING_ORDER_TOO_CLOSE,
                    Map.of("symbol", symbol, "triggerPrice", triggerPrice,
                            "markPrice", markPrice, "actualRatio", actualRatio, "minRatio", minRatio));
        }
    }
}
