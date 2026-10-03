package com.falconx.market.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * market-service 内部 K 线快照对象。
 *
 * <p>该对象用于表达当前聚合出的 K 线状态。
 * Stage 2A 只冻结对象结构，后续在 K 线计算服务和 ClickHouse 写入逻辑中复用。
 */
public record KlineSnapshot(
        String symbol,
        String interval,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        OffsetDateTime openTime,
        OffsetDateTime closeTime,
        boolean isFinal
) {
}
