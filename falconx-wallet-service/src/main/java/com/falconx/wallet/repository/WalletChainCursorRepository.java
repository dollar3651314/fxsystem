package com.falconx.wallet.repository;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.entity.WalletChainCursor;

/**
 * 钱包链监听游标仓储抽象。
 *
 * <p>该仓储负责保存每条链当前的监听推进位置，
 * 对应 `t_wallet_chain_cursor` 的 owner 读写责任。
 */
public interface WalletChainCursorRepository {

    /**
     * 按链查询当前游标。
     *
     * @param chain 链类型
     * @return 当前链游标，若不存在则为空
     */
    WalletChainCursor findByChain(ChainType chain);

    /**
     * 若游标不存在则初始化。
     *
     * @param chain 链类型
     * @param cursorType 游标类型
     * @param initialValue 初始游标值
     * @return 当前游标对象
     */
    WalletChainCursor initializeIfAbsent(ChainType chain, String cursorType, String initialValue);

    /**
     * 更新游标值。
     *
     * @param chain 链类型
     * @param cursorValue 新游标值
     * @return 更新后的游标
     */
    WalletChainCursor updateCursor(ChainType chain, String cursorValue);
}
