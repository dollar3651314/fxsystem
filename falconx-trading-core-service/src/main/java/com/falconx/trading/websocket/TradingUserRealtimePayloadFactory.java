package com.falconx.trading.websocket;

import com.falconx.trading.dto.TradingAccountPositionResponse;
import com.falconx.trading.dto.TradingLiquidationItemResponse;
import com.falconx.trading.dto.TradingOrderItemResponse;
import com.falconx.trading.dto.TradingPositionItemResponse;
import com.falconx.trading.dto.TradingTradeItemResponse;
import com.falconx.trading.entity.TradingLiquidationLog;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrder;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.support.TradingPricingSupport;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 用户交易实时推送 payload 工厂。
 */
@Component
public class TradingUserRealtimePayloadFactory {

    private final TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;
    private final TradingRealtimeDualPnlSupport dualPnlSupport;

    @Autowired
    public TradingUserRealtimePayloadFactory(TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository,
                                             TradingRealtimeDualPnlSupport dualPnlSupport) {
        this.tradingQuoteSnapshotRepository = tradingQuoteSnapshotRepository;
        this.dualPnlSupport = dualPnlSupport;
    }

    /** 测试 / 跨包复用构造：直接注入结算币（账户币 AC），内部组装共享双币计算器。 */
    public TradingUserRealtimePayloadFactory(TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository,
                                      FxRateService fxRateService,
                                      MarketSymbolSpecRepository marketSymbolSpecRepository,
                                      String settlementCurrency) {
        this(tradingQuoteSnapshotRepository,
                new TradingRealtimeDualPnlSupport(fxRateService, marketSymbolSpecRepository, settlementCurrency));
    }

    public TradingOrderItemResponse toOrderPayload(TradingOrder order) {
        return new TradingOrderItemResponse(
                order.orderId(),
                order.orderNo(),
                order.symbol(),
                order.side().name(),
                order.orderType().name(),
                order.quantity(),
                order.requestedPrice(),
                order.filledPrice(),
                order.leverage(),
                order.margin(),
                order.fee(),
                order.clientOrderId(),
                order.status().name(),
                order.rejectReason(),
                order.createdAt(),
                order.updatedAt()
        );
    }

    public TradingTradeItemResponse toTradePayload(TradingTrade trade) {
        return new TradingTradeItemResponse(
                trade.tradeId(),
                trade.orderId(),
                trade.positionId(),
                trade.symbol(),
                trade.side().name(),
                trade.tradeType().name(),
                trade.quantity(),
                trade.price(),
                trade.fee(),
                trade.realizedPnl(),
                trade.tradedAt()
        );
    }

    /**
     * STAGE-14E1 Task 1（master §7.5 WebSocket 最终 break）：position.update 浮盈亏由单币
     * {@code unrealizedPnl}（QC 原币）硬切为双币 + 元数据。{@code unrealizedPnlInQuote}（QC 原币）
     * 由 markPrice 即时算；{@code unrealizedPnlInAccount}（AC 账户币）= inQuote × fxRate，口径同
     * D2 实时 MM（实时 FX，不可用降级开仓冻结 entryFxRate，不抛）。{@code isolatedMargin} 仅 ISOLATED 仓有值。
     */
    public TradingPositionItemResponse toPositionPayload(TradingPosition position) {
        TradingQuoteSnapshot quote = position.status() == TradingPositionStatus.OPEN
                ? tradingQuoteSnapshotRepository.findBySymbol(position.symbol()).orElse(null)
                : null;
        BigDecimal markPrice = TradingPricingSupport.resolvePositionMarkPrice(quote, position.side());
        // STAGE-12-GROUP-MARKUP: 有效标记价含 position 冻结 markup，与 entry 同口径
        BigDecimal effectiveMarkPrice = TradingPricingSupport.resolvePositionMarkPrice(quote, position);
        TradingRealtimeDualPnlSupport.DualPnl pnl = dualPnlSupport.computeDualPnl(position, effectiveMarkPrice);
        BigDecimal openFee = null;
        if (position.openFeeRate() != null && position.entryPrice() != null && position.quantity() != null) {
            openFee = TradingPricingSupport.scaleAmount(
                    position.openFeeRate().multiply(position.entryPrice()).multiply(position.quantity()));
        }
        return new TradingPositionItemResponse(
                position.positionId(),
                position.openingOrderId(),
                position.symbol(),
                position.side().name(),
                position.quantity(),
                position.entryPrice(),
                position.leverage(),
                position.margin(),
                position.marginMode().name(),
                position.liquidationPrice(),
                position.takeProfitPrice(),
                position.stopLossPrice(),
                markPrice,
                pnl.quoteCurrency(),
                pnl.fxRate(),
                pnl.inQuote(),
                pnl.inAccount(),
                isolatedMargin(position),
                position.closePrice(),
                position.closeReason() == null ? null : position.closeReason().name(),
                // realizedPnl 为 AC 账户币（落账时换算）；STAGE-14E1 起 unrealized 亦提供 AC 口径（unrealizedPnlInAccount），口径统一。
                position.realizedPnl(),
                position.status().name(),
                quote == null ? null : quote.stale(),
                quote == null ? null : quote.ts(),
                quote == null ? null : quote.source(),
                position.openedAt(),
                position.closedAt(),
                position.updatedAt(),
                openFee,
                position.openFeeRate(),
                position.groupCodeAtOpen(),
                position.bidExtraAtOpen(),
                position.askExtraAtOpen(),
                effectiveMarkPrice
        );
    }

