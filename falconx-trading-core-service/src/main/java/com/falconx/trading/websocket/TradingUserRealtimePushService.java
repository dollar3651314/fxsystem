package com.falconx.trading.websocket;

import com.falconx.trading.application.TradingAccountSnapshotApplicationService;
import com.falconx.trading.application.TradingUserQueryApplicationService;
import com.falconx.trading.command.ListTradingLedgerEntriesCommand;
import com.falconx.trading.dto.TradingLedgerItemResponse;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingLiquidationLog;
import com.falconx.trading.entity.TradingOrder;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.event.TradingHedgeAlertEvent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用户交易实时推送服务。
 *
 * <p>该服务只负责事务提交后的 best-effort 通知；
 * 推送失败不改变已经提交的订单、持仓和账户事实。
 */
@Service
public class TradingUserRealtimePushService {

    private static final Logger log = LoggerFactory.getLogger(TradingUserRealtimePushService.class);

    private final TradingUserWebSocketSessionRegistry sessionRegistry;
    private final TradingAccountSnapshotApplicationService accountSnapshotApplicationService;
    private final TradingUserQueryApplicationService tradingUserQueryApplicationService;
    private final TradingUserRealtimePayloadFactory payloadFactory;
    private final UserPositionSummaryAggregator userSummaryAggregator;
    private final UserPositionSummaryPushThrottler userSummaryPushThrottler;
    private final TradingRealtimeDualPnlSupport dualPnlSupport;

    public TradingUserRealtimePushService(TradingUserWebSocketSessionRegistry sessionRegistry,
                                          TradingAccountSnapshotApplicationService accountSnapshotApplicationService,
                                          TradingUserQueryApplicationService tradingUserQueryApplicationService,
                                          TradingUserRealtimePayloadFactory payloadFactory,
                                          UserPositionSummaryAggregator userSummaryAggregator,
                                          UserPositionSummaryPushThrottler userSummaryPushThrottler,
                                          TradingRealtimeDualPnlSupport dualPnlSupport) {
        this.sessionRegistry = sessionRegistry;
        this.accountSnapshotApplicationService = accountSnapshotApplicationService;
        this.tradingUserQueryApplicationService = tradingUserQueryApplicationService;
        this.payloadFactory = payloadFactory;
        this.userSummaryAggregator = userSummaryAggregator;
        this.userSummaryPushThrottler = userSummaryPushThrottler;
        this.dualPnlSupport = dualPnlSupport;
    }

    public void publishOrderRejected(TradingOrder order, TradingAccount account) {
        int recipients = push(order.userId(), "order.update", TradingUserWebSocketSessionRegistry.CHANNEL_ORDERS,
                payloadFactory.toOrderPayload(order));
        recipients += pushAccountUpdated(account);
        recipients += pushLatestLedger(account.userId());
        log.info("trading.websocket.realtime.order.rejected userId={} orderId={} recipientCount={}",
                order.userId(),
                order.orderId(),
                recipients);
    }

    public void publishOrderFilled(TradingOrder order, TradingPosition position, TradingTrade trade, TradingAccount account) {
        int recipients = push(order.userId(), "order.update", TradingUserWebSocketSessionRegistry.CHANNEL_ORDERS,
                payloadFactory.toOrderPayload(order));
        recipients += push(position.userId(), "position.update", TradingUserWebSocketSessionRegistry.CHANNEL_POSITIONS,
                payloadFactory.toPositionPayload(position));
        recipients += push(trade.userId(), "trade.created", TradingUserWebSocketSessionRegistry.CHANNEL_TRADES,
                payloadFactory.toTradePayload(trade));
        recipients += pushAccountUpdated(account);
        recipients += pushLatestLedger(account.userId());
        log.info("trading.websocket.realtime.order.filled userId={} orderId={} positionId={} tradeId={} recipientCount={}",
                order.userId(),
                order.orderId(),
                position.positionId(),
                trade.tradeId(),
                recipients);
    }

    public void publishPositionClosed(TradingPosition position,
                                      TradingTrade trade,
                                      TradingAccount account,
                                      TradingLiquidationLog liquidationLog) {
        int recipients = push(position.userId(), "position.update", TradingUserWebSocketSessionRegistry.CHANNEL_POSITIONS,
                payloadFactory.toPositionPayload(position));
        recipients += push(trade.userId(), "trade.created", TradingUserWebSocketSessionRegistry.CHANNEL_TRADES,
                payloadFactory.toTradePayload(trade));
        recipients += pushAccountUpdated(account);
        recipients += pushLatestLedger(account.userId());
        if (liquidationLog != null) {
            recipients += push(position.userId(), "liquidation.update", TradingUserWebSocketSessionRegistry.CHANNEL_LIQUIDATIONS,
                    payloadFactory.toLiquidationPayload(liquidationLog));
        }
        log.info("trading.websocket.realtime.position.closed userId={} positionId={} closeReason={} tradeId={} liquidationLogId={} recipientCount={}",
                position.userId(),
                position.positionId(),
                position.closeReason(),
                trade.tradeId(),
                liquidationLog == null ? null : liquidationLog.liquidationLogId(),
                recipients);
    }

