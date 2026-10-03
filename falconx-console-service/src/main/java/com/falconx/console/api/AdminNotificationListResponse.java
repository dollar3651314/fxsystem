package com.falconx.console.api;

import java.util.List;

public record AdminNotificationListResponse(
        int page,
        int pageSize,
        long total,
        List<AdminNotificationItem> items
) {
}
