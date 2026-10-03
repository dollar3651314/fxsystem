package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * 交易账户实体。
 *
 * <p>该对象对应 `falconx_trading.t_account` 的领域表达，
 * 固定遵守数据库设计中的账户语义：
 *
 * <ul>
 *   <li>`balance`：账户现金余额</li>
 *   <li>`frozen`：已预留未确认金额</li>
 *   <li>`marginUsed`：已确认占用的保证金</li>
 *   <li>`available = balance - frozen - marginUsed`</li>
 *   <li>`marginMode`：账户级默认保证金模式偏好（CROSS / ISOLATED）</li>
 *   <li>`modeChangedAt`：上次 margin mode 切换时间（审计 + 冷静期起算）</li>
 *   <li>`modeCoolingUntil`：margin mode 冷静期截止时间，null 表示当前不在冷静期</li>
 * </ul>
 *
 * <p>所有状态变更都通过返回新对象实现，便于在真实持久化场景下继续保持不可变更新语义。
 */
public record TradingAccount(
        Long accountId,
        Long userId,
        String currency,
        BigDecimal balance,
        BigDecimal frozen,
        BigDecimal marginUsed,
        TradingMarginMode marginMode,
        OffsetDateTime modeChangedAt,
        OffsetDateTime modeCoolingUntil,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /**
     * @return 当前可用于开仓、扣费或提现的可用余额
     */
    public BigDecimal available() {
        return balance.subtract(frozen).subtract(marginUsed).setScale(8, RoundingMode.DOWN);
    }

    /**
     * 生成入金后的账户快照。
     *
     * @param amount 入账金额
     * @param occurredAt 本次状态变化时间
     * @return 余额增加后的新账户对象
     */
    public TradingAccount credit(BigDecimal amount, OffsetDateTime occurredAt) {
        return new TradingAccount(
                accountId,
                userId,
                currency,
                scaled(balance.add(amount)),
                frozen,
                marginUsed,
                marginMode,
                modeChangedAt,
                modeCoolingUntil,
                createdAt,
                occurredAt
        );
    }

    /**
     * 生成业务入金回滚后的账户快照。
     *
     * <p>当前阶段先冻结“余额回退”语义，后续若引入更复杂的人工补偿和负余额处理，
     * 也应从该方法继续扩展，而不是改变余额、冻结和保证金三字段的基础含义。
     *
     * @param amount 回滚金额
     * @param occurredAt 本次状态变化时间
     * @return 余额减少后的新账户对象
     */
    public TradingAccount reverseCredit(BigDecimal amount, OffsetDateTime occurredAt) {
        return new TradingAccount(
                accountId,
                userId,
                currency,
                scaled(balance.subtract(amount)),
                frozen,
                marginUsed,
                marginMode,
                modeChangedAt,
                modeCoolingUntil,
                createdAt,
                occurredAt
        );
    }

    /**
     * 生成“预留保证金”后的账户快照。
     *
     * @param amount 预留金额
     * @param occurredAt 本次状态变化时间
     * @return `frozen` 增加后的账户对象
     */
    public TradingAccount reserveMargin(BigDecimal amount, OffsetDateTime occurredAt) {
        return new TradingAccount(
                accountId,
                userId,
                currency,
                balance,
                scaled(frozen.add(amount)),
                marginUsed,
                marginMode,
                modeChangedAt,
                modeCoolingUntil,
                createdAt,
                occurredAt
        );
    }

    /**
     * STAGE-3-PENDING-ORDER：生成"释放冻结保证金"后的账户快照。
     *
     * @param amount 释放金额（应等于之前 reserveMargin 的金额）
     * @param occurredAt 本次状态变化时间
     * @return `frozen` 减少后的账户对象
     */
    public TradingAccount releaseFrozen(BigDecimal amount, OffsetDateTime occurredAt) {
        return new TradingAccount(
                accountId,
                userId,
                currency,
                balance,
                scaled(frozen.subtract(amount)),
                marginUsed,
                marginMode,
                modeChangedAt,
                modeCoolingUntil,
                createdAt,
                occurredAt
        );
    }

    /**
     * STAGE-7-WITHDRAW Phase 3：链上确认完成结算 — 同时减 frozen 和 balance。
     *
     * <p>提交出金时 frozen += amount（{@link #reserveMargin}），balance 不变；
     * 链上确认时 balance 永久减少 amount + frozen 释放 amount。净效果：balance 真正扣减。
     */
    public TradingAccount settleConfirmedWithdraw(BigDecimal amount, OffsetDateTime occurredAt) {
        return new TradingAccount(
                accountId,
                userId,
                currency,
                scaled(balance.subtract(amount)),
                scaled(frozen.subtract(amount)),
                marginUsed,
                marginMode,
                modeChangedAt,
                modeCoolingUntil,
                createdAt,
                occurredAt
        );
    }

    /**
     * 生成“扣手续费”后的账户快照。
     *
     * @param fee 手续费金额
     * @param occurredAt 本次状态变化时间
     * @return `balance` 扣减后的账户对象
     */
    public TradingAccount chargeFee(BigDecimal fee, OffsetDateTime occurredAt) {
        return new TradingAccount(
                accountId,
                userId,
                currency,
                scaled(balance.subtract(fee)),
                frozen,
                marginUsed,
                marginMode,
                modeChangedAt,
                modeCoolingUntil,
                createdAt,
                occurredAt
        );
    }

    /**
     * 生成“扣减隔夜利息”后的账户快照。
     *
     * @param amount 本次扣减金额，必须为正数
     * @param occurredAt 本次状态变化时间
     * @return `balance` 扣减后的账户对象
     */
    public TradingAccount chargeSwap(BigDecimal amount, OffsetDateTime occurredAt) {
        return new TradingAccount(
                accountId,
                userId,
                currency,
                scaled(balance.subtract(amount)),
                frozen,
                marginUsed,
                marginMode,
                modeChangedAt,
                modeCoolingUntil,
                createdAt,
                occurredAt
        );
    }

    /**
     * 生成“记入隔夜利息收入”后的账户快照。
     *
     * @param amount 本次入账金额，必须为正数
     * @param occurredAt 本次状态变化时间
     * @return `balance` 增加后的账户对象
     */
    public TradingAccount creditSwap(BigDecimal amount, OffsetDateTime occurredAt) {
        return new TradingAccount(
                accountId,
                userId,
                currency,
                scaled(balance.add(amount)),
                frozen,
                marginUsed,
                marginMode,
                modeChangedAt,
                modeCoolingUntil,
                createdAt,
                occurredAt
        );
    }

    /**
     * 生成“追加逐仓保证金”后的账户快照。
     *
     * <p>该动作不改变 `balance / frozen`，只增加 `marginUsed`，
     * 使 `available` 自然减少。
     */
    public TradingAccount supplementIsolatedMargin(BigDecimal amount, OffsetDateTime occurredAt) {
        return new TradingAccount(
                accountId,
                userId,
                currency,
                balance,
                frozen,
                scaled(marginUsed.add(amount)),
                marginMode,
                modeChangedAt,
                modeCoolingUntil,
                createdAt,
                occurredAt
        );
    }

    /**
     * 生成“确认占用保证金”后的账户快照。
     *
     * @param margin 已成交需要确认占用的保证金
     * @param occurredAt 本次状态变化时间
     * @return `frozen` 减少、`marginUsed` 增加后的账户对象
     */
    public TradingAccount confirmMarginUsed(BigDecimal margin, OffsetDateTime occurredAt) {
        return new TradingAccount(
                accountId,
                userId,
                currency,
                balance,
                scaled(frozen.subtract(margin)),
                scaled(marginUsed.add(margin)),
                marginMode,
                modeChangedAt,
                modeCoolingUntil,
                createdAt,
                occurredAt
        );
    }

    /**
     * 生成“持仓退出结算”后的账户快照。
     *
     * <p>该方法同时服务于手动平仓、TP、SL 和强平：
     * `releasedMargin` 只回补 `marginUsed`，`appliedPnl` 只写实际进入账户余额的清算结果。
     * 若发生负净值保护，`appliedPnl` 可能小于真实 `realizedPnl` 的绝对值。
     *
     * @param releasedMargin 释放的保证金
     * @param appliedPnl 实际回写到账户余额的已实现盈亏
     * @param occurredAt 本次状态变化时间
     * @return 持仓退出后的账户对象
     */
    public TradingAccount settlePositionExit(BigDecimal releasedMargin,
                                             BigDecimal appliedPnl,
                                             OffsetDateTime occurredAt) {
        return new TradingAccount(
                accountId,
                userId,
                currency,
                scaled(balance.add(appliedPnl)),
                frozen,
                scaled(marginUsed.subtract(releasedMargin)),
                marginMode,
                modeChangedAt,
                modeCoolingUntil,
                createdAt,
                occurredAt
        );
    }

    private BigDecimal scaled(BigDecimal value) {
        return value.setScale(8, RoundingMode.DOWN);
    }
}
