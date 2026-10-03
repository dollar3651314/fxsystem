package com.falconx.wallet.repository;

import com.falconx.wallet.entity.WalletWithdrawWhitelist;
import java.util.List;
import java.util.Optional;

/**
 * STAGE-7-WITHDRAW：出金白名单仓储。
 */
public interface WalletWithdrawWhitelistRepository {

    void insert(WalletWithdrawWhitelist whitelist);

    Optional<WalletWithdrawWhitelist> findById(Long id);

    List<WalletWithdrawWhitelist> findActiveByUserId(Long userId);

    int countActiveByUserId(Long userId);

    Optional<WalletWithdrawWhitelist> findByUserNetworkAddressActive(Long userId, String network, String address);

    /** STAGE-7-WITHDRAW Phase 2：删除（status → REMOVED）。 */
    int markRemoved(Long id);

    /**
     * STAGE-7-WITHDRAW Phase 2：扫描已过 24h 冷静期的 PENDING 记录。
     *
     * @param threshold 阈值时刻（已减去冷静期长度）；created_at &lt;= threshold 表示已满 24h
     */
    List<WalletWithdrawWhitelist> findExpiredPendingWhitelists(java.time.OffsetDateTime threshold, int limit);

    /**
     * STAGE-7-WITHDRAW Phase 2：CAS 把 PENDING(0) 推进到 ACTIVE(1) + 写 activated_at。
     */
    int markActiveFromPendingAtomic(Long id, java.time.OffsetDateTime activatedAt);
}
