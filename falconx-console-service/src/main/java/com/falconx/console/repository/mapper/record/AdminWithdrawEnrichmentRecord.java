package com.falconx.console.repository.mapper.record;

import java.math.BigDecimal;

/**
 * STAGE-7-WITHDRAW Phase 4 §4 commit C：管理端出金审核扩展字段 enrichment 跨 schema 查询结果。
 *
 * <p>来源：单 SQL JOIN
 * {@code falconx_identity.t_user u
 *        LEFT JOIN (SELECT user_id, SUM(amount) FROM falconx_trading.t_withdraw_order
 *                   WHERE created_at IN [today UTC] AND status IN (0..5) GROUP BY user_id) d
 *        ON d.user_id = u.id}
 *
 * <p>dailyAccumulatedUsd 含义：当日 UTC 已创建且仍计入单日上限的出金累计（COOLING/PENDING/APPROVED/
 * APPROVED_DELAYED/PROCESSING/COMPLETED；与 trading-core {@code sumActiveAmountForUserOnDay} 一致）。
 * 用户当日无任何出金时为 0（COALESCE）。
 */
public record AdminWithdrawEnrichmentRecord(
        Long userId,
        String email,
        Integer kycLevel,
        BigDecimal dailyAccumulatedUsd
) {
}
