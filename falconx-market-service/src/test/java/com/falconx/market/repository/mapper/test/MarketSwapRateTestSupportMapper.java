package com.falconx.market.repository.mapper.test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * `t_swap_rate` 测试专用支持 Mapper。
 *
 * <p>该 Mapper 只存在于测试源码，
 * 用来驱动 owner 费率历史变化并验证 Redis 共享快照刷新结果。
 */
@Mapper
public interface MarketSwapRateTestSupportMapper {

    /**
     * 清空全部隔夜利息费率。
     */
    void deleteAllSwapRates();

    /**
     * 以 `symbol + effective_from` 为键写入或更新费率规则。
     *
     * @param symbol 品种
     * @param longRate 多头费率
     * @param shortRate 空头费率
     * @param rolloverTime 结算时间
     * @param effectiveFrom 生效日期
     * @return 影响行数
     */
    int upsertSwapRate(@Param("symbol") String symbol,
                       @Param("longRate") BigDecimal longRate,
                       @Param("shortRate") BigDecimal shortRate,
                       @Param("rolloverTime") LocalTime rolloverTime,
                       @Param("effectiveFrom") LocalDate effectiveFrom);
}
