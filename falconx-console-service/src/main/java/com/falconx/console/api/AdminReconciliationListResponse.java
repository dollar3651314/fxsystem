package com.falconx.console.api;

import java.util.List;

public record AdminReconciliationListResponse(
        int page,
        int pageSize,
        long total,
        List<AdminReconciliationItem> items
) {
}
