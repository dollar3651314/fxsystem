package com.falconx.trading.api;

import java.util.List;

/**
 * BBOOK-RISK-CONTROL-01：用户级风控阈值分页响应。
 */
public record AdminUserRiskThresholdListResponse(
        List<AdminUserRiskThresholdItem> items,
        long total,
        int page,
        int size
) {
}
