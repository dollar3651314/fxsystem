package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-3-PENDING-ORDER：挂单触发实体。
 *
 * <p>统一承载 LIMIT/STOP/STOP_LIMIT/SL_TP；
 * SL_TP（{@code parent_position_id} 必有值）触发后平 parent 持仓，
 * 其他类型触发后开仓。
 */
public record TradingPendingOrderTrigger(
        Long id,
        String orderNo,
        Long userId,
        /**
         * STAGE-12-GROUP-MARKUP：挂单创建时用户所属组（冻结）。
         */
        String groupCodeAtCreate,
        /**
         * STAGE-12-GROUP-MARKUP：挂单创建时该组对该 symbol 的 bid_extra（冻结）。
         */
        BigDecimal bidExtraAtCreate,
        /**
         * STAGE-12-GROUP-MARKUP：挂单创建时该组对该 symbol 的 ask_extra（冻结）。
         */
        BigDecimal askExtraAtCreate,
        String symbol,
        TradingPendingOrderType orderType,
        TradingOrderSide side,
        BigDecimal quantity,
        BigDecimal triggerPrice,
        BigDecimal limitPrice,
        BigDecimal leverage,
        TradingMarginMode marginMode,
        BigDecimal frozenMargin,
        BigDecimal frozenFee,
        TradingPendingOrderStatus status,
        Long parentPositionId,
        TradingPendingOrderTriggerKind triggerKind,
        String clientOrderId,
        Long triggeredOrderId,
        OffsetDateTime triggeredAt,
        OffsetDateTime cancelledAt,
        String cancelReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
