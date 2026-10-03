package com.falconx.trading.service;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingLedgerEntry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 交易账户服务接口。
 *
 * <p>该服务封装交易账户语义和账本留痕规则，
 * 确保应用层在调用账户变更时不会破坏 `balance / frozen / marginUsed` 的定义。
 */
public interface TradingAccountService {

    /**
     * 持仓退出结算结果。
     *
     * @param account 变更后的账户
     * @param appliedPnl 实际记入账户余额的清算盈亏
     * @param platformCoveredLoss 因负净值保护由平台兜底的亏损
     */
    record PositionSettlementResult(
            TradingAccount account,
            BigDecimal appliedPnl,
            BigDecimal platformCoveredLoss
    ) {
    }

    /**
     * 获取或初始化交易账户。
     *
     * @param userId 用户 ID
     * @param currency 账户币种
     * @return 现有或新建账户
     */
    TradingAccount getOrCreateAccount(Long userId, String currency);

    /**
     * 获取或初始化交易账户，并在存在记录时对账户行加悲观锁。
     *
     * <p>该方法只用于需要“先读取余额、再同步扣减”的交易写路径，避免并发请求在风控检查阶段读到相同余额快照。
     *
     * @param userId 用户 ID
     * @param currency 账户币种
     * @return 现有或新建且已加锁的账户
     */
    TradingAccount getOrCreateAccountForUpdate(Long userId, String currency);

    /**
     * 获取已存在的交易账户并加悲观锁。
     *
     * <p>该方法只用于“不允许隐式补建账户”的结算类写路径。
     * 若 owner 账户缺失或币种不匹配，调用方必须让整笔事务失败并回滚。
     *
     * @param userId 用户 ID
     * @param currency 账户币种
     * @return 已存在且已加锁的账户
     */
    TradingAccount getExistingAccountForUpdate(Long userId, String currency);

    /**
     * 记一笔业务入金。
     *
     * @param userId 用户 ID
     * @param currency 币种
     * @param amount 入账金额
     * @param idempotencyKey 账务幂等键
     * @param referenceNo 业务参考号
     * @param occurredAt 发生时间
     * @return 变更后账户
     */
    TradingAccount creditDeposit(Long userId,
                                 String currency,
                                 BigDecimal amount,
                                 String idempotencyKey,
                                 String referenceNo,
                                 OffsetDateTime occurredAt);

    /**
     * 反转一笔已入账业务入金。
     *
     * @param userId 用户 ID
     * @param currency 币种
     * @param amount 回滚金额
     * @param idempotencyKey 账务幂等键
     * @param referenceNo 业务参考号
     * @param occurredAt 发生时间
     * @return 变更后账户
     */
    TradingAccount reverseDeposit(Long userId,
                                  String currency,
                                  BigDecimal amount,
                                  String idempotencyKey,
                                  String referenceNo,
                                  OffsetDateTime occurredAt);

    /**
     * 预留保证金。
     *
     * @param userId 用户 ID
     * @param currency 币种
     * @param margin 预留金额
     * @param idempotencyKey 账务幂等键
     * @param referenceNo 业务参考号
     * @param occurredAt 发生时间
     * @return 变更后账户
     */
    TradingAccount reserveMargin(Long userId,
                                 String currency,
                                 BigDecimal margin,
                                 String idempotencyKey,
                                 String referenceNo,
                                 OffsetDateTime occurredAt);

    /**
     * STAGE-7-WITHDRAW：用户冷静期取消出金，退还冻结余额。
     *
     * <p>frozen -= amount；账本 biz_type 写
     * {@link TradingLedgerBizType#WITHDRAW_REFUND_CANCEL}。
     */
    TradingAccount refundCanceledWithdraw(Long userId,
                                            String currency,
                                            BigDecimal amount,
                                            String idempotencyKey,
                                            String referenceNo,
                                            OffsetDateTime occurredAt);

    /**
     * STAGE-7-WITHDRAW：admin 拒绝出金，退还冻结余额。
     *
     * <p>frozen -= amount；账本 biz_type 写
     * {@link TradingLedgerBizType#WITHDRAW_REFUND_REJECT}。
     */
    TradingAccount refundRejectedWithdraw(Long userId,
                                           String currency,
                                           BigDecimal amount,
                                           String idempotencyKey,
                                           String referenceNo,
                                           OffsetDateTime occurredAt);

    /**
     * STAGE-7-WITHDRAW：admin 在大额延迟期内紧急取消，退还冻结余额。
     *
     * <p>frozen -= amount；账本 biz_type 写
     * {@link TradingLedgerBizType#WITHDRAW_REFUND_EMERGENCY}。
     */
    TradingAccount refundEmergencyCanceledWithdraw(Long userId,
                                                     String currency,
                                                     BigDecimal amount,
                                                     String idempotencyKey,
                                                     String referenceNo,
                                                     OffsetDateTime occurredAt);

