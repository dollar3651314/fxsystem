package com.falconx.console.api;

import java.util.List;

public record AdminPriceAlertListResponse(
        int page,
        int pageSize,
        long total,
        List<AdminPriceAlertItem> items
) {
}
