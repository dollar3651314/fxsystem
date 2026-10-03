package com.falconx.trading.entity;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * STAGE-8-NOTIFICATION Phase 0 R2：通知模板。
 *
 * <p>schema：{@code t_notification_template}（V22 migration）。
 * <p>插值规约：{@code ${variable}} 占位符 + Map<String,String> params + 简单 String.replace
 * （由 {@code NotificationTemplateService.render} 实现）。
 *
 * @param code           模板代码（PK，正则 ^[A-Z][A-Z0-9_]{2,63}$）
 * @param titleTemplate  标题模板（含 ${var} 占位符）
 * @param bodyTemplate   正文模板
 * @param level          INFO / WARN / CRITICAL
 * @param channels       投递 channel 列表（解析自 DB CSV 列）
 * @param description    运营注释（可选）
 * @param enabled        是否启用（false = producer 调 send 时视为不存在）
 * @param createdAt      首次落库时间
 * @param updatedAt      最近修改时间
 */
public record TradingNotificationTemplate(
        String code,
        String titleTemplate,
        String bodyTemplate,
        TradingNotificationLevel level,
        List<NotificationChannel> channels,
        String description,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
