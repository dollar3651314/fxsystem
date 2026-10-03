package com.falconx.wallet.repository.mapper;

import com.falconx.wallet.repository.mapper.record.WalletChainCursorRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 链监听游标 MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `t_wallet_chain_cursor` 的 SQL 声明。
 */
@Mapper
public interface WalletChainCursorMapper {

    WalletChainCursorRecord selectByChain(@Param("chain") String chain);

    int insertWalletChainCursor(WalletChainCursorRecord record);

    int updateCursorValueByChain(@Param("chain") String chain,
                                 @Param("cursorValue") String cursorValue,
                                 @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
