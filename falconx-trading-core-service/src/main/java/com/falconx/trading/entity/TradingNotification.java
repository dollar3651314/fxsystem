package com.falconx.trading.entity;

import java.time.OffsetDateTime;

/**
 * STAGE-8-NOTIFICATION：站内信。
 *
 * <p>触发场景：价格告警 / 持仓 LIQUIDATED / TP / SL / KYC / 出金 / 入金 / 风控等。
 * <ul>
 *   <li>Phase 0 R2 反转 V19 sql 决策：引入模板表 t_notification_template，新路径
 *       {@code TradingNotificationApplicationService.send(templateCode, userId, params)}
 *       由 NotificationTemplateService 渲染 title/body 并填充 {@code templateCode} 字段。</li>
 *   <li>旧路径 {@code create(userId, type, level, title, body, ...)} 保留兼容，
 *       此时 {@code templateCode} 为 null（producer 直接拼接 title/body）。</li>
 * </ul>
 */
public record TradingNotification(
        Long id,
        Long userId,
        String type,
        String templateCode,
        TradingNotificationLevel level,
        String title,
        String body,
        String relatedKey,
        Long relatedId,
        String payloadJson,
        TradingNotificationStatus status,
        OffsetDateTime readAt,
        OffsetDateTime createdAt
) {
}
