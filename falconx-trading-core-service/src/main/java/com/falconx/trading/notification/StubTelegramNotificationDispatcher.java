package com.falconx.trading.notification;

import com.falconx.trading.entity.NotificationChannel;
import com.falconx.trading.entity.TradingNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * STAGE-8-NOTIFICATION Phase 1：TELEGRAM channel stub 投递。
 *
 * <p>V2 一期仅 log，不真实发送。预留接口给后续接 Telegram Bot API。
 */
@Component
public class StubTelegramNotificationDispatcher implements NotificationChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(StubTelegramNotificationDispatcher.class);

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.TELEGRAM;
    }

    @Override
    public void dispatch(TradingNotification notification) {
        log.info("trading.notification.telegram.stub userId={} type={} templateCode={} title='{}' (not actually sent)",
                notification.userId(),
                notification.type(),
                notification.templateCode(),
                notification.title());
    }
}
