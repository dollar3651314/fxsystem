package com.falconx.trading.dto;

import java.util.List;

public record PriceAlertListResponse(
        int page,
        int pageSize,
        long total,
        List<PriceAlertItemResponse> items
) {
}
