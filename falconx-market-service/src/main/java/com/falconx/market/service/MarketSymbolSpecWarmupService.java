package com.falconx.market.service;

/**
 * SymbolSpec 共享快照预热服务。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 引入：把 {@code t_symbol_quote_mapping} 的
 * 交易参数（杠杆 / 费率 / 点差 / qty 限制 / 系统级 precision）写入 Redis Hash
 * {@code falconx:market:symbol-spec:{platformSymbol}}，供 trading-core 高频读。
 *
 * <p>刷新时机：服务启动 ApplicationRunner + mapping CRUD afterCommit。
 */
public interface MarketSymbolSpecWarmupService {

    /**
     * 从 owner MySQL 全量刷新 Redis SymbolSpec 快照。
     */
    void refreshAll();

    /**
     * 刷新单个 platform symbol 的 SymbolSpec 快照（mapping CRUD afterCommit 时用）。
     */
    void refresh(String platformSymbol);
}
