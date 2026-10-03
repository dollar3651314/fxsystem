package com.falconx.trading.dto;

import java.time.OffsetDateTime;

/**
 * STAGE-14D1 Task 3：margin mode 切换结果（POST /api/v1/me/margin-mode）。
 *
 * <p>切换成功后返回，供 Task 4 发 Kafka {@code falconx.trading.account.mode.changed}
 * 与 ACCOUNT_MODE_CHANGED 通知使用（本 task 不发，留 hook 给 Task 4）。
 *
 * @param oldMode 切换前 margin mode
 * @param newMode 切换后 margin mode
 * @param modeChangedAt 本次切换时间
 * @param coolingUntil 本次切换后的冷静期截止时间
 */
public record MarginModeSwitchResult(
        String oldMode,
        String newMode,
        OffsetDateTime modeChangedAt,
        OffsetDateTime coolingUntil
) {
}
