package com.falconx.trading.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * STAGE-14D1 Task 3：margin mode 查询结果（GET /api/v1/me/margin-mode）。
 *
 * <p>对应 master §7.4：返回当前 mode + cooling_until + can_switch + blockers。
 *
 * @param currentMode 当前账户 margin mode（ISOLATED / CROSS）
 * @param modeChangedAt 上次切换时间，可为 null（从未切换过）
 * @param coolingUntil 冷静期截止时间，null 表示不在冷静期
 * @param canSwitch 当前是否可发起切换（任一阻断项存在即 false）
 * @param blockers 当前阻断原因列表（OPEN_POSITIONS / ACTIVE_PENDING / COOLING）
 * @param crossModeEnabled 平台是否开放 CROSS（cross_mode.enabled）；false 时切到 CROSS 会被 30088 拒，
 *                         前端据此 disable CROSS 选项并提示「全仓暂未开放」，避免下单默认模式选 CROSS 后被拒
 */
public record MarginModeQueryResult(
        String currentMode,
        OffsetDateTime modeChangedAt,
        OffsetDateTime coolingUntil,
        boolean canSwitch,
        List<String> blockers,
        boolean crossModeEnabled
) {
}
