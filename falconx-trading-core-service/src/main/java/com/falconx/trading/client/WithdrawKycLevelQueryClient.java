package com.falconx.trading.client;

/**
 * STAGE-7-WITHDRAW：trading-core 出金前置 KYC 等级查询。
 *
 * <p>抽象出接口便于：
 * <ul>
 *   <li>对外：HTTP 实现走 identity {@code /internal/v1/identity/users/{id}/kyc-status}</li>
 *   <li>测试：mock 实现避免 IT 启动 identity 容器</li>
 * </ul>
 */
public interface WithdrawKycLevelQueryClient {

    /**
     * 查询用户 KYC 等级。
     *
     * @param userId 用户 ID
     * @return kyc_level（0 / 1）
     */
    int queryKycLevel(long userId);
}
