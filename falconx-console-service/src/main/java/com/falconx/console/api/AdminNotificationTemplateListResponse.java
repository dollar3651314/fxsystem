package com.falconx.console.api;

import java.util.List;

public record AdminNotificationTemplateListResponse(
        int page,
        int pageSize,
        long total,
        List<AdminNotificationTemplateItem> items
) {
}