    /**
     * STAGE-7-WITHDRAW Phase 3：链上确认完成 — 真实扣减 balance + 释放 frozen。
     *
     * <p>balance -= amount + frozen -= amount；账本 biz_type 写
     * {@link TradingLedgerBizType#WITHDRAW_SETTLE}。
     */
    TradingAccount settleConfirmedWithdraw(Long userId,
                                            String currency,
                                            BigDecimal amount,
                                            String idempotencyKey,
                                            String referenceNo,
                                            OffsetDateTime occurredAt);

    /**
     * STAGE-7-WITHDRAW Phase 3：链上失败回滚 — 仅释放 frozen，balance 不变。
     *
     * <p>frozen -= amount；账本 biz_type 写
     * {@link TradingLedgerBizType#WITHDRAW_REFUND_CHAIN_FAILED}。
     */
    TradingAccount refundFailedWithdraw(Long userId,
                                         String currency,
                                         BigDecimal amount,
                                         String idempotencyKey,
                                         String referenceNo,
                                         OffsetDateTime occurredAt);

    /**
     * STAGE-7-WITHDRAW：提交出金时冻结余额。
     *
     * <p>语义同 {@link #reserveMargin}（frozen += amount），但账本 biz_type 写
     * {@link TradingLedgerBizType#WITHDRAW_FREEZE} 以区分追溯来源。
     *
     * @param userId 用户 ID
     * @param currency 币种
     * @param amount 冻结金额
     * @param idempotencyKey 账务幂等键（沿用 withdraw idempotency key）
     * @param referenceNo 业务参考号（建议传 withdrawOrderId）
     * @param occurredAt 发生时间
     * @return 变更后账户
     */
    TradingAccount freezeForWithdraw(Long userId,
                                      String currency,
                                      BigDecimal amount,
                                      String idempotencyKey,
                                      String referenceNo,
                                      OffsetDateTime occurredAt);

    /**
     * STAGE-3-PENDING-ORDER：释放冻结资金。
     *
     * <p>挂单撤销 / 触发后释放挂单创建时的 frozen（margin + fee）。
     * 仅减 frozen 字段，不改 balance / marginUsed。
     *
     * @return 变更后账户
     */
    TradingAccount releaseFrozen(Long userId,
                                  String currency,
                                  BigDecimal amount,
                                  String idempotencyKey,
                                  String referenceNo,
                                  OffsetDateTime occurredAt);

    /**
     * 扣减手续费。
     *
     * @param userId 用户 ID
     * @param currency 币种
     * @param fee 手续费金额
     * @param idempotencyKey 账务幂等键
     * @param referenceNo 业务参考号
     * @param occurredAt 发生时间
     * @return 变更后账户
     */
    TradingAccount chargeFee(Long userId,
                             String currency,
                             BigDecimal fee,
                             String idempotencyKey,
                             String referenceNo,
                             OffsetDateTime occurredAt);

    /**
     * 追加逐仓保证金。
     *
     * <p>该方法只增加 `marginUsed`，不改变 `balance / frozen`。
     *
     * @param existingAccount 已存在且已加锁的账户
     * @param amount 追加金额
     * @param idempotencyKey 账务幂等键
     * @param referenceNo 业务参考号
     * @param occurredAt 发生时间
     * @return 变更后账户
     */
    TradingAccount supplementIsolatedMargin(TradingAccount existingAccount,
                                            BigDecimal amount,
                                            String idempotencyKey,
                                            String referenceNo,
                                            OffsetDateTime occurredAt);

    /**
     * 结算一笔隔夜利息（STAGE-14B Task 8：货币换算 + 落账三列真值）。
     *
     * <p>该方法只允许修改账户 `balance`，不释放保证金，也不改变持仓状态。
     *
     * <p><b>币种口径</b>：账户 balance 一律按账户币计，故 balance 变更使用账户币 {@code amount}；
     * t_ledger 三列留痕记录原币 {@code originalAmount}（Swap(QC)）、{@code originalCurrency}（计价币）
     * 与当次 {@code fxRateAtSettlement}（fx(QC→AC)），对齐 master §3.2「落账三列留痕」。
     * 同币种 / FX 降级场景由调用方传 {@code amount==originalAmount、originalCurrency=账户币、fxRate=ONE}。
     *
     * @param existingAccount 已存在且已加锁的账户
     * @param amount 账户币结算金额（Swap(AC)），必须为正数；驱动 balance 变更
     * @param originalAmount 原币结算金额（Swap(QC)），落 t_ledger.original_amount
     * @param originalCurrency 原币代码（计价币），落 t_ledger.original_currency
     * @param fxRateAtSettlement 当次 fx(QC→AC)，落 t_ledger.fx_rate_at_settlement
     * @param ledgerBizType `SWAP_CHARGE` 或 `SWAP_INCOME`
     * @param idempotencyKey 账务幂等键
     * @param referenceNo 业务参考号
     * @param occurredAt 发生时间
     * @return 已持久化账本流水
     */
    TradingLedgerEntry settleSwap(TradingAccount existingAccount,
                                  BigDecimal amount,
                                  BigDecimal originalAmount,
                                  String originalCurrency,
                                  BigDecimal fxRateAtSettlement,
                                  TradingLedgerBizType ledgerBizType,
                                  String idempotencyKey,
                                  String referenceNo,
                                  OffsetDateTime occurredAt);

