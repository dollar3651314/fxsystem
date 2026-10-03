package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record AdminTradingExposureListResponse(List<Item> items) {

    public record Item(
            String symbol,
            /** STAGE-14E2 Task 2：计价币代码（QC，trading internal RPC 透传，过渡期可能为 null），供前端按报价币聚合 netExposureUsd。 */
            String quoteCurrency,
            BigDecimal totalLongQty,
            BigDecimal totalShortQty,
            BigDecimal netExposure,
            BigDecimal netExposureUsd,
            OffsetDateTime updatedAt
    ) {
    }
}
