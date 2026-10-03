package com.falconx.trading.repository;

import com.falconx.market.contract.SymbolSpec;
import java.util.Optional;

/**
 * Trading-core 消费 market 暴露的 SymbolSpec 共享快照。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.2 引入。Trading-core 通过 Redis Hash
 * {@code falconx:market:symbol-spec:{platformSymbol}} 高频读，
 * 禁止跨 schema 直查 {@code falconx_market.t_symbol_quote_mapping}（owner 边界硬约束）。
 */
public interface MarketSymbolSpecRepository {

    Optional<SymbolSpec> findByPlatformSymbol(String platformSymbol);
}
