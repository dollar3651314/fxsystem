package com.falconx.wallet.repository.mapper;

import com.falconx.wallet.repository.mapper.record.WalletAddressRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 钱包地址 MyBatis Mapper。
 *
 * <p>该 Mapper 只负责 `t_wallet_address` 的 SQL 声明，领域语义转换由 Repository 完成。
 */
@Mapper
public interface WalletAddressMapper {

    WalletAddressRecord selectByUserAndChain(@Param("userId") Long userId, @Param("chain") String chain);

    WalletAddressRecord selectByUserAndChainForUpdate(@Param("userId") Long userId, @Param("chain") String chain);

    WalletAddressRecord selectByChainAndAddress(@Param("chain") String chain, @Param("address") String address);

    List<String> selectAssignedAddressesByChain(@Param("chain") String chain, @Param("status") Integer status);

    Integer selectLastAddressIndexByChainForUpdate(@Param("chain") String chain);

    int insertWalletAddress(WalletAddressRecord record);

    int updateWalletAddress(WalletAddressRecord record);
}
