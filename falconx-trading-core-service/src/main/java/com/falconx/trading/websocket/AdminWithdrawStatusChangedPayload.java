package com.falconx.trading.websocket;

import java.time.OffsetDateTime;

/**
 * STAGE-7-WITHDRAW Phase 4 §4 commit C：管理端出金状态变化推送 payload。
 *
 * <p>当 trading-core 消费 wallet 端 broadcast/confirmed/failed 事件并完成状态切换后，
 * 通过 {@code admin.withdraws} 频道实时推送给管理后台审核页面。
 *
 * <p>withdrawId / userId 用 String（雪花 ID 防 JS 精度丢失，FX-071）。
 */
public record AdminWithdrawStatusChangedPayload(
        String withdrawId,
        String userId,
        String status,
        String txHash,
        Integer confirmations,
        String failureReason,
        OffsetDateTime occurredAt
) {}
