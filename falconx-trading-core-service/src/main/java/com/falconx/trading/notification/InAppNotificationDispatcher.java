package com.falconx.trading.notification;

import com.falconx.trading.entity.NotificationChannel;
import com.falconx.trading.entity.TradingNotification;
import com.falconx.trading.repository.TradingNotificationRepository;
import com.falconx.trading.websocket.TradingUserRealtimePushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * STAGE-8-NOTIFICATION Phase 1：IN_APP channel 投递。
 *
 * <p>写 t_notification 落库 + WebSocket 推送 notification.created。这是 V2 一期的主路径，
 * 与历史 {@code TradingNotificationApplicationService.create} 行为一致。
 */
@Component
public class InAppNotificationDispatcher implements NotificationChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InAppNotificationDispatcher.class);

    private final TradingNotificationRepository repository;
    private final TradingUserRealtimePushService realtimePushService;

    public InAppNotificationDispatcher(TradingNotificationRepository repository,
                                       TradingUserRealtimePushService realtimePushService) {
        this.repository = repository;
        this.realtimePushService = realtimePushService;
    }

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.IN_APP;
    }

    @Override
    public void dispatch(TradingNotification notification) {
        repository.insert(notification);
        log.info("trading.notification.in_app.persisted id={} userId={} type={} templateCode={}",
                notification.id(), notification.userId(), notification.type(), notification.templateCode());
        try {
            realtimePushService.publishNotificationCreated(notification);
        } catch (RuntimeException ex) {
            // WS 推送失败不阻塞落库，下次客户端拉列表能补
            log.warn("trading.notification.in_app.push-failed id={} reason={}",
                    notification.id(), ex.toString());
        }
    }
}
