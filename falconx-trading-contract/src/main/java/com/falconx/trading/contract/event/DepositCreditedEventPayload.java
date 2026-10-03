package com.falconx.trading.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * `falconx.trading.deposit.credited` 事件的跨服务 payload 契约。
 *
 * <p>该对象由 trading-core-service 在业务入账完成后发布，
 * 由 identity-service 消费后触发用户从 `PENDING_DEPOSIT` 到 `ACTIVE` 的状态迁移。
 * 当前阶段先冻结字段结构，后续实现事件生产和消费时必须与 Kafka 事件规范保持一致。
 *
 * @param depositId 业务入金事实 ID
 * @param userId 激活目标用户 ID
 * @param accountId 入账账户 ID
 * @param chain 链类型
 * @param token 入金币种
 * @param txHash 链上交易哈希
 * @param amount 入账金额
 * @param creditedAt 入账完成时间
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DepositCreditedEventPayload(
        Long depositId,
        Long userId,
        Long accountId,
        String chain,
        String token,
        String txHash,
        BigDecimal amount,
        OffsetDateTime creditedAt
) {
}
