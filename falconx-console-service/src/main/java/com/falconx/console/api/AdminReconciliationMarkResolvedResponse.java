package com.falconx.console.api;

import java.time.OffsetDateTime;

public record AdminReconciliationMarkResolvedResponse(
        String walletTxId,
        OffsetDateTime resolvedAt,
        String resolvedByAdminId
) {
}
