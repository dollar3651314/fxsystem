package com.falconx.trading.entity;

/**
 * STAGE-8-NOTIFICATION Phase 1：通知 channel 枚举。
 *
 * <p>V2 一期仅 IN_APP 真实写入 + WebSocket 推送；EMAIL / TELEGRAM 为 stub，仅 log。
 * <p>模板 {@code t_notification_template.channels} 字段 CSV 存储：例如 "IN_APP,EMAIL"。
 */
public enum NotificationChannel {
    IN_APP,
    EMAIL,
    TELEGRAM;

    public static NotificationChannel fromName(String name) {
        if (name == null) return null;
        try {
            return NotificationChannel.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
