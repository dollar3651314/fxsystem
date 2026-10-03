package com.falconx.wallet.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * 钱包地址 MyBatis 记录对象。
 *
 * @param id 主键 ID
 * @param userId 用户 ID
 * @param chain 链标识
 * @param token 入金代币符号
 * @param network 入金网络展示名
 * @param address 平台地址
 * @param addressIndex 派生索引
 * @param derivationPath HD 派生路径
 * @param statusCode 状态码
 * @param assignedAt 分配时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record WalletAddressRecord(
        Long id,
        Long userId,
        String chain,
        String token,
        String network,
        String address,
        Integer addressIndex,
        String derivationPath,
        Integer statusCode,
        LocalDateTime assignedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
