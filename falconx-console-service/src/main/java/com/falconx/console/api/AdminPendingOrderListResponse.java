package com.falconx.console.api;

import java.util.List;

public record AdminPendingOrderListResponse(
        int page,
        int pageSize,
        long total,
        List<AdminPendingOrderItem> items
) {
}
