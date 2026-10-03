package com.falconx.trading.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-7-WITHDRAW Phase 2：`falconx.trading.withdraw.reviewed` 事件的跨服务 payload 契约。
 *
 * <p>该事件由 trading-core 在 admin 审核完成后通过 Outbox 发布。{@code result} 取值：
 * {@code APPROVED} / {@code APPROVED_DELAYED} / {@code REJECTED}。
 *
 * <p>下游消费者：
 * <ul>
 *   <li>{@code APPROVED} → wallet-service 启动链上签名 / 广播链路（Phase 3 实施）</li>
 *   <li>{@code APPROVED_DELAYED} → wallet-service 等待 {@code delayedUntil} 到期；admin 可在该窗口紧急取消</li>
 *   <li>{@code REJECTED} → 客户端站内信（Phase 3 实施）</li>
 * </ul>
 *
 * @param withdrawId 出金单 ID
 * @param userId 用户 ID
 * @param result {@code APPROVED / APPROVED_DELAYED / REJECTED}
 * @param reviewerId 审核人 ID
 * @param reviewAt 审核时间
 * @param rejectReason result=REJECTED 时填，否则为 {@code null}
 * @param delayedUntil result=APPROVED_DELAYED 时填（= 审核时间 + 6h），否则为 {@code null}
 * @param amount 出金金额
 * @param currency 币种
 * @param network 链类型
 * @param targetAddress 目标地址
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradingWithdrawReviewedEventPayload(
        Long withdrawId,
        Long userId,
        String result,
        Long reviewerId,
        OffsetDateTime reviewAt,
        String rejectReason,
        OffsetDateTime delayedUntil,
        BigDecimal amount,
        String currency,
        String network,
        String targetAddress
) {
}
