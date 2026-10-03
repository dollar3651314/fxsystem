package com.falconx.wallet.repository.mapper.test;

import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;

/**
 * wallet-service 测试专用 Mapper。
 *
 * <p>该 Mapper 只在测试源码中存在，用来完成 Stage 5 集成测试的清表和结果断言。
 */
@Mapper
public interface WalletTestSupportMapper {

    default void clearOwnerTables() {
        deleteOutbox();
        deleteWalletChainCursor();
        deleteWalletDepositTransaction();
        deleteWalletAddress();
        deleteWithdrawWhitelist();
        deleteWithdrawTx();
        deleteWalletAddressProvisionDlq();
    }

    int deleteOutbox();

    int deleteWalletChainCursor();

    int deleteWalletDepositTransaction();

    int deleteWalletAddress();

    int deleteWithdrawWhitelist();

    int deleteWithdrawTx();

    int deleteWalletAddressProvisionDlq();

    Integer countWithdrawTxByWithdrawOrderId(long withdrawOrderId);

    Integer selectWithdrawTxStatusByWithdrawOrderId(long withdrawOrderId);

    String selectWithdrawTxTxHashByWithdrawOrderId(long withdrawOrderId);

    Integer countWithdrawWhitelistByUserId(long userId);

    Integer countWithdrawWhitelistByUserIdAndStatus(long userId, int status);

    Integer selectWithdrawWhitelistStatusById(long id);

    int updateWithdrawWhitelistStatus(long id, int status);

    Integer countWalletAddressByUserId(long userId);

    Integer countWalletAddressByUserIdAndChain(long userId, String chain);

    String selectWalletAddressByUserIdAndChain(long userId, String chain);

    String selectWalletAddressTokenByUserIdAndChain(long userId, String chain);

    String selectWalletAddressNetworkByUserIdAndChain(long userId, String chain);

    String selectWalletAddressDerivationPathByUserIdAndChain(long userId, String chain);

    Integer countWalletDepositByUserId(long userId);

    Integer countConfirmedDepositByTxHash(String txHash);

    Integer selectWalletDepositStatusByTxHash(String txHash);

    OffsetDateTime selectConfirmedAtByTxHash(String txHash);

    Integer countOutbox();

    Integer countOutboxByEventType(String eventType);

    Integer countOutboxByEventTypeAndWalletTxId(String eventType, long walletTxId);

    String selectLatestOutboxPayloadByEventType(String eventType);

    String selectLatestOutboxPayloadByEventTypeAndWalletTxId(String eventType, long walletTxId);
}
