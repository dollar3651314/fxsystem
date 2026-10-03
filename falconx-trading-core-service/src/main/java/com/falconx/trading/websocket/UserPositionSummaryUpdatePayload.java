package com.falconx.trading.websocket;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 单用户「总未实现盈亏」实时更新 payload。
 *
 * <p>事件 type = {@code user.position.summary}，channel = {@code positions}（与 position.pnl 同频道，
 * 用户开桌即订阅）；per-user 500ms 节流。
 *
 * <p>UI 用法：客户端 dashboard / market 页面顶部「未实现盈亏」直接显示
 * {@code totalUnrealizedPnl}，无需前端累加 pnlMap；初次进页面 REST 拉一次保底，
 * 之后跟随 WS 节奏实时跳。
 */
public record UserPositionSummaryUpdatePayload(
        BigDecimal totalUnrealizedPnl,
        OffsetDateTime computedAt
) {
}
