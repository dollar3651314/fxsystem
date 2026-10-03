package com.falconx.wallet.repository.mapper.record;

import java.time.LocalDateTime;

public record WalletAddressProvisionDlqRecord(
        Long id,
        String eventId,
        Long userId,
        String uid,
        String email,
        Integer attemptCount,
        Integer status,
        String lastErrorCode,
        String lastErrorMessage,
        LocalDateTime lastAttemptAt,
        LocalDateTime resolvedAt,
        LocalDateTime createdAt
) {
}
