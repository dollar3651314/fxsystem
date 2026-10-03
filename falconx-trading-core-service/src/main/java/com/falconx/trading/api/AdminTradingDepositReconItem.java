package com.falconx.trading.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * STAGE-11-OBS-RECON §11.4：trading-core admin 对账视图。
 *
 * <p>由 {@code /internal/v1/trading/console/deposits} 返回给 console-service，
 * 用于和 wallet 端 confirmed 列表做 in-memory diff。
 *
 * <p>雪花 ID 使用 {@link ToStringSerializer} 序列化为字符串，由 console-service
 * 转发给前端时不受 JS 精度限制（与 STAGE-9 / STAGE-7 admin DTO 一致）。
 */
public record AdminTradingDepositReconItem(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        @JsonSerialize(using = ToStringSerializer.class) Long walletTxId,
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String chain,
        String token,
        String txHash,
        BigDecimal amount,
        String status,
        OffsetDateTime creditedAt,
        OffsetDateTime reversedAt
) {
}
