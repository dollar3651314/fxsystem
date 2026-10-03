package com.falconx.wallet.service;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.entity.WalletAddressAssignment;

/**
 * 钱包地址分配领域服务抽象。
 *
 * <p>该服务负责封装“如何为用户在指定链上拿到一个平台地址”的规则，
 * 并保证同一用户在同一链上的地址申请具备幂等性。
 */
public interface WalletAddressAllocationService {

    /**
     * 为用户分配链地址。
     *
     * @param userId 用户 ID
     * @param chain 链类型
     * @return 地址分配结果
     */
    WalletAddressAssignment allocateAddress(long userId, ChainType chain);
}
