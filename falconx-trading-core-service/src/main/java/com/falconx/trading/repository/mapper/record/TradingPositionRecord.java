package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易持仓 MyBatis 记录对象。
 *
 * <p>该记录对象对应 `t_position` 的数据库结构。
 *
 * @param id 主键 ID
 * @param openingOrderId 开仓订单 ID
 * @param userId 用户 ID
 * @param symbol 品种
 * @param sideCode 方向码
 * @param quantity 持仓数量
 * @param entryPrice 开仓均价
 * @param entryFxRate 开仓时 FX rate (quote→account)，仅审计用（t_position.entry_fx_rate）
 * @param mmRateAtOpen 开仓时 tier mmRate 冻结（t_position.mm_rate_at_open），强平价/MM 计算用
 * @param tierNoAtOpen 开仓时命中 tier 档位号冻结（t_position.tier_no_at_open），审计用
 * @param leverage 杠杆
 * @param margin 占用保证金
 * @param marginModeCode 保证金模式码
 * @param liquidationPrice 强平价
 * @param takeProfitPrice 止盈触发价
 * @param stopLossPrice 止损触发价
 * @param closePrice 平仓价
 * @param closeReasonCode 平仓原因码
 * @param realizedPnl 已实现盈亏
 * @param statusCode 持仓状态码
 * @param openedAt 开仓时间
 * @param closedAt 平仓时间
 * @param updatedAt 更新时间
 */
public record TradingPositionRecord(
        Long id,
        Long openingOrderId,
        Long userId,
        String symbol,
        Integer sideCode,
        BigDecimal quantity,
        BigDecimal entryPrice,
        /** STAGE-14B Task 9a: 开仓时 FX rate (quote→account)，仅审计用（t_position.entry_fx_rate）。 */
        BigDecimal entryFxRate,
        /** STAGE-14C1 Task 6: 开仓时 tier mmRate 冻结（t_position.mm_rate_at_open），强平价/MM 计算用。 */
        BigDecimal mmRateAtOpen,
        /** STAGE-14C1 Task 6: 开仓时命中 tier 档位号冻结（t_position.tier_no_at_open），审计用。 */
        Integer tierNoAtOpen,
        BigDecimal leverage,
        BigDecimal margin,
        Integer marginModeCode,
        BigDecimal liquidationPrice,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        BigDecimal closePrice,
        Integer closeReasonCode,
        BigDecimal realizedPnl,
        Integer statusCode,
        /** STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.2: 开仓时 mapping.taker_fee_rate 快照（t_position.open_fee_rate）。 */
        BigDecimal openFeeRate,
        /** STAGE-12-GROUP-MARKUP: 开仓时用户所属组（t_position.group_code_at_open）。 */
        String groupCodeAtOpen,
        /** STAGE-12-GROUP-MARKUP: 开仓时该组的 bid_extra（t_position.bid_extra_at_open）。 */
        BigDecimal bidExtraAtOpen,
        /** STAGE-12-GROUP-MARKUP: 开仓时该组的 ask_extra（t_position.ask_extra_at_open）。 */
        BigDecimal askExtraAtOpen,
        LocalDateTime openedAt,
        LocalDateTime closedAt,
        LocalDateTime updatedAt
) {
}
