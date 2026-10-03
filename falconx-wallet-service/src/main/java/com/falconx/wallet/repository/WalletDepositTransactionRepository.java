package com.falconx.wallet.repository;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.entity.WalletDepositTransaction;
import java.util.List;
import java.util.Optional;

/**
 * 钱包原始入金交易仓储抽象。
 *
 * <p>该仓储负责原始链交易的去重与状态落地，
 * 对应 `t_wallet_deposit_tx` 的 owner 读写责任。
 */
public interface WalletDepositTransactionRepository {

    /**
     * 按链、交易哈希和日志索引查询已存在交易。
     *
     * @param chain 链类型
     * @param txHash 交易哈希
     * @param logIndex 日志索引；原生币固定为 0
     * @return 已落地的交易记录
     */
    Optional<WalletDepositTransaction> findByChainAndTxHashAndLogIndex(ChainType chain, String txHash, int logIndex);

    /**
     * 按链和区块范围加载已落地的原始交易。
     *
     * <p>该查询只服务 wallet owner 自己的监听补偿和回滚识别，
     * 允许 listener 在确认窗口内一次性取回已跟踪交易，再与当前链上扫描结果做对账，
     * 避免逐笔按交易哈希回查。
     *
     * @param chain 链类型
     * @param fromBlock 起始区块（含）
     * @param toBlock 结束区块（含）
     * @return 区块范围内已落地的原始交易
     */
    List<WalletDepositTransaction> findByChainAndBlockRange(ChainType chain, long fromBlock, long toBlock);

    /**
     * 保存或覆盖交易记录。
     *
     * @param transaction 原始交易对象
     * @return 持久化后的交易对象
     */
    WalletDepositTransaction save(WalletDepositTransaction transaction);

    /**
     * STAGE-2-DEPOSIT R4：管理端多条件分页查询。
     *
     * @param statuses 状态枚举集合；空 / null 表示不限
     * @param onlyOrphan true 时只返回 user_id IS NULL（忽略 userId 参数）
     */
    List<WalletDepositTransaction> findAdminPaginated(Long userId, ChainType chain, String token,
                                                     List<com.falconx.wallet.entity.WalletDepositStatus> statuses,
                                                     java.time.OffsetDateTime fromDetectedAt,
                                                     java.time.OffsetDateTime toDetectedAt,
                                                     boolean onlyOrphan,
                                                     int offset, int limit);

    /**
     * STAGE-2-DEPOSIT R4：管理端多条件计数。
     */
    long countAdminFiltered(Long userId, ChainType chain, String token,
                            List<com.falconx.wallet.entity.WalletDepositStatus> statuses,
                            java.time.OffsetDateTime fromDetectedAt,
                            java.time.OffsetDateTime toDetectedAt,
                            boolean onlyOrphan);

    /**
     * STAGE-2-DEPOSIT R4：按 id 查询。
     */
    Optional<WalletDepositTransaction> findById(long id);

    /**
     * STAGE-6-KYC trigger 2：用户在指定链上历史 CONFIRMED 入金的去重 from_address 集合。
     * 用作出金前置陌生地址校验「目标地址 ∉ 该集合 → 拒绝 / 触发 KYC」。
     */
    List<String> findDistinctFromAddressesByUserAndChain(long userId, ChainType chain);
}
