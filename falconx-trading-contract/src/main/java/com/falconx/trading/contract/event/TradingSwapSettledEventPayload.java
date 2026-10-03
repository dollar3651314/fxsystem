package com.falconx.trading.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * `falconx.trading.swap.settled` 事件的跨服务 payload 契约。
 *
 * <p>该对象由 trading-core-service 在单笔 `Swap` 账本落账完成后发布，
 * 当前先冻结字段结构，供后续运营、对账或通知型下游消费。
 *
 * @param ledgerId 账本主键
 * @param userId 用户 ID
 * @param positionId 持仓 ID
 * @param symbol 品种
 * @param side 持仓方向
 * @param settlementType `SWAP_CHARGE` 或 `SWAP_INCOME`
 * @param amount 结算金额，始终为正数
 * @param rate 触发本次结算的配置费率
 * @param effectivePrice 结算使用的有效价格
 * @param rolloverAt 结算所属 rollover 时点
 * @param quoteTs 使用的报价时间戳
 * @param settledAt 账本落账时间
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradingSwapSettledEventPayload(
        Long ledgerId,
        Long userId,
        Long positionId,
        String symbol,
        String side,
        String settlementType,
        BigDecimal amount,
        BigDecimal rate,
        BigDecimal effectivePrice,
        OffsetDateTime rolloverAt,
        OffsetDateTime quoteTs,
        OffsetDateTime settledAt
) {
}
