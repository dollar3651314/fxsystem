package com.falconx.wallet.repository;

import com.falconx.domain.enums.ChainType;
import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.wallet.entity.WalletAddressAssignment;
import com.falconx.wallet.repository.mapper.WalletAddressMapper;
import com.falconx.wallet.repository.mapper.record.WalletAddressRecord;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;

/**
 * 钱包地址 Repository 的 MyBatis 实现。
 *
 * <p>该实现负责把地址分配对象落到 `t_wallet_address`，并通过 XML SQL 完成 owner 数据读写。
 */
@Repository
public class MybatisWalletAddressRepository implements WalletAddressRepository {

    private final WalletAddressMapper walletAddressMapper;
    private final IdGenerator idGenerator;

    public MybatisWalletAddressRepository(WalletAddressMapper walletAddressMapper, IdGenerator idGenerator) {
        this.walletAddressMapper = walletAddressMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public Optional<WalletAddressAssignment> findByUserAndChain(long userId, ChainType chain) {
        return Optional.ofNullable(toDomain(
                walletAddressMapper.selectByUserAndChain(userId, WalletMybatisSupport.toChainValue(chain))
        ));
    }

    @Override
    public Optional<WalletAddressAssignment> findByUserAndChainForUpdate(long userId, ChainType chain) {
        return Optional.ofNullable(toDomain(
                walletAddressMapper.selectByUserAndChainForUpdate(userId, WalletMybatisSupport.toChainValue(chain))
        ));
    }

    @Override
    public Optional<WalletAddressAssignment> findByChainAndAddress(ChainType chain, String address) {
        return Optional.ofNullable(toDomain(
                walletAddressMapper.selectByChainAndAddress(WalletMybatisSupport.toChainValue(chain), address)
        ));
    }

    @Override
    public Set<String> findAssignedAddressesByChain(ChainType chain) {
        return new LinkedHashSet<>(walletAddressMapper.selectAssignedAddressesByChain(
                WalletMybatisSupport.toChainValue(chain),
                WalletMybatisSupport.toAddressStatusCode(com.falconx.wallet.entity.WalletAddressStatus.ASSIGNED)
        ).stream().map(address -> normalizeAddress(chain, address)).toList());
    }

    @Override
    public int nextAddressIndex(ChainType chain, int startIndex) {
        Integer currentLast = walletAddressMapper.selectLastAddressIndexByChainForUpdate(WalletMybatisSupport.toChainValue(chain));
        if (currentLast == null) {
            return startIndex;
        }
        return currentLast + 1;
    }

    @Override
    public WalletAddressAssignment save(WalletAddressAssignment assignment) {
        if (assignment.id() == null) {
            OffsetDateTime assignedAt = assignment.assignedAt() == null ? OffsetDateTime.now() : assignment.assignedAt();
            WalletAddressAssignment persisted = new WalletAddressAssignment(
                    idGenerator.nextId(),
                    assignment.userId(),
                    assignment.chain(),
                    assignment.token(),
                    assignment.network(),
                    assignment.address(),
                    assignment.addressIndex(),
                    assignment.derivationPath(),
                    assignment.status(),
                    assignedAt
            );
            walletAddressMapper.insertWalletAddress(toRecord(persisted));
            return persisted;
        }

        WalletAddressAssignment persisted = new WalletAddressAssignment(
                assignment.id(),
                assignment.userId(),
                assignment.chain(),
                assignment.token(),
                assignment.network(),
                assignment.address(),
                assignment.addressIndex(),
                assignment.derivationPath(),
                assignment.status(),
                assignment.assignedAt()
        );
        walletAddressMapper.updateWalletAddress(toRecord(persisted));
        return persisted;
    }

    private WalletAddressRecord toRecord(WalletAddressAssignment assignment) {
        OffsetDateTime assignedAt = assignment.assignedAt() == null ? OffsetDateTime.now() : assignment.assignedAt();
        return new WalletAddressRecord(
                assignment.id(),
                assignment.userId(),
                WalletMybatisSupport.toChainValue(assignment.chain()),
                assignment.token(),
                assignment.network(),
                assignment.address(),
                assignment.addressIndex(),
                assignment.derivationPath(),
                WalletMybatisSupport.toAddressStatusCode(assignment.status()),
                WalletMybatisSupport.toLocalDateTime(assignedAt),
                WalletMybatisSupport.toLocalDateTime(assignedAt),
                WalletMybatisSupport.toLocalDateTime(OffsetDateTime.now())
        );
    }

    private WalletAddressAssignment toDomain(WalletAddressRecord record) {
        if (record == null) {
            return null;
        }
        return new WalletAddressAssignment(
                record.id(),
                record.userId(),
                WalletMybatisSupport.toChainType(record.chain()),
                record.token(),
                record.network(),
                record.address(),
                record.addressIndex(),
                record.derivationPath(),
                WalletMybatisSupport.toAddressStatus(record.statusCode()),
                WalletMybatisSupport.toOffsetDateTime(record.assignedAt())
        );
    }

    private String normalizeAddress(ChainType chain, String address) {
        if (address == null || chain == ChainType.TRON) {
            return address;
        }
        return address.toLowerCase(Locale.ROOT);
    }
}
