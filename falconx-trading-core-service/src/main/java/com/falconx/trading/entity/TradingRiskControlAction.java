package com.falconx.trading.entity;

import java.time.OffsetDateTime;

/**
 * BBook 风控执行动作实体，对应 {@code t_risk_control_action}。
 *
 * @param actionId      主键
 * @param symbol        品种；{@code null} 表示全局动作
 * @param actionType    动作类型
 * @param active        是否激活
 * @param triggerSource 触发来源（AUTO / AUTO_CONCENTRATION / MANUAL）
 * @param triggerReason 触发原因说明
 * @param hedgeLogId    关联的 t_hedge_log.id（自动触发时填写）
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 */
public record TradingRiskControlAction(
        Long actionId,
        String symbol,
        TradingRiskControlActionType actionType,
        boolean active,
        String triggerSource,
        String triggerReason,
        Long hedgeLogId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
