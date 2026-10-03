package com.falconx.wallet.entity;

import java.time.OffsetDateTime;

/**
 * STAGE-5-WALLET-PROVISION Phase 2：地址预分配死信队列条目。
 */
public record WalletAddressProvisionDlqEntry(
        Long id,
        String eventId,
        Long userId,
        String uid,
        String email,
        int attemptCount,
        WalletAddressProvisionDlqStatus status,
        String lastErrorCode,
        String lastErrorMessage,
        OffsetDateTime lastAttemptAt,
        OffsetDateTime resolvedAt,
        OffsetDateTime createdAt
) {
}
