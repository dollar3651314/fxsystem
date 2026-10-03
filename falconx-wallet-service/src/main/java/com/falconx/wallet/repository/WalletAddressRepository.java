package com.falconx.wallet.repository;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.entity.WalletAddressAssignment;
import java.util.Set;
import java.util.Optional;

/**
 * 钱包地址仓储抽象。
 *
 * <p>该仓储负责 wallet owner 地址事实的读写。
 */
public interface WalletAddressRepository {

    /**
     * 按用户和链查询已分配地址，用于地址申请幂等。
     *
     * @param userId 用户 ID
     * @param chain 链类型
     * @return 已分配地址
     */
    Optional<WalletAddressAssignment> findByUserAndChain(long userId, ChainType chain);

    /**
     * 按用户和链查询地址并对结果行加悲观锁。
     *
     * @param userId 用户 ID
     * @param chain 链类型
     * @return 加锁后的地址归属
     */
    Optional<WalletAddressAssignment> findByUserAndChainForUpdate(long userId, ChainType chain);

    /**
     * 按链和地址查询归属信息，用于链上交易归属判断。
     *
     * @param chain 链类型
     * @param address 平台地址
     * @return 地址归属记录
     */
    Optional<WalletAddressAssignment> findByChainAndAddress(ChainType chain, String address);

    /**
     * 按链加载全部已启用平台地址。
     *
     * <p>该方法用于监听器批量预载 owner 地址快照，避免按链上交易逐条查库。
     *
     * @param chain 链类型
     * @return 当前链的全部启用地址集合
     */
    Set<String> findAssignedAddressesByChain(ChainType chain);

    /**
     * 申请下一个地址索引。
     *
     * @param chain 链类型
     * @param startIndex 初始索引
     * @return 新索引
     */
    int nextAddressIndex(ChainType chain, int startIndex);

    /**
     * 保存地址分配记录。
     *
     * @param assignment 地址分配对象
     * @return 持久化后的地址分配对象
     */
    WalletAddressAssignment save(WalletAddressAssignment assignment);
}