    /**
     * STAGE-14E1 Task 2：账户快照 / account.update 内嵌持仓的双币响应。
     *
     * <p>与 {@link #toPositionPayload} 同源（同一 quote 解析 + 共享 {@link TradingRealtimeDualPnlSupport#computeDualPnl}），仅 DTO 形状不同
     * （{@link TradingAccountPositionResponse} 字段子集，无 leverage/openFee 等），避免账户内嵌持仓与
     * position 列表出现两种双币组装漂移。FX 不可用降级 entryFxRate（不抛），口径同 D2。
     */
    public TradingAccountPositionResponse toAccountPositionResponse(TradingPosition position) {
        TradingQuoteSnapshot quote = position.status() == TradingPositionStatus.OPEN
                ? tradingQuoteSnapshotRepository.findBySymbol(position.symbol()).orElse(null)
                : null;
        BigDecimal markPrice = TradingPricingSupport.resolvePositionMarkPrice(quote, position.side());
        BigDecimal effectiveMarkPrice = TradingPricingSupport.resolvePositionMarkPrice(quote, position);
        TradingRealtimeDualPnlSupport.DualPnl pnl = dualPnlSupport.computeDualPnl(position, effectiveMarkPrice);
        return new TradingAccountPositionResponse(
                position.positionId(),
                position.symbol(),
                position.side().name(),
                position.quantity(),
                position.entryPrice(),
                markPrice,
                pnl.quoteCurrency(),
                pnl.fxRate(),
                pnl.inQuote(),
                pnl.inAccount(),
                isolatedMargin(position),
                position.marginMode().name(),
                position.liquidationPrice(),
                position.takeProfitPrice(),
                position.stopLossPrice(),
                quote != null && quote.stale(),
                quote == null ? null : quote.ts(),
                quote == null ? null : quote.source()
        );
    }

    /**
     * STAGE-2-REALTIME-DATA Phase 1：PnL 实时推送 payload。
     *
     * <p>调用方在 QuoteDrivenEngine 收到 tick 后批量构造，
     * markPrice 由 caller 用 resolvePositionMarkPrice(quote, side) 算好；
     * liquidationDistance = (markPrice - liquidationPrice) × side（多正空负，0/负值告警接近强平）。
     */
    public TradingPositionPnlUpdatePayload toPnlPayload(TradingPosition position,
                                                        BigDecimal markPrice,
                                                        TradingQuoteSnapshot quote) {
        // markPrice 由 caller 用 resolvePositionMarkPrice(quote, side) 算好（已含 markup 口径，
        // 见 publishPositionPnlUpdates 用 resolvePositionMarkPrice(quote, side)）；双币口径同 toPositionPayload。
        return toPnlPayload(position, markPrice, quote, dualPnlSupport.computeDualPnl(position, markPrice));
    }

    /**
     * 双币 PnL 已由 caller 预算（{@link TradingRealtimeDualPnlSupport#computeDualPnl}）的重载：
     * caller 在同一遍循环里既要按 AC 账户币累计用户/平台汇总、又要构造本 payload 时，
     * 复用同一个 {@link TradingRealtimeDualPnlSupport.DualPnl} 实例——保证推送切片与汇总求和严格同源、
     * 且每仓每 tick 只算一次（省一次 SymbolSpec 查询 + FX 查询）。
     */
    public TradingPositionPnlUpdatePayload toPnlPayload(TradingPosition position,
                                                        BigDecimal markPrice,
                                                        TradingQuoteSnapshot quote,
                                                        TradingRealtimeDualPnlSupport.DualPnl pnl) {
        BigDecimal liquidationDistance = null;
        if (position.liquidationPrice() != null && markPrice != null) {
            BigDecimal diff = markPrice.subtract(position.liquidationPrice());
            // 多头：mark > liq 为正（离强平远）；空头：mark < liq 为正
            liquidationDistance = position.side() == com.falconx.trading.entity.TradingOrderSide.BUY
                    ? diff
                    : diff.negate();
        }
        return new TradingPositionPnlUpdatePayload(
                position.positionId(),
                position.symbol(),
                position.side().name(),
                markPrice,
                pnl.quoteCurrency(),
                pnl.fxRate(),
                pnl.inQuote(),
                pnl.inAccount(),
                isolatedMargin(position),
                liquidationDistance,
                quote == null ? null : quote.ts()
        );
    }

    /**
     * 逐仓占用保证金：ISOLATED 仓返回 position.margin（AC 账户币），CROSS 仓返回 null
     * （保证金不归属单仓）。不新增 DB 列，用 margin 映射。
     */
    private static BigDecimal isolatedMargin(TradingPosition position) {
        return position.marginMode() == TradingMarginMode.ISOLATED ? position.margin() : null;
    }

    public TradingLiquidationItemResponse toLiquidationPayload(TradingLiquidationLog liquidationLog) {
        return new TradingLiquidationItemResponse(
                liquidationLog.liquidationLogId(),
                liquidationLog.positionId(),
                liquidationLog.symbol(),
                liquidationLog.side().name(),
                liquidationLog.marginMode().name(),
                liquidationLog.quantity(),
                liquidationLog.entryPrice(),
                liquidationLog.liquidationPrice(),
                liquidationLog.markPrice(),
                liquidationLog.priceTs(),
                liquidationLog.priceSource(),
                liquidationLog.loss(),
                liquidationLog.fee(),
                liquidationLog.marginReleased(),
                liquidationLog.platformCoveredLoss(),
                liquidationLog.createdAt()
        );
    }
}
