package com.falconx.market.contract;

import java.math.BigDecimal;

/**
 * Platform symbol 的完整可交易规格。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 引入：market 暴露给 trading-core 的运行时
 * symbol 配置真源。所有交易参数取自 {@code t_symbol_quote_mapping}；
 * V15 起 precision 也取自 {@code t_symbol_quote_mapping} 的系统级配置。
 *
 * <p>持久化通道为 Redis Hash {@code falconx:market:symbol-spec:{platformSymbol}}；
 * market 在 mapping CRUD 的 {@code afterCommit} 触发刷新，trading-core 直接消费此快照，
 * 禁止跨 schema 直查 {@code falconx_market.t_symbol_quote_mapping}。
 *
 * <p>STAGE-14B Task 5 追加 {@code baseCurrency} / {@code quoteCurrency}（来源 {@code t_symbol}），
 * 供 trading-core 算法层（Task 6-9）识别每个 symbol 的计价币与基础币。两字段追加在末尾以
 * 最小化既有组件顺序扰动；跨服务兼容（AGENTS §3.8）：market 未重发布前的旧 JSON 快照不含这两个
 * 字段，trading-core 反序列化为 null（Jackson 3 缺省行为），market 重发后自然补齐。
 *
 * <p>STAGE-14C2 Task 1 追加 {@code category}（来源 {@code t_symbol.category}），供 trading-core
 * 在 FX_PAUSED / GLOBAL_PAUSE 下按品种类目（{@code t_fx_pause_behavior}）控制开仓与被动强平。
 * 同 14B 口径追加在末尾以最小化既有组件顺序扰动；跨服务兼容（AGENTS §3.8）：market 未重发布前的
 * 旧 JSON 快照不含 category，trading-core 反序列化为 null（Jackson 3 缺省行为），消费方按类目降级
 * （Task 6 处理 null → allow_all），market 重发后自然补齐。
 *
 * @param baseCurrency 基础币代码（如 BTC / EUR），过渡期旧快照可能为 null
 * @param quoteCurrency 计价币代码（如 USDT / USD），过渡期旧快照可能为 null
 * @param category 品种类目编码 1-8（1=crypto/2=forex/3=metal/4=index/5=energy/6=stock/7=etf/8=other），过渡期旧快照可能为 null
 */
public record SymbolSpec(
        String platformSymbol,
        Integer maxLeverage,
        BigDecimal takerFeeRate,
        BigDecimal spread,
        BigDecimal minQty,
        BigDecimal maxQty,
        BigDecimal minNotional,
        Integer pricePrecision,
        Integer qtyPrecision,
        String baseCurrency,
        String quoteCurrency,
        Integer category
) {
}
