package com.falconx.console.api;

import java.util.List;

public record AdminWalletProvisionDlqListResponse(
        int page,
        int pageSize,
        long total,
        List<AdminWalletProvisionDlqItem> items
) {
}