    /**
     * 确认占用保证金。
     *
     * @param userId 用户 ID
     * @param currency 币种
     * @param margin 需确认的保证金
     * @param idempotencyKey 账务幂等键
     * @param referenceNo 业务参考号
     * @param occurredAt 发生时间
     * @return 变更后账户
     */
    TradingAccount confirmMarginUsed(Long userId,
                                     String currency,
                                     BigDecimal margin,
                                     String idempotencyKey,
                                     String referenceNo,
                                     OffsetDateTime occurredAt);

    /**
     * 原子应用下单的资金变更：保证金预留 + 手续费扣减 + 保证金确认占用，写 3 条 ledger 流水。
     *
     * <p>专为 {@link com.falconx.trading.application.TradingOrderPlacementApplicationService}
     * 下单链路设计，避免多次锁同一账户行（Sprint 3 C1 / 性能报告 §2 P0）。
     *
     * <p>事务边界：必须在外层 {@code @Transactional} 内调用；调用前外层应已持有该账户的
     * SELECT FOR UPDATE 锁（通过 {@link #getOrCreateAccountForUpdate} 或
     * {@link #getExistingAccountForUpdate} 取锁）。
     *
     * <p>幂等性：与原 {@link #reserveMargin}/{@link #chargeFee}/{@link #confirmMarginUsed}
     * 完全一致 — 3 条 ledger 分别使用幂等键 {@code "order-margin-reserve:" + clientOrderId}、
     * {@code "order-fee-charge:" + clientOrderId}、{@code "order-margin-confirm:" + clientOrderId}，
     * t_ledger 的 {@code (user_id, idempotency_key)} UNIQUE 约束保证重试幂等。
     *
     * <p>失败语义：领域对象校验失败（{@link com.falconx.trading.entity.TradingAccount#reserveMargin}
     * 等抛业务异常）、UPDATE 失败、batchInsert 唯一键冲突 — 任一失败整事务回滚。
     *
     * <p>STAGE-14B Task 9a：开仓 margin reserve / fee 落账三列写真值（master §3.2）—
     * t_ledger.amount=账户币（{@code margin}/{@code fee}，inAccount，驱动 balance/frozen 变更）；
     * original_amount=原币（{@code marginInQuote}/{@code feeInQuote}，inQuote）；
     * original_currency={@code quoteCurrency}；margin 两笔（RESERVED/CONFIRMED）的
     * fx_rate_at_settlement={@code fxRate}，fee 笔（FEE_CHARGED）的 fx_rate_at_settlement={@code feeFxRate}。
     * ORDER_MARGIN_CONFIRMED 同 margin 真值留痕。
     *
     * <p>STAGE-14B Task 9a 收口：margin 与 fee 在风控阶段各自查询一次 FX 快照，可能取到不同值，故
     * margin 账与 fee 账各自携带自身查询时刻的 fxRate（{@code fxRate} / {@code feeFxRate}），保证各自
     * 账本三列 {@code original_amount × fx_rate_at_settlement = amount} 数学自洽。
     *
     * @param existingAccount 已加锁的账户对象（外层 getOrCreateAccountForUpdate 返回）
     * @param margin 预留并占用的保证金（账户币 inAccount），必须 ≥ 0
     * @param fee 扣减的手续费（账户币 inAccount），必须 ≥ 0
     * @param quoteCurrency 计价币（SymbolSpec.quoteCurrency），写 ledger.original_currency
     * @param marginInQuote 保证金原币值 IM(QC)，写 margin 两笔 ledger.original_amount
     * @param feeInQuote 手续费原币值 Fee(QC)，写 fee ledger.original_amount
     * @param fxRate margin 查询时刻 fx(quoteCurrency→accountCurrency)，写 margin 两笔 ledger.fx_rate_at_settlement；同币种为 ONE
     * @param feeFxRate fee 查询时刻 fx(quoteCurrency→accountCurrency)，写 fee 笔 ledger.fx_rate_at_settlement；同币种为 ONE
     * @param clientOrderId 客户端订单 ID，用于派生 3 条 ledger 的 idempotency_key
     * @param referenceNo 业务参考号，写入 3 条 ledger 的 reference_no 字段
     * @param occurredAt 发生时间
     * @return 变更后账户
     */
    TradingAccount applyOrderPlacementAccountChange(
            TradingAccount existingAccount,
            BigDecimal margin,
            BigDecimal fee,
            String quoteCurrency,
            BigDecimal marginInQuote,
            BigDecimal feeInQuote,
            BigDecimal fxRate,
            BigDecimal feeFxRate,
            String clientOrderId,
            String referenceNo,
            OffsetDateTime occurredAt);

