package com.falconx.console.api;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * STAGE-2-SYMBOL: 行情品种列表响应（对外 console API 透传 market internal record）。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R9：Item 字段集裁剪（删 6 交易字段）+ 新增 lastTickAt
 *（由 console 合并 ClickHouse 聚合结果；从未收到 tick 时为 null）。
 */
public record AdminSymbolListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String lpCode,
            String symbol,
            Integer category,
            String marketCode,
            String baseCurrency,
            String quoteCurrency,
            Integer pricePrecision,
            Integer qtyPrecision,
            Integer status,
            OffsetDateTime lastTickAt,
            LocalDateTime createdAt
    ) {
    }
}
