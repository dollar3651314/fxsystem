package com.falconx.market.repository.mapper.record;

import java.time.OffsetDateTime;

/**
 * ClickHouse {@code falconx_market_analytics.quote_tick} 聚合最新 tick 时间记录。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B：替代 t_symbol.last_tick_at 字段，
 * 由 console 主表 Tab 查询时调用 market internal RPC 拿当前一页 symbols 的最新 tick 时间。
 */
public record SymbolLastTickRecord(
        String symbol,
        OffsetDateTime lastTickAt
) {
}
