package com.falconx.wallet.repository;

import com.falconx.wallet.entity.WalletWithdrawWhitelist;
import com.falconx.wallet.entity.WalletWithdrawWhitelistStatus;
import com.falconx.wallet.repository.mapper.WalletWithdrawWhitelistMapper;
import com.falconx.wallet.repository.mapper.record.WalletWithdrawWhitelistRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * STAGE-7-WITHDRAW：出金白名单仓储 MyBatis 实现。
 */
@Repository
public class MybatisWalletWithdrawWhitelistRepository implements WalletWithdrawWhitelistRepository {

    private final WalletWithdrawWhitelistMapper mapper;

    public MybatisWalletWithdrawWhitelistRepository(WalletWithdrawWhitelistMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(WalletWithdrawWhitelist whitelist) {
        WalletWithdrawWhitelistRecord record = new WalletWithdrawWhitelistRecord(
                whitelist.id(),
                whitelist.userId(),
                whitelist.network(),
                whitelist.address(),
                whitelist.label(),
                whitelist.status().code(),
                WalletMybatisSupport.toLocalDateTime(whitelist.activatedAt()),
                WalletMybatisSupport.toLocalDateTime(whitelist.removedAt()),
                WalletMybatisSupport.toLocalDateTime(whitelist.createdAt()),
                WalletMybatisSupport.toLocalDateTime(whitelist.updatedAt())
        );
        mapper.insert(record);
    }

    @Override
    public Optional<WalletWithdrawWhitelist> findById(Long id) {
        return Optional.ofNullable(toDomain(mapper.selectById(id)));
    }

    @Override
    public List<WalletWithdrawWhitelist> findActiveByUserId(Long userId) {
        return mapper.selectActiveByUserId(userId).stream().map(this::toDomain).toList();
    }

    @Override
    public int countActiveByUserId(Long userId) {
        return mapper.countActiveByUserId(userId);
    }

    @Override
    public Optional<WalletWithdrawWhitelist> findByUserNetworkAddressActive(Long userId, String network, String address) {
        return Optional.ofNullable(toDomain(mapper.selectByUserNetworkAddressActive(userId, network, address)));
    }

    @Override
    public int markRemoved(Long id) {
        return mapper.markRemoved(id);
    }

    @Override
    public List<WalletWithdrawWhitelist> findExpiredPendingWhitelists(java.time.OffsetDateTime threshold, int limit) {
        return mapper.selectExpiredPendingWhitelists(WalletMybatisSupport.toLocalDateTime(threshold), limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public int markActiveFromPendingAtomic(Long id, java.time.OffsetDateTime activatedAt) {
        return mapper.markActiveFromPendingAtomic(id, WalletMybatisSupport.toLocalDateTime(activatedAt));
    }

    private WalletWithdrawWhitelist toDomain(WalletWithdrawWhitelistRecord r) {
        if (r == null) return null;
        return new WalletWithdrawWhitelist(
                r.id(),
                r.userId(),
                r.network(),
                r.address(),
                r.label(),
                WalletWithdrawWhitelistStatus.fromCode(r.status()),
                WalletMybatisSupport.toOffsetDateTime(r.activatedAt()),
                WalletMybatisSupport.toOffsetDateTime(r.removedAt()),
                WalletMybatisSupport.toOffsetDateTime(r.createdAt()),
                WalletMybatisSupport.toOffsetDateTime(r.updatedAt())
        );
    }
}
