package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易订单 MyBatis 记录对象。
 *
 * <p>该记录对象对应 `t_order` 的数据库结构。
 *
 * @param id 主键 ID
 * @param orderNo 订单号
 * @param userId 用户 ID
 * @param symbol 品种
 * @param sideCode 方向码
 * @param orderTypeCode 订单类型码
 * @param quantity 下单数量
 * @param requestedPrice 请求时价格
 * @param filledPrice 成交价
 * @param leverage 杠杆
 * @param margin 保证金
 * @param fee 手续费
 * @param clientOrderId 客户端幂等键
 * @param statusCode 订单状态码
 * @param rejectReason 拒单原因
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record TradingOrderRecord(
        Long id,
        String orderNo,
        Long userId,
        String symbol,
        Integer sideCode,
        Integer orderTypeCode,
        BigDecimal quantity,
        BigDecimal requestedPrice,
        BigDecimal filledPrice,
        BigDecimal leverage,
        BigDecimal margin,
        BigDecimal fee,
        /** STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.2: 开仓时 mapping.taker_fee_rate 快照（t_order.open_fee_rate）。 */
        BigDecimal openFeeRate,
        String clientOrderId,
        Integer statusCode,
        String rejectReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
