package com.falconx.identity.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;

/**
 * `falconx.identity.kyc.reviewed` 事件的跨服务 payload 契约。
 *
 * <p>该对象由 identity-service 在 KYC 审核完成（APPROVED / REJECTED）后发布，
 * 由 trading-core-service 消费后写入 `t_notification` 站内信并通过 WebSocket
 * 推送给目标用户；后续阶段 7 出金链路也可消费此事件来更新前置门禁缓存。
 *
 * <p>事件语义：`at-least-once`。消费方必须基于 `submissionId` 做幂等去重
 * （站内信侧用 `relatedKey = 'kyc.reviewed'` + `relatedId = submissionId`）。
 *
 * @param submissionId KYC 提交记录主键（同时作为消费方幂等键）
 * @param userId 审核目标用户 ID（同时作为 Kafka 分区键）
 * @param result 审核结果：`APPROVED` / `REJECTED`
 * @param kycLevel 审核通过后的 KYC 等级；REJECTED 时为 0
 * @param reviewerId 审核 admin 用户 ID
 * @param reviewAt 审核完成时间（UTC）
 * @param rejectReason 拒绝原因；APPROVED 时为 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KycReviewedEventPayload(
        Long submissionId,
        Long userId,
        String result,
        Integer kycLevel,
        Long reviewerId,
        OffsetDateTime reviewAt,
        String rejectReason
) {
}
