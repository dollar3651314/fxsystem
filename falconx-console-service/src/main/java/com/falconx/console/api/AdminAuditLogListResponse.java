package com.falconx.console.api;

import java.util.List;

public record AdminAuditLogListResponse(
        int page,
        int pageSize,
        long total,
        List<AdminAuditLogItem> items
) {
}
