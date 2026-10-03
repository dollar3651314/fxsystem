package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * STAGE-3-PENDING-ORDER：挂单触发 MyBatis 记录对象。
 */
public record TradingPendingOrderTriggerRecord(
        Long id,
        String orderNo,
        Long userId,
        /** STAGE-12-GROUP-MARKUP: 挂单创建时用户所属组（t_pending_order_trigger.group_code_at_create）。 */
        String groupCodeAtCreate,
        /** STAGE-12-GROUP-MARKUP: 挂单创建时该组的 bid_extra（t_pending_order_trigger.bid_extra_at_create）。 */
        BigDecimal bidExtraAtCreate,
        /** STAGE-12-GROUP-MARKUP: 挂单创建时该组的 ask_extra（t_pending_order_trigger.ask_extra_at_create）。 */
        BigDecimal askExtraAtCreate,
        String symbol,
        Integer orderType,
        Integer side,
        BigDecimal quantity,
        BigDecimal triggerPrice,
        BigDecimal limitPrice,
        BigDecimal leverage,
        Integer marginMode,
        BigDecimal frozenMargin,
        BigDecimal frozenFee,
        Integer status,
        Long parentPositionId,
        Integer triggerKind,
        String clientOrderId,
        Long triggeredOrderId,
        LocalDateTime triggeredAt,
        LocalDateTime cancelledAt,
        String cancelReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
