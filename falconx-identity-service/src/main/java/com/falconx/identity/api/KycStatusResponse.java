package com.falconx.identity.api;

/**
 * STAGE-7-WITHDRAW：用户 KYC 状态查询响应（供 trading-core 出金前置校验）。
 *
 * @param userId 用户主键
 * @param kycLevel 0=未认证 / 1=已通过简单 KYC
 */
public record KycStatusResponse(long userId, int kycLevel) {
}
