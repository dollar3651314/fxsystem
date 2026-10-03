package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.PlatformMetricsRecords.BiggestPositionRow;
import com.falconx.trading.repository.mapper.record.PlatformMetricsRecords.BizTypeAggregateRow;
import com.falconx.trading.repository.mapper.record.PlatformMetricsRecords.PositionsStatRow;
import com.falconx.trading.repository.mapper.record.PlatformMetricsRecords.UserPnlBucketRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 平台 dashboard 指标聚合 Mapper。
 *
 * <p>所有方法都是 read-only 聚合查询，跨用户全局视角；权限校验由 console-service
 * controller {@code @RequiresPermission("platform-metrics:view")} 把守。
 */
@Mapper
public interface PlatformMetricsMapper {

    /**
     * §01 持仓快照：一行返回 OPEN/CLOSED/LIQUIDATED 计数 + 多空拆分 + 累计已实现盈亏。
     */
    PositionsStatRow selectPositionsStat();

    /**
     * §02 / §04 ledger 按 biz_type 分组求和。fromTs 可空（不过滤时间 = lifetime）。
     */
    List<BizTypeAggregateRow> selectLedgerAggregate(@Param("bizTypes") List<Integer> bizTypes,
                                                    @Param("fromTs") LocalDateTime fromTs);

    /**
     * §03 用户盈亏分布：按 (用户 → 已实现盈亏总和) 分桶。
     * 仅统计 status IN (2=CLOSED, 3=LIQUIDATED) 且 realized_pnl IS NOT NULL 的持仓。
     */
    List<UserPnlBucketRow> selectUserPnlDistribution();

    /**
     * §03 最大盈或最大亏单笔持仓。direction = "WIN" → ORDER BY realized_pnl DESC，
     * "LOSE" → ASC。返回 null 表示尚无已平仓数据。
     */
    BiggestPositionRow selectBiggestPosition(@Param("direction") String direction);

    /**
     * §05 全部已成交订单数。
     */
    long countFilledOrders();

    /**
     * §05 今日已成交订单数（按服务器本地日历日）。
     */
    long countFilledOrdersToday();
}
