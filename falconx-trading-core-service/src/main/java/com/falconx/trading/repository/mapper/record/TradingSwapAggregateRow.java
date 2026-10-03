package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Swap 聚合查询的一行结果（每行对应一个 biz_type）。
 *
 * <p>SQL 形如 {@code SELECT biz_type, SUM(amount), COUNT(*), MIN/MAX(created_at) ... GROUP BY biz_type}
 * 返回 0 / 1 / 2 行（对应 SWAP_CHARGE=6、SWAP_INCOME=7 命中情况）。
 * Service 层按 {@link #bizType()} 拍平成固定形状 DTO。
 */
public record TradingSwapAggregateRow(
        // STAGE-12: MyBatis 3 + Java 21 record 不自动 box/unbox 构造器签名，
        // result set 中 INTEGER 列必须映射 Integer 而非 primitive int，
        // 否则抛 NoSuchMethodException: <init>(Integer, BigDecimal, Integer, ...).
        Integer bizType,
        BigDecimal totalAmount,
        Integer entryCount,
        LocalDateTime firstAt,
        LocalDateTime lastAt
) {
}
