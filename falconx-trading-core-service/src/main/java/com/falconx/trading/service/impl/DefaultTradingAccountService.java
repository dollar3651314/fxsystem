package com.falconx.trading.service.impl;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingLedgerEntry;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.repository.TradingAccountRepository;
import com.falconx.trading.repository.TradingLedgerRepository;
import com.falconx.trading.service.TradingAccountService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 交易账户服务默认实现。
 *
 * <p>该实现承担 Stage 3B 的账户语义冻结职责：
 *
 * <ol>
 *   <li>统一创建或读取交易账户</li>
 *   <li>把账户变更转换为不可变新快照</li>
 *   <li>为每次变化写出可回放账本流水</li>
 * </ol>
 *
 * <p>当前阶段账户 owner 数据已经接入真实仓储，但方法边界仍按本地事务模型保持稳定：
 * 后续若从 JDBC 迁移到 `MyBatis + XML Mapper`，也只允许替换持久化实现，不应改变业务步骤。
 *
 * <p><b>并发安全（2026-05-20 加固）：</b>所有 mutate 方法（reserveMargin / chargeFee / releaseFrozen /
 * creditDeposit / freezeForWithdraw / refundXxxWithdraw / settleConfirmedWithdraw / confirmMarginUsed）
 * 入口都用 {@link #getOrCreateAccountForUpdate} 拿 SELECT ... FOR UPDATE 行锁。原因：之前 cancel pending
 * order 这种 caller 只锁了挂单行没锁账户行，再调 releaseFrozen 触发 read-modify-write，并发 (cancel A +
 * placeOrder B 同时操作同一账户) 会丢失更新（balance/frozen 算错）。InnoDB 同事务 FOR UPDATE 可重入，
 * 即使 caller 已经持锁也不会死锁；caller 没持锁时也被这里兜底保护。
 */
@Service
public class DefaultTradingAccountService implements TradingAccountService {

    private final TradingAccountRepository tradingAccountRepository;
    private final TradingLedgerRepository tradingLedgerRepository;

    public DefaultTradingAccountService(TradingAccountRepository tradingAccountRepository,
                                        TradingLedgerRepository tradingLedgerRepository) {
        this.tradingAccountRepository = tradingAccountRepository;
        this.tradingLedgerRepository = tradingLedgerRepository;
    }

    @Override
    public TradingAccount getOrCreateAccount(Long userId, String currency) {
        return getOrCreateAccountInternal(userId, currency, false);
    }

    @Override
    public TradingAccount getOrCreateAccountForUpdate(Long userId, String currency) {
        return getOrCreateAccountInternal(userId, currency, true);
    }

    @Override
    public TradingAccount getExistingAccountForUpdate(Long userId, String currency) {
        return findExistingAccountForUpdateOrThrow(userId, currency);
    }

    @Override
    public TradingAccount creditDeposit(Long userId,
                                        String currency,
                                        BigDecimal amount,
                                        String idempotencyKey,
                                        String referenceNo,
                                        OffsetDateTime occurredAt) {
        TradingAccount before = getOrCreateAccountForUpdate(userId, currency);
        TradingAccount after = tradingAccountRepository.save(before.credit(amount, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.DEPOSIT_CREDIT, amount, amount, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingAccount reverseDeposit(Long userId,
                                         String currency,
                                         BigDecimal amount,
                                         String idempotencyKey,
                                         String referenceNo,
                                         OffsetDateTime occurredAt) {
        TradingAccount before = getOrCreateAccountForUpdate(userId, currency);
        TradingAccount after = tradingAccountRepository.save(before.reverseCredit(amount, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.DEPOSIT_REVERSAL, amount, amount, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingAccount reserveMargin(Long userId,
                                        String currency,
                                        BigDecimal margin,
                                        String idempotencyKey,
                                        String referenceNo,
                                        OffsetDateTime occurredAt) {
        TradingAccount before = getOrCreateAccountForUpdate(userId, currency);
        TradingAccount after = tradingAccountRepository.save(before.reserveMargin(margin, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.ORDER_MARGIN_RESERVED, margin, margin, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingAccount chargeFee(Long userId,
                                    String currency,
                                    BigDecimal fee,
                                    String idempotencyKey,
                                    String referenceNo,
                                    OffsetDateTime occurredAt) {
        TradingAccount before = getOrCreateAccountForUpdate(userId, currency);
        TradingAccount after = tradingAccountRepository.save(before.chargeFee(fee, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.ORDER_FEE_CHARGED, fee, fee, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingAccount releaseFrozen(Long userId,
                                         String currency,
                                         BigDecimal amount,
                                         String idempotencyKey,
                                         String referenceNo,
                                         OffsetDateTime occurredAt) {
        TradingAccount before = getOrCreateAccountForUpdate(userId, currency);
        TradingAccount after = tradingAccountRepository.save(before.releaseFrozen(amount, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.PENDING_ORDER_RELEASED, amount, amount, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingAccount freezeForWithdraw(Long userId,
                                             String currency,
                                             BigDecimal amount,
                                             String idempotencyKey,
                                             String referenceNo,
                                             OffsetDateTime occurredAt) {
        TradingAccount before = getOrCreateAccountForUpdate(userId, currency);
        TradingAccount after = tradingAccountRepository.save(before.reserveMargin(amount, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.WITHDRAW_FREEZE, amount, amount, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingAccount refundCanceledWithdraw(Long userId,
                                                  String currency,
                                                  BigDecimal amount,
                                                  String idempotencyKey,
                                                  String referenceNo,
                                                  OffsetDateTime occurredAt) {
        TradingAccount before = getOrCreateAccountForUpdate(userId, currency);
        TradingAccount after = tradingAccountRepository.save(before.releaseFrozen(amount, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.WITHDRAW_REFUND_CANCEL, amount, amount, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingAccount refundRejectedWithdraw(Long userId,
                                                   String currency,
                                                   BigDecimal amount,
                                                   String idempotencyKey,
                                                   String referenceNo,
                                                   OffsetDateTime occurredAt) {
        TradingAccount before = getOrCreateAccountForUpdate(userId, currency);
        TradingAccount after = tradingAccountRepository.save(before.releaseFrozen(amount, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.WITHDRAW_REFUND_REJECT, amount, amount, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingAccount refundEmergencyCanceledWithdraw(Long userId,
                                                            String currency,
                                                            BigDecimal amount,
                                                            String idempotencyKey,
                                                            String referenceNo,
                                                            OffsetDateTime occurredAt) {
        TradingAccount before = getOrCreateAccountForUpdate(userId, currency);
        TradingAccount after = tradingAccountRepository.save(before.releaseFrozen(amount, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.WITHDRAW_REFUND_EMERGENCY, amount, amount, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingAccount settleConfirmedWithdraw(Long userId,
                                                    String currency,
                                                    BigDecimal amount,
                                                    String idempotencyKey,
                                                    String referenceNo,
                                                    OffsetDateTime occurredAt) {
        TradingAccount before = getOrCreateAccountForUpdate(userId, currency);
        TradingAccount after = tradingAccountRepository.save(before.settleConfirmedWithdraw(amount, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.WITHDRAW_SETTLE, amount, amount, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingAccount refundFailedWithdraw(Long userId,
                                                 String currency,
                                                 BigDecimal amount,
                                                 String idempotencyKey,
                                                 String referenceNo,
                                                 OffsetDateTime occurredAt) {
        TradingAccount before = getOrCreateAccountForUpdate(userId, currency);
        TradingAccount after = tradingAccountRepository.save(before.releaseFrozen(amount, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.WITHDRAW_REFUND_CHAIN_FAILED, amount, amount, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingAccount supplementIsolatedMargin(TradingAccount existingAccount,
                                                   BigDecimal amount,
                                                   String idempotencyKey,
                                                   String referenceNo,
                                                   OffsetDateTime occurredAt) {
        TradingAccount before = Objects.requireNonNull(existingAccount, "existingAccount");
        BigDecimal positiveAmount = Objects.requireNonNull(amount, "amount");
        if (positiveAmount.signum() <= 0) {
            throw new IllegalArgumentException("Isolated margin supplement amount must be positive");
        }
        TradingAccount after = tradingAccountRepository.save(before.supplementIsolatedMargin(positiveAmount, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.ISOLATED_MARGIN_SUPPLEMENT, positiveAmount, positiveAmount, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingLedgerEntry settleSwap(TradingAccount existingAccount,
                                         BigDecimal amount,
                                         BigDecimal originalAmount,
                                         String originalCurrency,
                                         BigDecimal fxRateAtSettlement,
                                         TradingLedgerBizType ledgerBizType,
                                         String idempotencyKey,
                                         String referenceNo,
                                         OffsetDateTime occurredAt) {
        TradingAccount before = Objects.requireNonNull(existingAccount, "existingAccount");
        // STAGE-14B Task 8：amount 为账户币 Swap(AC)，驱动 balance 变更（账户 balance 按账户币计）。
        BigDecimal positiveAmount = Objects.requireNonNull(amount, "amount");
        if (positiveAmount.signum() < 0) {
            throw new IllegalArgumentException("Swap settlement amount must be positive");
        }
        if (ledgerBizType != TradingLedgerBizType.SWAP_CHARGE && ledgerBizType != TradingLedgerBizType.SWAP_INCOME) {
            throw new IllegalArgumentException("Swap settlement requires SWAP_CHARGE or SWAP_INCOME");
        }
        TradingAccount after = ledgerBizType == TradingLedgerBizType.SWAP_CHARGE
                ? tradingAccountRepository.save(before.chargeSwap(positiveAmount, occurredAt))
                : tradingAccountRepository.save(before.creditSwap(positiveAmount, occurredAt));
        // 三列留痕：amount=账户币 Swap(AC)；original_amount=原币 Swap(QC)；original_currency=计价币；fx=当次快照。
        return writeLedger(before, after, ledgerBizType, positiveAmount, originalAmount, originalCurrency, fxRateAtSettlement, idempotencyKey, referenceNo, occurredAt);
    }

    @Override
    public TradingAccount confirmMarginUsed(Long userId,
                                            String currency,
                                            BigDecimal margin,
                                            String idempotencyKey,
                                            String referenceNo,
                                            OffsetDateTime occurredAt) {
        TradingAccount before = getOrCreateAccountForUpdate(userId, currency);
        TradingAccount after = tradingAccountRepository.save(before.confirmMarginUsed(margin, occurredAt));
        writeLedger(before, after, TradingLedgerBizType.ORDER_MARGIN_CONFIRMED, margin, margin, before.currency(), BigDecimal.ONE, idempotencyKey, referenceNo, occurredAt);
        return after;
    }

    @Override
    public TradingAccount applyOrderPlacementAccountChange(
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
            OffsetDateTime occurredAt) {
        // Sprint 3 C1 / 性能报告 §2 P0：
        // 把下单链路的 reserveMargin + chargeFee + confirmMarginUsed 三步合并为原子方法，
        // 单 UPDATE 写最终 balance/frozen/margin_used，批量 INSERT 3 条 ledger。
        // 持锁时间从 3 次 SQL roundtrip 降到 1 次 UPDATE + 1 次 batch INSERT。

        // 领域对象 3 步链式：每步内部规则校验（如 balance < margin 抛业务异常）。
        TradingAccount afterReserve = existingAccount.reserveMargin(margin, occurredAt);
        TradingAccount afterFee = afterReserve.chargeFee(fee, occurredAt);
        TradingAccount finalAccount = afterFee.confirmMarginUsed(margin, occurredAt);

        // 单 UPDATE 写入最终账户状态（balance / frozen / margin_used 三字段）。
        TradingAccount persisted = tradingAccountRepository.save(finalAccount);

        // 批量 INSERT 3 条 ledger，biz_type / idempotency_key / amount 与原
        // reserveMargin / chargeFee / confirmMarginUsed 三个独立方法保持完全一致。
        // STAGE-14B Task 9a：开仓三笔落账三列写真值（master §3.2）—
        //   amount = 账户币 inAccount（margin / fee，驱动 balance/frozen 变更）；
        //   original_amount = 原币 inQuote（marginInQuote / feeInQuote）；
        //   original_currency = quoteCurrency；fx_rate_at_settlement = 各自查询时刻 fxRate。
        //   同币种（USDT-quote）时上层传 quoteCurrency=账户币、inQuote=inAccount、fxRate/feeFxRate=ONE，三列退化。
        //   STAGE-14B Task 9a 收口：margin 两笔（RESERVED/CONFIRMED）用 fxRate，fee 笔（FEE_CHARGED）
        //   用 feeFxRate —— margin 与 fee 在风控阶段各查一次 FX 快照可能取到不同值，各账携带自身查询时刻
        //   的 fx，保证各自 original_amount × fx == amount 数学自洽。
        //   ORDER_MARGIN_CONFIRMED 不改 balance，original_amount 表示“确认占用的保证金原币值（审计快照），
        //   与 RESERVED 同一笔保证金额（marginInQuote），不改 balance”，仍用 margin 真值与 fxRate 留痕保持同笔一致。
        java.util.List<TradingLedgerEntry> entries = java.util.List.of(
                toLedgerEntry(existingAccount, afterReserve, TradingLedgerBizType.ORDER_MARGIN_RESERVED,
                        margin, marginInQuote, quoteCurrency, fxRate, "order-margin-reserve:" + clientOrderId, referenceNo, occurredAt),
                toLedgerEntry(afterReserve, afterFee, TradingLedgerBizType.ORDER_FEE_CHARGED,
                        fee, feeInQuote, quoteCurrency, feeFxRate, "order-fee-charge:" + clientOrderId, referenceNo, occurredAt),
                toLedgerEntry(afterFee, finalAccount, TradingLedgerBizType.ORDER_MARGIN_CONFIRMED,
                        margin, marginInQuote, quoteCurrency, fxRate, "order-margin-confirm:" + clientOrderId, referenceNo, occurredAt)
        );
        tradingLedgerRepository.batchInsert(entries);

        return persisted;
    }

    /**
     * 拼装单条 ledger 领域对象（不持久化）。供 {@link #applyOrderPlacementAccountChange} 内部
     * 批量 INSERT 使用，逻辑与私有 {@link #writeLedger} 镜像但不直接调 save。
     */
    private TradingLedgerEntry toLedgerEntry(TradingAccount before,
                                             TradingAccount after,
                                             TradingLedgerBizType bizType,
                                             BigDecimal amount,
                                             BigDecimal originalAmount,
                                             String originalCurrency,
                                             BigDecimal fxRateAtSettlement,
                                             String idempotencyKey,
                                             String referenceNo,
                                             OffsetDateTime occurredAt) {
        return new TradingLedgerEntry(
                null,
                after.accountId(),
                after.userId(),
                bizType,
                amount,
                originalAmount,
                originalCurrency,
                fxRateAtSettlement,
                idempotencyKey,
                referenceNo,
                before.balance(),
                after.balance(),
                before.frozen(),
                after.frozen(),
                before.marginUsed(),
                after.marginUsed(),
                occurredAt
        );
    }

    @Override
    public PositionSettlementResult settlePositionExit(TradingAccount existingAccount,
                                                       BigDecimal releasedMargin,
                                                       BigDecimal realizedPnlInAccount,
                                                       BigDecimal originalAmount,
                                                       String originalCurrency,
                                                       BigDecimal fxRateAtSettlement,
                                                       TradingLedgerBizType ledgerBizType,
                                                       boolean protectNegativeBalance,
                                                       String idempotencyKey,
                                                       String referenceNo,
                                                       OffsetDateTime occurredAt) {
        TradingAccount before = Objects.requireNonNull(existingAccount, "existingAccount");
        if (before.marginUsed().compareTo(releasedMargin) < 0) {
            throw new IllegalStateException(
                    "Trading margin_used snapshot is inconsistent, accountId=" + before.accountId()
                            + ", marginUsed=" + before.marginUsed()
                            + ", releasedMargin=" + releasedMargin
            );
        }

        // STAGE-14B Task 9b：balance 变更 / 负净值保护 / platformCoveredLoss 一律基于账户币
        // realizedPnlInAccount（RealizedPnL(AC)）。平台兜底承担的是账户币损失。
        //
        // STAGE-14B Task 9b 收口 Issue 1（三列非自洽预期）：protectNegativeBalance 触发封顶时，
        //   t_ledger.amount = 封顶后账户币 appliedPnl（如 -50），而 original_amount 仍是完整原币
        //   realizedPnl(QC)（如 -200）、fx_rate=fxAtClose（如 0.65）。此时 amount ≠ original_amount × fx，
        //   这是【预期审计行为】：账本同时留痕「实际亏 200 AUD、仅扣账户 50 USDT、平台补 80」全貌。
        //   非封顶场景（appliedPnl == realizedPnlInAccount）下三列与 amount 仍满足换算关系。
        BigDecimal appliedPnl = realizedPnlInAccount;
        BigDecimal platformCoveredLoss = BigDecimal.ZERO.setScale(8);
        if (protectNegativeBalance && realizedPnlInAccount.signum() < 0) {
            BigDecimal minimumAllowedPnl = before.balance().negate();
            if (realizedPnlInAccount.compareTo(minimumAllowedPnl) < 0) {
                appliedPnl = minimumAllowedPnl.setScale(8);
                platformCoveredLoss = realizedPnlInAccount.abs().subtract(appliedPnl.abs()).setScale(8);
            }
        }

        TradingAccount after = tradingAccountRepository.save(before.settlePositionExit(releasedMargin, appliedPnl, occurredAt));
        // t_ledger.amount = 实际记账的账户币 appliedPnl（负净值保护封顶后值）；
        // 三列 original_amount/original_currency/fx_rate_at_settlement 留原币真值（master §3.2）。
        // Issue 1：负净值保护封顶时 amount 为封顶后账户币值，三列 original 保留完整原值供审计，
        //   此场景 amount ≠ original_amount × fx 为【预期】（见上方 appliedPnl 计算处注释）。
        writeLedger(before, after, Objects.requireNonNull(ledgerBizType, "ledgerBizType"),
                appliedPnl, originalAmount, originalCurrency, fxRateAtSettlement,
                idempotencyKey, referenceNo, occurredAt);
        return new PositionSettlementResult(after, appliedPnl, platformCoveredLoss);
    }

    /**
     * 写单条账本流水。
     *
     * <p>STAGE-14B Task 5 起携带原币三列（{@code originalAmount} / {@code originalCurrency} /
     * {@code fxRateAtSettlement}）。当前算法层尚未接 CurrencyConverter，所有金额本就是账户币原生值，
     * 故占位策略与 master 设计「老数据 = 账户币 + fx=1」一致：
     * {@code originalAmount = amount}、{@code originalCurrency = 账户币}、{@code fxRateAtSettlement = 1}。
     * Task 6-9 接 converter 后由调用方传入真值。
     */
    private TradingLedgerEntry writeLedger(TradingAccount before,
                                           TradingAccount after,
                                           TradingLedgerBizType bizType,
                                           BigDecimal amount,
                                           BigDecimal originalAmount,
                                           String originalCurrency,
                                           BigDecimal fxRateAtSettlement,
                                           String idempotencyKey,
                                           String referenceNo,
                                           OffsetDateTime occurredAt) {
        return tradingLedgerRepository.save(new TradingLedgerEntry(
                null,
                after.accountId(),
                after.userId(),
                bizType,
                amount,
                originalAmount,
                originalCurrency,
                fxRateAtSettlement,
                idempotencyKey,
                referenceNo,
                before.balance(),
                after.balance(),
                before.frozen(),
                after.frozen(),
                before.marginUsed(),
                after.marginUsed(),
                occurredAt
        ));
    }

    /**
     * 统一承接“查找或创建账户”的并发语义。
     *
     * <p>普通读路径只需返回账户快照；交易写路径则必须在账户已存在时拿到 `FOR UPDATE`
     * 锁，以保证后续保证金校验与扣减基于同一行锁视图完成。
     *
     * <p>若两个线程首次同时为同一用户创建账户，第二个线程会命中唯一键冲突。
     * 这里显式捕获 `DuplicateKeyException`，回退为重新查询，避免把瞬时并发初始化错误暴露给上层。
     */
    private TradingAccount getOrCreateAccountInternal(Long userId, String currency, boolean lockForUpdate) {
        if (lockForUpdate) {
            TradingAccount locked = tradingAccountRepository.findByUserIdAndCurrencyForUpdate(userId, currency).orElse(null);
            if (locked != null) {
                return locked;
            }
        } else {
            TradingAccount existing = tradingAccountRepository.findByUserIdAndCurrency(userId, currency).orElse(null);
            if (existing != null) {
                return existing;
            }
        }

        // 一期默认 ISOLATED；CROSS 推迟到一期之外，但保留 TradingMarginMode 枚举值。
        TradingAccount newAccount = new TradingAccount(
                null,
                userId,
                currency,
                BigDecimal.ZERO.setScale(8),
                BigDecimal.ZERO.setScale(8),
                BigDecimal.ZERO.setScale(8),
                TradingMarginMode.ISOLATED,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        try {
            TradingAccount created = tradingAccountRepository.save(newAccount);
            if (lockForUpdate) {
                return tradingAccountRepository.findByUserIdAndCurrencyForUpdate(userId, currency).orElse(created);
            }
            return created;
        } catch (DuplicateKeyException exception) {
            return lockForUpdate
                    ? tradingAccountRepository.findByUserIdAndCurrencyForUpdate(userId, currency)
                    .orElseThrow(() -> new IllegalStateException("Account not found after duplicate key", exception))
                    : tradingAccountRepository.findByUserIdAndCurrency(userId, currency)
                    .orElseThrow(() -> new IllegalStateException("Account not found after duplicate key", exception));
        }
    }

    private TradingAccount findExistingAccountForUpdateOrThrow(Long userId, String currency) {
        return tradingAccountRepository.findByUserIdAndCurrencyForUpdate(userId, currency)
                .orElseThrow(() -> new IllegalStateException(
                        "Trading settlement account not found or currency mismatch, userId=" + userId + ", currency=" + currency
                ));
    }
}
