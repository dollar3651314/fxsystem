package com.falconx.trading.client;

import java.util.List;

/**
 * STAGE-6-KYC trigger 2：trading-core 出金前置「目标地址 ∉ 历史 from_address」校验所需的钱包查询。
 *
 * <p>抽象出接口便于测试 mock，避免 IT 启动 wallet 容器。
 */
public interface WithdrawDepositAddressQueryClient {

    /**
     * 拉取用户在指定链上历史 CONFIRMED 入金的去重 from_address 集合。
     *
     * @param userId 用户 ID
     * @param chain  链类型字符串（ETH / TRON / BSC，与 wallet {@code t_wallet_deposit_tx.chain} 一致）
     * @return 去重 from_address 列表；用户无历史入金时为空 List
     */
    List<String> listConfirmedFromAddresses(long userId, String chain);
}