    public void publishMarginUpdated(TradingPosition position, TradingAccount account) {
        int recipients = push(position.userId(), "position.update", TradingUserWebSocketSessionRegistry.CHANNEL_POSITIONS,
                payloadFactory.toPositionPayload(position));
        recipients += push(position.userId(), "margin.update", TradingUserWebSocketSessionRegistry.CHANNEL_MARGIN,
                Map.of(
                        "position", payloadFactory.toPositionPayload(position),
                        "account", accountSnapshotApplicationService.toResponse(account)
                ));
        recipients += pushAccountUpdated(account);
        recipients += pushLatestLedger(account.userId());
        log.info("trading.websocket.realtime.margin.updated userId={} positionId={} recipientCount={}",
                position.userId(),
                position.positionId(),
                recipients);
    }

    public void publishRiskControlsUpdated(TradingPosition position) {
        int recipients = push(position.userId(), "position.update", TradingUserWebSocketSessionRegistry.CHANNEL_POSITIONS,
                payloadFactory.toPositionPayload(position));
        recipients += push(position.userId(), "risk-controls.update", TradingUserWebSocketSessionRegistry.CHANNEL_POSITIONS,
                payloadFactory.toPositionPayload(position));
        log.info("trading.websocket.realtime.risk-controls.updated userId={} positionId={} recipientCount={}",
                position.userId(),
                position.positionId(),
                recipients);
    }

