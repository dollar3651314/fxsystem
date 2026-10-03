package com.falconx.trading.consumer;

import com.falconx.trading.application.TradingNotificationApplicationService;
import com.falconx.trading.event.TradingHedgeAlertEvent;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.websocket.TradingUserRealtimePushService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * BBook 风险告警事件监听器。
 *
 * <p>在敞口超阈值事件提交后，向受影响品种的持仓用户：
 * <ol>
 *   <li>推送 {@code risk.warning} WebSocket 通知（短时实时提醒）</li>
 *   <li>STAGE-8-NOTIFICATION Phase 2：写 {@code t_notification}（持久化记录，模板 RISK_ACTION_TRIGGERED）</li>
 * </ol>
 *
 * <p>两条推送都是 best-effort，失败不影响已提交的风控动作事实。
 */
@Component
public class TradingHedgeAlertEventListener {

    private static final Logger log = LoggerFactory.getLogger(TradingHedgeAlertEventListener.class);

    private final TradingPositionRepository tradingPositionRepository;
    private final TradingUserRealtimePushService tradingUserRealtimePushService;
    private final TradingNotificationApplicationService notificationService;

    public TradingHedgeAlertEventListener(TradingPositionRepository tradingPositionRepository,
                                          TradingUserRealtimePushService tradingUserRealtimePushService,
                                          TradingNotificationApplicationService notificationService) {
        this.tradingPositionRepository = tradingPositionRepository;
        this.tradingUserRealtimePushService = tradingUserRealtimePushService;
        this.notificationService = notificationService;
    }

    @EventListener
    public void onHedgeAlert(TradingHedgeAlertEvent event) {
        try {
            List<Long> affectedUserIds = tradingPositionRepository.findDistinctUserIdsBySymbolOpen(event.symbol());
            String actionType = event.triggerSource() == null ? "HEDGE_ALERT" : event.triggerSource().name();
            String reason = event.symbol() + " 净敞口 " + event.netExposureUsd()
                    + " USD 超过阈值 " + event.hedgeThresholdUsd() + " USD";
            Map<String, String> params = Map.of("actionType", actionType, "reason", reason);
            for (Long userId : affectedUserIds) {
                tradingUserRealtimePushService.publishRiskWarning(userId, event);
                try {
                    notificationService.send(
                            "RISK_ACTION_TRIGGERED",
                            userId,
                            "RISK_ACTION_TRIGGERED",
                            params,
                            "RISK",
                            event.hedgeLogId(),
                            null
                    );
                } catch (RuntimeException ex) {
                    log.warn("trading.risk.hedge.alert.notification.send.failed userId={} symbol={} reason={}",
                            userId, event.symbol(), ex.toString());
                }
            }
            log.info("trading.risk.hedge.alert.notified symbol={} affectedUsers={}",
                    event.symbol(), affectedUserIds.size());
        } catch (RuntimeException e) {
            log.error("trading.risk.hedge.alert.notify.failed symbol={}", event.symbol(), e);
        }
    }
}
