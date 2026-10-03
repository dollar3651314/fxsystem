package com.falconx.market.repository;

import com.falconx.market.entity.MarketSymbol;
import com.falconx.market.entity.MarketSymbolWithSpec;
import java.util.List;
import java.util.Optional;

/**
 * 市场品种仓储。
 *
 * <p>该仓储负责 `t_symbol` 的 owner 读写，
 * 并通过 `t_symbol_quote_mapping` 与 `t_symbol_group_visibility` 查询用户可见的平台 symbol。
 * 用于交易时间快照预热、品种元数据查询以及受控 symbol 追加。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 后：涉及交易参数（杠杆 / 费率 / 点差 / qty 限制）
 * 的查询返回 {@link MarketSymbolWithSpec}（含 mapping JOIN）；仅元数据的查询继续返回
 * {@link MarketSymbol}。
 */
public interface MarketSymbolRepository {

    /**
     * 查询全部可交易 LP 源元数据（不含交易参数）。
     */
    List<MarketSymbol> findAllTradingSymbols();

    /**
     * 查询指定用户组可见的可交易平台品种，含 mapping 交易参数。
     *
     * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 起返回类型从 {@code MarketSymbol} 改为
     * {@link MarketSymbolWithSpec}：杠杆 / 费率 / 点差 / qty 限制取自 mapping。
     */
    List<MarketSymbolWithSpec> findTradingSymbolsByGroupCode(String groupCode);

    /**
     * 按 source symbol 查询 LP 源元数据。
     */
    Optional<MarketSymbol> findBySymbol(String symbol);

    /**
     * 按用户组可见性查询平台品种，含 mapping 交易参数。
     *
     * <p>返回对象的 {@code symbol} 字段是平台展示 symbol；交易参数取自 mapping。
     */
    Optional<MarketSymbolWithSpec> findVisibleTradingSymbol(String symbol, String groupCode);

    /**
     * 只追加不存在的 LP 源 symbol。
     */
    int appendIfAbsent(List<MarketSymbol> symbols);
}
