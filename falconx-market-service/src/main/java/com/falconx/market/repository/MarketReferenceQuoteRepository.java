package com.falconx.market.repository;

import com.falconx.market.entity.StandardQuote;
import java.util.Optional;

/**
 * 展示用最后有效参考价仓储。
 *
 * <p>该仓储不参与成交、平仓、TP/SL 或强平链路，只为首页列表和休盘展示提供参考价格。
 */
public interface MarketReferenceQuoteRepository {

    /**
     * 保存最后有效参考价。
     *
     * @param quote fresh 标准报价
     */
    void saveLastValid(StandardQuote quote);

    /**
     * 查询最后有效参考价。
     *
     * @param symbol 品种代码
     * @return 参考报价
     */
    Optional<StandardQuote> findBySymbol(String symbol);

    /**
     * 批量查询最后有效参考价（首屏 fast path：仅 Redis pipeline，不回查 ClickHouse）。
     *
     * <p>性能背景：1571 symbols 中 ~1462 个无 latest，需 fallback reference；
     * 若 fallback 走单查 + ClickHouse 回填，1462 × 50ms+ ≈ 70s+，不可用。
     * 批量接口走 pipeline 一次 RTT 返所有命中；未命中视 MISSING 不阻塞首屏；
     * ClickHouse 回填由后台 warmup job 或单 symbol 详情接口承担。
     *
     * @param symbols 品种代码列表
     * @return symbol → StandardQuote 命中映射
     */
    java.util.Map<String, StandardQuote> findBySymbols(java.util.List<String> symbols);
}
