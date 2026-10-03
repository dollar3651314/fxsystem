package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 持仓实体。
 *
 * <p>该对象对应 `falconx_trading.t_position` 的内存骨架表达。
 * 当前阶段只保留“一次开仓生成一条持仓”的最小模型，
 * 用于支撑订单、保证金和强平价的最小链路测试。
 */
public record TradingPosition(
        Long positionId,
        Long openingOrderId,
        Long userId,
        String symbol,
        TradingOrderSide side,
        BigDecimal quantity,
        BigDecimal entryPrice,
        /**
         * STAGE-14B Task 9a 引入：开仓时 fx(quoteCurrency→accountCurrency) 快照（t_position.entry_fx_rate）。
         * 仅审计留痕（master §4.2），不参与强平价计算；同币种为 1。
         */
        BigDecimal entryFxRate,
        /**
         * STAGE-14C1 Task 6 引入：开仓时 tier 维持保证金率冻结（t_position.mm_rate_at_open）。
         * 强平价 / MM 计算用本字段，后续 tier 调整不影响存量仓位强平价（同 STAGE-12 markup 冻结）。
         * 老数据回填 0.005（V32）。
         */
        BigDecimal mmRateAtOpen,
        /**
         * STAGE-14C1 Task 6 引入：开仓时命中 tier 档位号冻结（t_position.tier_no_at_open）。
         * 仅审计用。老数据回填 1（V32）。
         */
        Integer tierNoAtOpen,
        BigDecimal leverage,
        BigDecimal margin,
        TradingMarginMode marginMode,
        BigDecimal liquidationPrice,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        BigDecimal closePrice,
        TradingPositionCloseReason closeReason,
        BigDecimal realizedPnl,
        TradingPositionStatus status,
        /**
         * STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.2 引入：开仓时 mapping.taker_fee_rate 快照。
         * 平仓 / 强平 / Swap 用本字段复算费率，不再回查 mapping。
         */
        BigDecimal openFeeRate,
        /**
         * STAGE-12-GROUP-MARKUP 引入：开仓时用户所属组（冻结）。
         * 用于稳定 PnL / 强平 / 平仓口径，不受用户后续换组影响。
         */
        String groupCodeAtOpen,
        /**
         * STAGE-12-GROUP-MARKUP 引入：开仓时该组对该 symbol 的 bid_extra（冻结）。
         */
        BigDecimal bidExtraAtOpen,
        /**
         * STAGE-12-GROUP-MARKUP 引入：开仓时该组对该 symbol 的 ask_extra（冻结）。
         */
        BigDecimal askExtraAtOpen,
        OffsetDateTime openedAt,
        OffsetDateTime closedAt,
        OffsetDateTime updatedAt
) {

    public boolean isOpen() {
        return status == TradingPositionStatus.OPEN;
    }

    public boolean isTerminal() {
        return !isOpen();
    }

    /**
     * 生成更新 TP/SL 后的 OPEN 持仓快照。
     */
    public TradingPosition updateRiskControls(BigDecimal nextTakeProfitPrice,
                                              BigDecimal nextStopLossPrice,
                                              OffsetDateTime occurredAt) {
        return new TradingPosition(
                positionId,
                openingOrderId,
                userId,
                symbol,
                side,
                quantity,
                entryPrice,
                entryFxRate,
                mmRateAtOpen,
                tierNoAtOpen,
                leverage,
                margin,
                marginMode,
                liquidationPrice,
                nextTakeProfitPrice,
                nextStopLossPrice,
                closePrice,
                closeReason,
                realizedPnl,
                status,
                openFeeRate,
                groupCodeAtOpen,
                bidExtraAtOpen,
                askExtraAtOpen,
                openedAt,
                closedAt,
                occurredAt
        );
    }

    /**
     * 生成追加逐仓保证金后的 OPEN 持仓快照。
     */
    public TradingPosition supplementMargin(BigDecimal additionalMargin,
                                            BigDecimal nextLiquidationPrice,
                                            OffsetDateTime occurredAt) {
        return new TradingPosition(
                positionId,
                openingOrderId,
                userId,
                symbol,
                side,
                quantity,
                entryPrice,
                entryFxRate,
                mmRateAtOpen,
                tierNoAtOpen,
                leverage,
                margin.add(additionalMargin),
                marginMode,
                nextLiquidationPrice,
                takeProfitPrice,
                stopLossPrice,
                closePrice,
                closeReason,
                realizedPnl,
                status,
                openFeeRate,
                groupCodeAtOpen,
                bidExtraAtOpen,
                askExtraAtOpen,
                openedAt,
                closedAt,
                occurredAt
        );
    }

    /**
     * 生成退出持仓后的终态快照。
     *
     * @param targetStatus 终态状态
     * @param targetCloseReason 终态原因
     * @param finalPrice 平仓/强平价
     * @param pnl 已实现盈亏
     * @param occurredAt 完成时间
     * @return 终态持仓
     */
    public TradingPosition close(TradingPositionStatus targetStatus,
                                 TradingPositionCloseReason targetCloseReason,
                                 BigDecimal finalPrice,
                                 BigDecimal pnl,
                                 OffsetDateTime occurredAt) {
        return new TradingPosition(
                positionId,
                openingOrderId,
                userId,
                symbol,
                side,
                quantity,
                entryPrice,
                entryFxRate,
                mmRateAtOpen,
                tierNoAtOpen,
                leverage,
                margin,
                marginMode,
                liquidationPrice,
                takeProfitPrice,
                stopLossPrice,
                finalPrice,
                targetCloseReason,
                pnl,
                targetStatus,
                openFeeRate,
                groupCodeAtOpen,
                bidExtraAtOpen,
                askExtraAtOpen,
                openedAt,
                occurredAt,
                occurredAt
        );
    }
}
