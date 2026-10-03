package com.falconx.wallet.repository;

import com.falconx.domain.enums.ChainType;
import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.wallet.entity.WalletChainCursor;
import com.falconx.wallet.repository.mapper.WalletChainCursorMapper;
import com.falconx.wallet.repository.mapper.record.WalletChainCursorRecord;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Repository;

/**
 * 链监听游标 Repository 的 MyBatis 实现。
 *
 * <p>该实现把各链扫描游标真正落到 `t_wallet_chain_cursor`，保证服务重启后仍能续扫。
 */
@Repository
public class MybatisWalletChainCursorRepository implements WalletChainCursorRepository {

    private final WalletChainCursorMapper walletChainCursorMapper;
    private final IdGenerator idGenerator;

    public MybatisWalletChainCursorRepository(WalletChainCursorMapper walletChainCursorMapper, IdGenerator idGenerator) {
        this.walletChainCursorMapper = walletChainCursorMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public WalletChainCursor findByChain(ChainType chain) {
        return toDomain(walletChainCursorMapper.selectByChain(WalletMybatisSupport.toChainValue(chain)));
    }

    @Override
    public WalletChainCursor initializeIfAbsent(ChainType chain, String cursorType, String initialValue) {
        WalletChainCursor existing = findByChain(chain);
        if (existing != null) {
            return existing;
        }

        WalletChainCursor cursor = new WalletChainCursor(
                idGenerator.nextId(),
                chain,
                cursorType,
                initialValue,
                OffsetDateTime.now()
        );
        walletChainCursorMapper.insertWalletChainCursor(toRecord(cursor));
        return cursor;
    }

    @Override
    public WalletChainCursor updateCursor(ChainType chain, String cursorValue) {
        OffsetDateTime updatedAt = OffsetDateTime.now();
        walletChainCursorMapper.updateCursorValueByChain(
                WalletMybatisSupport.toChainValue(chain),
                cursorValue,
                WalletMybatisSupport.toLocalDateTime(updatedAt)
        );
        return toDomain(walletChainCursorMapper.selectByChain(WalletMybatisSupport.toChainValue(chain)));
    }

    private WalletChainCursorRecord toRecord(WalletChainCursor cursor) {
        return new WalletChainCursorRecord(
                cursor.id(),
                WalletMybatisSupport.toChainValue(cursor.chain()),
                cursor.cursorType(),
                cursor.cursorValue(),
                WalletMybatisSupport.toLocalDateTime(cursor.updatedAt())
        );
    }

    private WalletChainCursor toDomain(WalletChainCursorRecord record) {
        if (record == null) {
            return null;
        }
        return new WalletChainCursor(
                record.id(),
                WalletMybatisSupport.toChainType(record.chain()),
                record.cursorType(),
                record.cursorValue(),
                WalletMybatisSupport.toOffsetDateTime(record.updatedAt())
        );
    }
}
