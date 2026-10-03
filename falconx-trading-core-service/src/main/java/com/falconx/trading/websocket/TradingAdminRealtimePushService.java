package com.falconx.trading.websocket;

import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingRiskControlAction;
import com.falconx.trading.entity.TradingRiskExposure;
import com.falconx.trading.entity.TradingRiskSwitch;
import com.falconx.trading.support.TradingPricingSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * STAGE-2-REALTIME-DATA Phase 2：管理端实时推送服务。
 *
 * <p>本服务广播 admin 看板事件：
 * <ul>
 *   <li>{@code admin.exposure.update}：净敞口变化（按 symbol 200ms 节流由调用方控制）</li>
 *   <li>{@code admin.risk-action.changed}：风控动作激活/停用</li>
 *   <li>{@code admin.risk-switch.changed}：风控开关切换</li>
 * </ul>
 *
 * <p>推送失败 best-effort，不影响业务事务。
 */
@Service
public class TradingAdminRealtimePushService {

    private static final Logger log = LoggerFactory.getLogger(TradingAdminRealtimePushService.class);

    private final TradingUserWebSocketSessionRegistry sessionRegistry;
    private final AdminPositionSummaryAggregator summaryAggregator;
    private final TradingRealtimeDualPnlSupport dualPnlSupport;

    public TradingAdminRealtimePushService(TradingUserWebSocketSessionRegistry sessionRegistry,
                                            AdminPositionSummaryAggregator summaryAggregator,
                                            TradingRealtimeDualPnlSupport dualPnlSupport) {
        this.sessionRegistry = sessionRegistry;
        this.summaryAggregator = summaryAggregator;
        this.dualPnlSupport = dualPnlSupport;
    }

    public int publishExposureUpdate(TradingRiskExposure exposure, OffsetDateTime quoteTs) {
        if (exposure == null) {
            return 0;
        }
        AdminExposureUpdatePayload payload = new AdminExposureUpdatePayload(
                exposure.symbol(),
                dualPnlSupport.resolveQuoteCurrency(exposure.symbol()),
                exposure.totalLongQty(),
                exposure.totalShortQty(),
                exposure.netExposure(),
                exposure.netExposureUsd(),
                quoteTs
        );
        int recipients = broadcast("admin.exposure.update", TradingUserWebSocketSessionRegistry.CHANNEL_ADMIN_EXPOSURE, payload);
        if (recipients > 0) {
            log.debug("trading.websocket.realtime.admin.exposure symbol={} netUsd={} recipientCount={}",
                    exposure.symbol(), exposure.netExposureUsd(), recipients);
        }
        return recipients;
    }

    /**
     * 广播该 symbol 当前全部 OPEN 持仓的 PnL patch 给所有订阅 admin.positions 频道的 admin。
     * 复用 QuoteDrivenEngine 已算过的 markPrice / quote，逐持仓按 side 取 bid/ask
     * 重算 effective markPrice + 双币浮盈亏（STAGE-14E2 复用 E1 {@link TradingRealtimeDualPnlSupport}，
     * 口径与 user position.pnl 完全一致）。
     *
     * <p>按 symbol 200ms 节流由调用方 AdminPositionPushThrottler 在外部完成。
     *
     * @return 总推送命中数（admin session 数 × admin channel 订阅数）
     */
    public int publishPositionPnlUpdates(String symbol,
                                          List<TradingPosition> openPositions,
                                          TradingQuoteSnapshot quote,
                                          OffsetDateTime quoteTs) {
        if (symbol == null || openPositions == null || openPositions.isEmpty() || quote == null) {
            return 0;
        }
        // STAGE-14E2 Task 1：构造双币 patch items（口径复用 E1 共享 computeDualPnl，无并行实现）
        // + 累计本 symbol Σ unrealizedPnlInAccount（AC 账户币，跨 symbol 可加，写聚合器供平台汇总）
        BigDecimal symbolTotalPnl = BigDecimal.ZERO;
        boolean hasAnyValidPnl = false;
        List<AdminPositionPnlUpdatePayload.Item> items = new java.util.ArrayList<>(openPositions.size());
        for (TradingPosition p : openPositions) {
            BigDecimal markPrice = TradingPricingSupport.resolvePositionMarkPrice(quote, p.side());
            TradingRealtimeDualPnlSupport.DualPnl pnl = dualPnlSupport.computeDualPnl(p, markPrice);
            // 平台汇总按 AC 账户币聚合（跨 symbol 求和有意义）；AC 缺失（FX+entryFxRate 均不可用）不计入
            if (pnl.inAccount() != null) {
                symbolTotalPnl = symbolTotalPnl.add(pnl.inAccount());
                hasAnyValidPnl = true;
            }
            items.add(new AdminPositionPnlUpdatePayload.Item(
                    p.positionId() == null ? null : String.valueOf(p.positionId()),
                    p.userId() == null ? null : String.valueOf(p.userId()),
                    p.side() == null ? null : p.side().name(),
                    markPrice,
                    pnl.quoteCurrency(),
                    pnl.fxRate(),
                    pnl.inQuote(),
                    pnl.inAccount()
            ));
        }
        // 写聚合器：全部缺 quote/AC 时用 null 移除条目（避免陈旧值串入平台 sum）
        summaryAggregator.updateSymbol(symbol, hasAnyValidPnl ? symbolTotalPnl : null);

        AdminPositionPnlUpdatePayload payload = new AdminPositionPnlUpdatePayload(symbol, quoteTs, items);
        int recipients = broadcast("admin.position.update",
                TradingUserWebSocketSessionRegistry.CHANNEL_ADMIN_POSITIONS, payload);
        if (recipients > 0) {
            log.debug("trading.websocket.realtime.admin.position symbol={} count={} recipientCount={}",
                    symbol, items.size(), recipients);
        }
        return recipients;
    }

