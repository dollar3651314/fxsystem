package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 管理端 Swap 资金费率聚合摘要。
 *
 * <p>结构与 trading-core {@code TradingSwapSummaryResponse} 一一对齐，
 * 通过 InternalRpcClient 走 {@code /internal/v1/trading/console/positions/{id}/swap-summary} 拉取。
 */
public record AdminTradingSwapSummaryResponse(
        BigDecimal totalCharge,
        BigDecimal totalIncome,
        BigDecimal net,
        int chargeCount,
        int incomeCount,
        OffsetDateTime firstAt,
        OffsetDateTime lastAt
) {
}
