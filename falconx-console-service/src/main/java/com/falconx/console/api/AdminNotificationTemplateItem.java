package com.falconx.console.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * STAGE-8-NOTIFICATION Phase 3：通知模板（管理端列表 / 详情）。
 *
 * <p>对齐 trading-core `/internal/v1/trading/console/notification-templates` 响应。
 */
public record AdminNotificationTemplateItem(
        String code,
        String titleTemplate,
        String bodyTemplate,
        String level,
        List<String> channels,
        String description,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
