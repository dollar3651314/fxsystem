package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;

/**
 * 平台 dashboard 指标聚合的 mapper 行类型集合。
 *
 * <p>所有 record 仅用于 mapper 与 service 之间传递；service 拍平到对外 Response DTO。
 */
public final class PlatformMetricsRecords {

    private PlatformMetricsRecords() {}

    /** §01 持仓快照：OPEN/CLOSED/LIQUIDATED 计数 + 多空拆分 + 已实现盈亏总和。
     *  字段都用 boxed Long / Integer，匹配 MyBatis 默认 column type mapping。 */
    public record PositionsStatRow(
            Long openCount,
            Long longCount,
            Long shortCount,
            Long closedCount,
            Long liquidatedCount,
            BigDecimal totalRealizedPnl  // 用户口径累计已实现盈亏；null safe
    ) {
    }

    /** §02 / §04 通用：按 biz_type 分组的 SUM/COUNT 聚合。 */
    public record BizTypeAggregateRow(
            Integer bizType,
            BigDecimal totalAmount,
            Long entryCount
    ) {
    }

    /** §03 用户盈亏分布：WIN / LOSE / EVEN 三桶各自用户数。 */
    public record UserPnlBucketRow(
            String bucket,  // "WIN" / "LOSE" / "EVEN"
            Long userCount
    ) {
    }

    /** §03 最大盈/亏单笔持仓（用户 + 品种 + 金额）。 */
    public record BiggestPositionRow(
            Long userId,
            String symbol,
            BigDecimal realizedPnl
    ) {
    }
}
