package com.falconx.trading.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-7-WITHDRAW Phase 2：`falconx.trading.withdraw.requested` 事件的跨服务 payload 契约。
 *
 * <p>该对象由 trading-core-service 在用户提交出金（status 落库为 COOLING）后通过 Outbox 发布，
 * 主要消费者：
 * <ul>
 *   <li>console / admin 监控：审计 + 待办提示</li>
 *   <li>BI / 风控：审计与统计</li>
 * </ul>
 *
 * <p>事件不直接驱动 wallet 链上动作；链上广播由 admin 审核通过后另一事件
 * {@code trading.withdraw.reviewed} 触发。
 *
 * @param withdrawId 出金单 ID
 * @param userId 申请用户 ID
 * @param amount 出金金额
 * @param currency 币种（一期固定 USDT）
 * @param network 链类型（ERC20 / TRC20）
 * @param targetAddress 目标地址（已通过白名单确权）
 * @param whitelistId 白名单 ID
 * @param coolingUntil 冷静期到期时间（= 创建时间 + 2h）
 * @param createdAt 创建时间
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradingWithdrawRequestedEventPayload(
        Long withdrawId,
        Long userId,
        BigDecimal amount,
        String currency,
        String network,
        String targetAddress,
        Long whitelistId,
        OffsetDateTime coolingUntil,
        OffsetDateTime createdAt
) {
}
