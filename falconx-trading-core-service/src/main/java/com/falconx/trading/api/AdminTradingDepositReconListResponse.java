package com.falconx.trading.api;

import java.util.List;

public record AdminTradingDepositReconListResponse(
        int page,
        int pageSize,
        int returned,
        List<AdminTradingDepositReconItem> items
) {
}
