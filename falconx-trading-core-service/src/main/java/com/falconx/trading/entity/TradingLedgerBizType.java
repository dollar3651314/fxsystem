package com.falconx.trading.entity;

/**
 * 账本业务类型枚举。
 *
 * <p>该枚举用于区分账户余额、冻结金额和保证金占用变化的来源，
 * 便于后续对账、审计和流水回放。
 */
public enum TradingLedgerBizType {
    DEPOSIT_CREDIT,
    DEPOSIT_REVERSAL,
    ORDER_MARGIN_RESERVED,
    ORDER_FEE_CHARGED,
    ORDER_MARGIN_CONFIRMED,
    ISOLATED_MARGIN_SUPPLEMENT,
    SWAP_CHARGE,
    SWAP_INCOME,
    REALIZED_PNL,
    LIQUIDATION_PNL,
    /** STAGE-2-CUSTOMER：管理员手动调整客户余额（高风险审计）。 */
    ADMIN_BALANCE_ADJUST,
    /** STAGE-3-PENDING-ORDER：挂单冻结释放（撤单 / 触发）。 */
    PENDING_ORDER_RELEASED,
    /** STAGE-7-WITHDRAW：提交出金时冻结余额（frozen +=）。 */
    WITHDRAW_FREEZE,
    /** STAGE-7-WITHDRAW：用户冷静期取消，退还冻结余额（frozen -=）。 */
    WITHDRAW_REFUND_CANCEL,
    /** STAGE-7-WITHDRAW：admin 拒绝，退还冻结余额（frozen -=）。 */
    WITHDRAW_REFUND_REJECT,
    /** STAGE-7-WITHDRAW：admin 紧急取消（APPROVED_DELAYED 阶段），退还冻结余额（frozen -=）。 */
    WITHDRAW_REFUND_EMERGENCY,
    /** STAGE-7-WITHDRAW：链上确认完成，落账扣减（balance -= + frozen -=）。 */
    WITHDRAW_SETTLE,
    /** STAGE-7-WITHDRAW：链上失败回滚，退还冻结余额（frozen -=）。 */
    WITHDRAW_REFUND_CHAIN_FAILED
}
