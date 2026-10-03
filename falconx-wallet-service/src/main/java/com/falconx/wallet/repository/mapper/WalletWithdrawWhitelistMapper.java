package com.falconx.wallet.repository.mapper;

import com.falconx.wallet.repository.mapper.record.WalletWithdrawWhitelistRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * STAGE-7-WITHDRAW：出金白名单 Mapper。
 */
@Mapper
public interface WalletWithdrawWhitelistMapper {

    int insert(WalletWithdrawWhitelistRecord record);

    WalletWithdrawWhitelistRecord selectById(@Param("id") Long id);

    /**
     * 用户可见白名单（非 REMOVED）。
     */
    List<WalletWithdrawWhitelistRecord> selectActiveByUserId(@Param("userId") Long userId);

    int countActiveByUserId(@Param("userId") Long userId);

    /**
     * 检查同 user_id + network + address 是否存在 PENDING 或 ACTIVE 记录。
     */
    WalletWithdrawWhitelistRecord selectByUserNetworkAddressActive(@Param("userId") Long userId,
                                                                    @Param("network") String network,
                                                                    @Param("address") String address);

    /**
     * STAGE-7-WITHDRAW Phase 2：把 PENDING/ACTIVE 切到 REMOVED。
     */
    int markRemoved(@Param("id") Long id);

    /**
     * STAGE-7-WITHDRAW Phase 2：扫描已过 24h 冷静期的 PENDING 记录。
     */
    java.util.List<WalletWithdrawWhitelistRecord> selectExpiredPendingWhitelists(
            @Param("threshold") java.time.LocalDateTime threshold,
            @Param("limit") int limit);

    /**
     * STAGE-7-WITHDRAW Phase 2：CAS 把 PENDING(0) 推进到 ACTIVE(1) + 写 activated_at。
     */
    int markActiveFromPendingAtomic(@Param("id") Long id,
                                     @Param("activatedAt") java.time.LocalDateTime activatedAt);
}