    /**
     * 推送平台 totalUnrealizedPnl 汇总 patch。读自 AdminPositionSummaryAggregator 跨 symbol 求和，
     * 调用方负责节流（AdminPositionSummaryPushThrottler 全局 500ms）。
     */
    public int publishPositionSummary(OffsetDateTime computedAt) {
        BigDecimal total = summaryAggregator.platformTotalUnrealizedPnl();
        AdminPositionSummaryUpdatePayload payload = new AdminPositionSummaryUpdatePayload(
                TradingPricingSupport.scaleAmount(total),
                computedAt
        );
        int recipients = broadcast("admin.position.summary",
                TradingUserWebSocketSessionRegistry.CHANNEL_ADMIN_POSITIONS, payload);
        if (recipients > 0) {
            log.debug("trading.websocket.realtime.admin.position-summary totalPnl={} symbolsTracked={} recipientCount={}",
                    total, summaryAggregator.trackedSymbolCount(), recipients);
        }
        return recipients;
    }

    public int publishRiskActionChanged(TradingRiskControlAction action, OffsetDateTime occurredAt) {
        if (action == null) {
            return 0;
        }
        AdminRiskActionChangedPayload payload = new AdminRiskActionChangedPayload(
                action.actionId(),
                action.symbol(),
                action.actionType() == null ? null : action.actionType().name(),
                action.triggerSource(),
                action.triggerReason(),
                action.active(),
                occurredAt
        );
        int recipients = broadcast("admin.risk-action.changed", TradingUserWebSocketSessionRegistry.CHANNEL_ADMIN_RISK_ACTIONS, payload);
        log.info("trading.websocket.realtime.admin.risk-action actionId={} symbol={} actionType={} active={} recipientCount={}",
                action.actionId(), action.symbol(), action.actionType(), action.active(), recipients);
        return recipients;
    }

    /**
     * STAGE-7-WITHDRAW Phase 4 §4 commit C：广播出金状态变化到 admin.withdraws 频道。
     * 在 trading-core 的 wallet 事件 consumer 处理完状态切换后调用（best-effort，不影响事务）。
     */
    public int publishWithdrawStatusChanged(long withdrawId, long userId, String status,
                                             String txHash, Integer confirmations,
                                             String failureReason) {
        AdminWithdrawStatusChangedPayload payload = new AdminWithdrawStatusChangedPayload(
                String.valueOf(withdrawId), String.valueOf(userId), status,
                txHash, confirmations, failureReason,
                OffsetDateTime.now()
        );
        int recipients = broadcast("admin.withdraw.status-changed",
                TradingUserWebSocketSessionRegistry.CHANNEL_ADMIN_WITHDRAWS, payload);
        if (recipients > 0) {
            log.info("trading.websocket.realtime.admin.withdraw.status-changed withdrawId={} status={} recipientCount={}",
                    withdrawId, status, recipients);
        }
        return recipients;
    }

    public int publishRiskSwitchChanged(TradingRiskSwitch riskSwitch) {
        if (riskSwitch == null) {
            return 0;
        }
        AdminRiskSwitchChangedPayload payload = new AdminRiskSwitchChangedPayload(
                riskSwitch.switchKey(),
                riskSwitch.enabled(),
                riskSwitch.updatedBy(),
                riskSwitch.updatedAt()
        );
        int recipients = broadcast("admin.risk-switch.changed", TradingUserWebSocketSessionRegistry.CHANNEL_ADMIN_RISK_SWITCHES, payload);
        log.info("trading.websocket.realtime.admin.risk-switch switchKey={} enabled={} updatedBy={} recipientCount={}",
                riskSwitch.switchKey(), riskSwitch.enabled(), riskSwitch.updatedBy(), recipients);
        return recipients;
    }

    private int broadcast(String type, String channel, Object data) {
        return sessionRegistry.broadcastToAdmins(channel, new TradingUserWebSocketEnvelope(
                type,
                channel,
                null,
                data,
                OffsetDateTime.now()
        ));
    }
}
