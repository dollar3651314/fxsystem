package com.falconx.wallet.entity;

import com.falconx.domain.enums.ChainType;
import java.time.OffsetDateTime;

/**
 * 钱包地址分配实体。
 *
 * <p>该对象对应 `falconx_wallet.t_wallet_address` 的领域表达，
 * 用于表达“某个用户在某条链上已经分配到了哪个平台地址”。
 *
 * @param id 主键 ID，由 owner 仓储在持久化时补齐
 * @param userId 用户 ID
 * @param chain 链类型
 * @param token 入金代币符号
 * @param network 入金网络展示名
 * @param address 平台分配的收款地址
 * @param addressIndex 地址派生索引
 * @param derivationPath HD 派生路径
 * @param status 地址状态
 * @param assignedAt 分配时间
 */
public record WalletAddressAssignment(
        Long id,
        Long userId,
        ChainType chain,
        String token,
        String network,
        String address,
        int addressIndex,
        String derivationPath,
        WalletAddressStatus status,
        OffsetDateTime assignedAt
) {
}
