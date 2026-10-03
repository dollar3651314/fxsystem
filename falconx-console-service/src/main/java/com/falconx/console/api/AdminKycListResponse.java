package com.falconx.console.api;

import java.util.List;

public record AdminKycListResponse(
        int page,
        int pageSize,
        long total,
        List<AdminKycItem> items
) {
}
