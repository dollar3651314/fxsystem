package com.falconx.wallet.entity;

import com.falconx.domain.enums.ChainType;
import java.time.OffsetDateTime;

/**
 * 钱包链监听游标实体。
 *
 * <p>该对象对应 `falconx_wallet.t_wallet_chain_cursor` 的领域表达，
 * 代表某条链当前监听推进到了哪个区块、高度或 slot。
 *
 * @param id 主键 ID，由 owner 仓储在持久化时补齐
 * @param chain 链类型
 * @param cursorType 游标类型，例如 block / slot / signature
 * @param cursorValue 当前游标值
 * @param updatedAt 最近更新时间
 */
public record WalletChainCursor(
        Long id,
        ChainType chain,
        String cursorType,
        String cursorValue,
        OffsetDateTime updatedAt
) {
}
