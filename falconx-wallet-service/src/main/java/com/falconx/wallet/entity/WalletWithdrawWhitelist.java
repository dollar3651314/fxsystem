package com.falconx.wallet.entity;

import java.time.OffsetDateTime;

/**
 * STAGE-7-WITHDRAW：出金白名单实体（wallet-service owner）。
 *
 * <p>24h 冷静期：添加后 status=PENDING；24h 后由调度器或惰性检查切到 ACTIVE。
 * 删除后 status=REMOVED，保留历史；同 user_id + network + address 允许在 REMOVED 后再次添加。
 */
public record WalletWithdrawWhitelist(
        Long id,
        Long userId,
        String network,
        String address,
        String label,
        WalletWithdrawWhitelistStatus status,
        OffsetDateTime activatedAt,
        OffsetDateTime removedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
