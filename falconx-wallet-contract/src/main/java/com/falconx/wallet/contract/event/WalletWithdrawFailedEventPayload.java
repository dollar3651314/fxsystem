package com.falconx.wallet.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;

/**
 * STAGE-7-WITHDRAW Phase 3：`falconx.wallet.withdraw.failed` 事件 payload 契约。
 *
 * <p>wallet-service 在链上签名 / 广播 / 确认任一阶段失败发布；包含 nonce 冲突、receipt revert、
 * 超时等。
 *
 * <p>下游消费者：trading-core-service 回滚冻结 — {@code t_account.frozen -= amount} +
 * {@code t_ledger.biz_type=WITHDRAW_REFUND_CHAIN_FAILED} + {@code t_withdraw_order.status=FAILED}。
 * 幂等键 {@code relatedKey='withdraw.failed' + relatedId=withdrawId}。
 *
 * @param withdrawId 出金单 ID
 * @param userId 用户 ID
 * @param network 链类型（ERC20 / TRC20）
 * @param txHash 链上交易哈希；广播前失败时为 {@code null}
 * @param failureCode wallet {@code 2xxxx} 段错误码（{@code 20010 / 20011 / 20014} 等）
 * @param failureReason 人可读失败原因，最大 512 字符
 * @param failedAt 失败时间
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletWithdrawFailedEventPayload(
        Long withdrawId,
        Long userId,
        String network,
        String txHash,
        String failureCode,
        String failureReason,
        OffsetDateTime failedAt
) {
}