    /**
     * STAGE-8-NOTIFICATION：站内信新建推送。
     */
    public int publishNotificationCreated(com.falconx.trading.entity.TradingNotification n) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("id", String.valueOf(n.id()));
        payload.put("type", n.type());
        payload.put("level", n.level().name());
        payload.put("title", n.title());
        payload.put("body", n.body());
        payload.put("relatedKey", n.relatedKey());
        payload.put("relatedId", n.relatedId() == null ? null : String.valueOf(n.relatedId()));
        payload.put("status", n.status().name());
        payload.put("createdAt", n.createdAt());
        int recipients = push(n.userId(), "notification.created",
                TradingUserWebSocketSessionRegistry.CHANNEL_NOTIFICATIONS, payload);
        log.info("trading.websocket.realtime.notification.created userId={} id={} type={} recipientCount={}",
                n.userId(), n.id(), n.type(), recipients);
        return recipients;
    }

    /**
     * STAGE-4-PRICE-ALERT：价格告警触发推送。
     */
    public int publishPriceAlertTriggered(Long userId, PriceAlertTriggeredPayload payload) {
        int recipients = push(userId, "price.alert.triggered",
                TradingUserWebSocketSessionRegistry.CHANNEL_PRICE_ALERTS, payload);
        log.info("trading.websocket.realtime.price-alert.triggered userId={} alertId={} symbol={} triggerCount={} recipientCount={}",
                userId, payload.alertId(), payload.symbol(), payload.triggerCount(), recipients);
        return recipients;
    }

    public void publishRiskWarning(Long userId, TradingHedgeAlertEvent event) {
        int recipients = push(userId, "risk.warning", TradingUserWebSocketSessionRegistry.CHANNEL_POSITIONS,
                Map.of(
                        "symbol", event.symbol(),
                        "actionType", "REJECT_OPEN",
                        "netExposureUsd", event.netExposureUsd(),
                        "hedgeThresholdUsd", event.hedgeThresholdUsd()
                ));
        log.info("trading.websocket.realtime.risk.warning userId={} symbol={} netExposureUsd={} recipientCount={}",
                userId,
                event.symbol(),
                event.netExposureUsd(),
                recipients);
    }

    /**
     * STAGE-2-REALTIME-DATA Phase 1：批量推送同 symbol 多持仓的 PnL 更新。
     *
     * <p>调用方（QuoteDrivenEngine）传入该 symbol 当前的全部 OPEN 持仓 + 最新 quote；
     * 本方法按 user 维度调用 broadcast，复用 CHANNEL_POSITIONS 订阅。
     *
     * <p>节流由调用方 PositionPnlPushThrottler 在外部完成（按 symbol 100ms）。
     *
     * @return 总推送命中数
     */
    public int publishPositionPnlUpdates(List<TradingPosition> openPositions,
                                          BigDecimal sharedMarkPrice,
                                          TradingQuoteSnapshot quote) {
        if (openPositions == null || openPositions.isEmpty() || sharedMarkPrice == null || quote == null) {
            return 0;
        }
        // 同一遍循环：per-position push + per-user 累计 Σ unrealizedPnl（本 symbol 上该用户的）
        int recipients = 0;
        Map<Long, BigDecimal> userTotalForSymbol = new java.util.HashMap<>();
        for (TradingPosition position : openPositions) {
            BigDecimal markForSide = com.falconx.trading.support.TradingPricingSupport
                    .resolvePositionMarkPrice(quote, position.side());
            BigDecimal effectiveMark = markForSide != null ? markForSide : sharedMarkPrice;
            // 双币口径单一来源：computeDualPnl 一次，payload 切片与用户跨 symbol 汇总同源（口径复用 admin 侧）。
            TradingRealtimeDualPnlSupport.DualPnl pnl = dualPnlSupport.computeDualPnl(position, effectiveMark);
            // 用户汇总按 AC 账户币累计（跨 symbol 可加，修复原 QC 原币混币相加）；
            // AC 缺失（FX + 开仓冻结 entryFxRate 均不可用）不计入，镜像 TradingAdminRealtimePushService。
            if (pnl.inAccount() != null && position.userId() != null) {
                userTotalForSymbol.merge(position.userId(), pnl.inAccount(), BigDecimal::add);
            }
            recipients += push(
                    position.userId(),
                    "position.pnl",
                    TradingUserWebSocketSessionRegistry.CHANNEL_POSITIONS,
                    payloadFactory.toPnlPayload(position, effectiveMark, quote, pnl)
            );
        }

        // 整体覆盖该 symbol 在聚合器里的 user → Σ PnL 快照；
        // 用户若已平掉该 symbol 所有 OPEN 持仓，下一个 tick 进来时不在 userTotalForSymbol → 自动移除
        userSummaryAggregator.updateSymbol(quote.symbol(), userTotalForSymbol);

        // 受本 tick 影响的用户：节流后推 user.position.summary（跨 symbol 求和）
        for (Long userId : userTotalForSymbol.keySet()) {
            if (userId == null || !userSummaryPushThrottler.shouldPush(userId)) continue;
            BigDecimal totalForUser = userSummaryAggregator.totalForUser(userId);
            UserPositionSummaryUpdatePayload payload = new UserPositionSummaryUpdatePayload(
                    com.falconx.trading.support.TradingPricingSupport.scaleAmount(totalForUser),
                    quote.ts()
            );
            push(userId, "user.position.summary", TradingUserWebSocketSessionRegistry.CHANNEL_POSITIONS, payload);
        }
        return recipients;
    }

    /**
     * 直接读聚合器算用户当前的 total unrealizedPnl（REST 接口走这个路径，
     * 不强行触发 tick；首次进入页面 / 长时间无 tick 时也能拿到上次 tick 留的 cached 值）。
     *
     * <p>注意：用户首次登录、聚合器还没收到任何 tick 时返回 0；UI 应再读 REST 持仓接口
     * 自己累加做兜底。
     */
    public BigDecimal currentTotalUnrealizedPnlForUser(long userId) {
        return com.falconx.trading.support.TradingPricingSupport
                .scaleAmount(userSummaryAggregator.totalForUser(userId));
    }

    private int pushAccountUpdated(TradingAccount account) {
        return push(account.userId(), "account.update", TradingUserWebSocketSessionRegistry.CHANNEL_ACCOUNT,
                accountSnapshotApplicationService.toResponse(account));
    }

    private int pushLatestLedger(Long userId) {
        TradingLedgerItemResponse latest = tradingUserQueryApplicationService
                .listLedgerEntries(new ListTradingLedgerEntriesCommand(userId, 1, 1, null, null, null))
                .items()
                .stream()
                .findFirst()
                .orElse(null);
        if (latest == null) {
            return 0;
        }
        return push(userId, "ledger.created", TradingUserWebSocketSessionRegistry.CHANNEL_LEDGER, latest);
    }

    private int push(Long userId, String type, String channel, Object data) {
        return sessionRegistry.broadcast(userId, channel, new TradingUserWebSocketEnvelope(
                type,
                channel,
                null,
                data,
                OffsetDateTime.now()
        ));
    }
}
