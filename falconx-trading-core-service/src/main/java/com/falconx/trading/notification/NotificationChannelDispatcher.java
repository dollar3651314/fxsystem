package com.falconx.trading.notification;

import com.falconx.trading.entity.NotificationChannel;
import com.falconx.trading.entity.TradingNotification;

/**
 * STAGE-8-NOTIFICATION Phase 1：通知 channel 投递 SPI。
 *
 * <p>V2 一期内置实现：
 * <ul>
 *   <li>{@link InAppNotificationDispatcher}：写 t_notification + WebSocket 推送（IN_APP）</li>
 *   <li>{@link StubEmailNotificationDispatcher}：仅 log，不真实发送（EMAIL）</li>
 *   <li>{@link StubTelegramNotificationDispatcher}：仅 log，不真实发送（TELEGRAM）</li>
 * </ul>
 *
 * <p>调用方：{@code TradingNotificationApplicationService.send} 遍历模板 channels，
 * 对每个 channel 找到 {@code supports(channel)=true} 的 dispatcher 调 {@link #dispatch}。
 */
public interface NotificationChannelDispatcher {

    /** 当前 dispatcher 是否支持目标 channel。 */
    boolean supports(NotificationChannel channel);

    /**
     * 投递通知。
     *
     * <p>投递失败应当 <strong>不抛异常</strong>（仅 log），由调用方继续遍历其他 channel。
     * 例外：IN_APP dispatcher 写库失败属于业务关键路径，调用方应当感知。
     */
    void dispatch(TradingNotification notification);
}
