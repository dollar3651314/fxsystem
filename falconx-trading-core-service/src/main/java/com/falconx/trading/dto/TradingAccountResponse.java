package com.falconx.trading.dto;

import java.math.BigDecimal;

/**
 * 交易账户响应 DTO。
 *
 * <p>该对象用于对外暴露交易账户的稳定查询结构，
 * 除核心余额视图外，还会返回当前 OPEN 持仓及基于 Redis 最新价动态计算的未实现盈亏。
 */
public record TradingAccountResponse(
        Long accountId,
        Long userId,
        String currency,
        BigDecimal balance,
        BigDecimal frozen,
        BigDecimal marginUsed,
        BigDecimal available,
        // 账户级默认保证金模式（CROSS / ISOLATED）。
        // 一期仅 ISOLATED；显式传 CROSS 下单仍返回 MARGIN_MODE_NOT_SUPPORTED；
        // 下单请求不传 marginMode 时由账户级默认模式 inherit。
        String marginMode,
        /**
         * STAGE-14E1 Task 2（master §7.5 / §3.2）：账户净值（AC 账户币），实时算
         * {@code Equity = balance + frozen + Σ uPnL_i(AC)}（口径复用 {@code AccountEquityCalculator}）。
         * FX 不可用降级时为 {@code null}（不抛）。
         */
        BigDecimal equity,
        /**
         * STAGE-14E1 Task 2：账户级保证金率（百分比，已 ×100；2 位 HALF_UP）。
         * 无持仓 / 除零 / FX 降级时为 {@code null}（calculator 约定）。
         */
        BigDecimal marginLevel,
        /**
         * STAGE-14E1 Task 2：保证金率阶段状态（HEALTHY / MARGIN_CALL / STOP_OUT），
         * 由 {@code MarginLevelMonitor} 基于 marginLevel + t_risk_config 阈值实时判定；
         * marginLevel 为 null（无持仓 / 降级）时为 HEALTHY。
         */
        String marginLevelStatus,
        java.util.List<TradingAccountPositionResponse> openPositions
) {
}
