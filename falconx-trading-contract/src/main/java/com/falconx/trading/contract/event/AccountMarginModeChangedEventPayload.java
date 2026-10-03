package com.falconx.trading.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * `falconx.trading.account.mode.changed` 事件的跨服务 payload 契约（master §7.2）。
 *
 * <p>该对象由 trading-core-service 在用户成功切换账户 margin mode（ISOLATED/CROSS）后
 * 经 Outbox 发布，由 console-service 消费用于审计、由 identity-service 消费用于触发
 * ACCOUNT_MODE_CHANGED 通知。字段结构与 Kafka 事件规范保持一致。
 *
 * @param userId 切换 margin mode 的用户 ID
 * @param oldMode 切换前 margin mode（ISOLATED/CROSS）
 * @param newMode 切换后 margin mode（ISOLATED/CROSS）
 * @param changedAtMillis 切换完成时间（epoch millis）
 * @param coolingUntilMillis 冷静期结束时间（epoch millis），null 表示无冷静期
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountMarginModeChangedEventPayload(
        Long userId,
        String oldMode,
        String newMode,
        long changedAtMillis,
        Long coolingUntilMillis
) {
}
