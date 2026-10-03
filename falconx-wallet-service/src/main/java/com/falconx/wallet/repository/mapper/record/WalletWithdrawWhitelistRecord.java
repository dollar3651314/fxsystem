package com.falconx.wallet.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * STAGE-7-WITHDRAW：出金白名单 MyBatis 记录对象。
 */
public record WalletWithdrawWhitelistRecord(
        Long id,
        Long userId,
        String network,
        String address,
        String label,
        Integer status,
        LocalDateTime activatedAt,
        LocalDateTime removedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
