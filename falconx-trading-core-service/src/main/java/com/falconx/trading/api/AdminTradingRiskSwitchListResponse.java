package com.falconx.trading.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 管理端风控开关列表响应（trading-core internal RPC）。
 */
public record AdminTradingRiskSwitchListResponse(List<Item> items) {

    public record Item(
            String key,
            boolean enabled,
            String reason,
            String updatedBy,
            OffsetDateTime updatedAt
    ) {
    }
}