    /**
     * 结算一笔持仓退出（STAGE-14B Task 9b：货币换算 + 落账三列真值）。
     *
     * <p>该方法是手动平仓、TP、SL 和强平共享的正式资金写路径。
     * 若启用负净值保护，账户余额最低只能落到 `0`，超出部分由返回值中的 `platformCoveredLoss` 表示。
     *
     * <p><b>币种口径</b>（master §3.2「已实现盈亏 Realized PnL」）：账户 balance 一律按账户币计，
     * 故 balance 变更、负净值保护与 platformCoveredLoss 一律使用账户币 {@code realizedPnlInAccount}；
     * t_ledger 三列留痕记原币 {@code originalAmount}（RealizedPnL(QC)）、{@code originalCurrency}（计价币）
     * 与当次 {@code fxRateAtSettlement}（fxAtClose）。
     * t_ledger.amount = 实际记账的账户币已实现盈亏（负净值保护封顶后的 {@code appliedPnl}）。
     * 同币种 / FX 降级场景由调用方传 {@code realizedPnlInAccount==originalAmount、originalCurrency=账户币、fxRate=ONE}。
     *
     * <p><b>负净值保护与三列非自洽（Task 9b 收口 Issue 1）</b>：{@code protectNegativeBalance} 触发封顶时，
     * t_ledger.amount = 封顶后账户币 {@code appliedPnl}（如 -50），而三列 {@code originalAmount} 仍记完整
     * 原币（如 -200）、{@code fxRateAtSettlement} 记 fxAtClose（如 0.65），此时
     * amount ≠ originalAmount × fxRateAtSettlement 为【预期审计行为】（留痕「实际亏 200、扣 50、平台补 80」全貌）。
     *
     * <p><b>⚠ 参数顺序防呆（Task 9b 收口 Issue 4）</b>：{@code releasedMargin}（释放保证金）、
     * {@code realizedPnlInAccount}（账户币盈亏，驱动 balance）、{@code originalAmount}（原币盈亏，仅留痕，
     * 不驱动 balance）三者均为 {@link BigDecimal}，编译期不区分，<b>位置传错会让原币当账户币扣进 balance</b>，
     * 调用方必须严格按下面 @param 顺序传入。command record 重构留待 STAGE-14C，本阶段不引入。
     *
     * @param existingAccount 已存在且已加锁的账户
     * @param releasedMargin 释放保证金（账户币，BigDecimal）；回退 marginUsed，不直接计入 balance
     * @param realizedPnlInAccount 账户币已实现盈亏 RealizedPnL(AC)（BigDecimal）；<b>驱动 balance 变更与负净值保护</b>，封顶后即 appliedPnl
     * @param originalAmount 原币已实现盈亏 RealizedPnL(QC)（BigDecimal）；<b>仅落 t_ledger.original_amount 供审计，不驱动 balance</b>
     * @param originalCurrency 原币代码（计价币 QC）；落 t_ledger.original_currency
     * @param fxRateAtSettlement 当次 fxAtClose(QC→AC)；落 t_ledger.fx_rate_at_settlement
     * @param ledgerBizType 本次账本业务类型（`REALIZED_PNL` 或 `LIQUIDATION_PNL`）
     * @param protectNegativeBalance 是否启用负净值保护（true 时 balance 最低封顶到 0，超出由 platformCoveredLoss 承担）
     * @param idempotencyKey 账务幂等键
     * @param referenceNo 业务参考号
     * @param occurredAt 发生时间
     * @return 结算结果（`appliedPnl` / `platformCoveredLoss` 均为账户币）
     */
    PositionSettlementResult settlePositionExit(TradingAccount existingAccount,
                                                BigDecimal releasedMargin,
                                                BigDecimal realizedPnlInAccount,
                                                BigDecimal originalAmount,
                                                String originalCurrency,
                                                BigDecimal fxRateAtSettlement,
                                                TradingLedgerBizType ledgerBizType,
                                                boolean protectNegativeBalance,
                                                String idempotencyKey,
                                                String referenceNo,
                                                OffsetDateTime occurredAt);
}
