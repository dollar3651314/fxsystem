package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易账本 MyBatis 记录对象。
 *
 * <p>该记录对象对应 `t_ledger` 的数据库结构。
 *
 * @param id 主键 ID
 * @param userId 用户 ID
 * @param accountId 账户 ID
 * @param bizTypeCode 业务类型码
 * @param idempotencyKey 幂等键
 * @param referenceNo 业务参考号
 * @param amount 变动金额（账户币）
 * @param originalAmount 原币金额（quote currency）；列 original_amount
 * @param originalCurrency 原币种代码；列 original_currency
 * @param fxRateAtSettlement 结算时 FX rate（original→account）；列 fx_rate_at_settlement
 * @param balanceBefore 余额前快照
 * @param balanceAfter 余额后快照
 * @param frozenBefore 冻结前快照
 * @param frozenAfter 冻结后快照
 * @param marginUsedBefore 保证金前快照
 * @param marginUsedAfter 保证金后快照
 * @param createdAt 创建时间
 */
public record TradingLedgerRecord(
        Long id,
        Long userId,
        Long accountId,
        Integer bizTypeCode,
        String idempotencyKey,
        String referenceNo,
        BigDecimal amount,
        BigDecimal originalAmount,
        String originalCurrency,
        BigDecimal fxRateAtSettlement,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        BigDecimal frozenBefore,
        BigDecimal frozenAfter,
        BigDecimal marginUsedBefore,
        BigDecimal marginUsedAfter,
        LocalDateTime createdAt
) {
}
