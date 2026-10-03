package com.falconx.console.api;

import java.time.OffsetDateTime;

/**
 * STAGE-5-WALLET-PROVISION Phase 2：地址预分配 DLQ 列表项。
 */
public record AdminWalletProvisionDlqItem(
        String id,
        String eventId,
        String userId,
        String uid,
        String email,
        int attemptCount,
        String status,
        String lastErrorCode,
        String lastErrorMessage,
        OffsetDateTime lastAttemptAt,
        OffsetDateTime resolvedAt,
        OffsetDateTime createdAt
) {
}
