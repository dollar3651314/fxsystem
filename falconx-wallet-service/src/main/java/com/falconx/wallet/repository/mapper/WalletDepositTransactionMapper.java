package com.falconx.wallet.repository.mapper;

import com.falconx.wallet.repository.mapper.record.WalletDepositTransactionRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 原始链上入金 MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `t_wallet_deposit_tx` 的 SQL 声明。
 */
@Mapper
public interface WalletDepositTransactionMapper {

    WalletDepositTransactionRecord selectByChainAndTxHashAndLogIndex(@Param("chain") String chain,
                                                                     @Param("txHash") String txHash,
                                                                     @Param("logIndex") Integer logIndex);

    List<WalletDepositTransactionRecord> selectByChainAndBlockRange(@Param("chain") String chain,
                                                                    @Param("fromBlock") Long fromBlock,
                                                                    @Param("toBlock") Long toBlock);

    int insertWalletDepositTransaction(WalletDepositTransactionRecord record);

    int updateWalletDepositTransaction(WalletDepositTransactionRecord record);

    /**
     * STAGE-2-DEPOSIT R4：管理端多条件分页查询。
     *
     * @param onlyOrphan true 时强制 user_id IS NULL
     */
    List<WalletDepositTransactionRecord> selectAdminPaginated(@Param("userId") Long userId,
                                                              @Param("chain") String chain,
                                                              @Param("token") String token,
                                                              @Param("statusCodes") List<Integer> statusCodes,
                                                              @Param("fromDetectedAt") LocalDateTime fromDetectedAt,
                                                              @Param("toDetectedAt") LocalDateTime toDetectedAt,
                                                              @Param("onlyOrphan") boolean onlyOrphan,
                                                              @Param("offset") int offset,
                                                              @Param("limit") int limit);

    /**
     * STAGE-2-DEPOSIT R4：管理端多条件计数。
     */
    long countAdminFiltered(@Param("userId") Long userId,
                            @Param("chain") String chain,
                            @Param("token") String token,
                            @Param("statusCodes") List<Integer> statusCodes,
                            @Param("fromDetectedAt") LocalDateTime fromDetectedAt,
                            @Param("toDetectedAt") LocalDateTime toDetectedAt,
                            @Param("onlyOrphan") boolean onlyOrphan);

    /**
     * STAGE-2-DEPOSIT R4：按 id 查询。
     */
    WalletDepositTransactionRecord selectById(@Param("id") long id);

    /**
     * STAGE-6-KYC trigger 2：用户在指定链上历史 CONFIRMED 入金的去重 from_address 集合。
     * 用作出金前置「目标地址 ∉ 历史入金来源地址 → 拒绝陌生地址 / 强制 KYC」判定。
     */
    List<String> selectDistinctFromAddressesByUserAndChain(@Param("userId") long userId,
                                                            @Param("chain") String chain);
}
