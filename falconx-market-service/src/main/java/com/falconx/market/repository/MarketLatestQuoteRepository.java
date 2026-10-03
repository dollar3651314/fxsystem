package com.falconx.market.repository;

import com.falconx.market.entity.StandardQuote;
import java.util.Optional;

/**
 * 市场最新报价读模型仓储接口。
 *
 * <p>该仓储用于承接 `market-service` 内部最新报价查询场景，
 * 让北向查询接口无需直接依赖 Redis 即可在骨架阶段形成稳定调用链。
 */
public interface MarketLatestQuoteRepository {

    /**
     * 保存最新报价。
     *
     * @param quote 标准报价对象
     */
    void save(StandardQuote quote);

    /**
     * 按品种查询最新报价。
     *
     * @param symbol 品种代码
     * @return 最新报价
     */
    Optional<StandardQuote> findBySymbol(String symbol);

    /**
     * 批量按品种查询最新报价（Redis pipeline 1 RTT）。
     *
     * <p>性能背景：北向 GET /api/v1/market/symbols 一次返回 1571 行，若串行 findBySymbol
     * 触发 1571 个 Redis HGETALL 同步阻塞 ≈ 8-11s（dev 实测）。pipeline 批量后理论 < 100ms。
     *
     * @param symbols 品种代码列表
     * @return symbol → StandardQuote 映射；命中即返回，未命中不出现在 map
     */
    java.util.Map<String, StandardQuote> findBySymbols(java.util.List<String> symbols);
}
