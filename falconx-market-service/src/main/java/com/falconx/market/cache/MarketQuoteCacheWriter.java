package com.falconx.market.cache;

import com.falconx.market.entity.StandardQuote;

/**
 * Redis 行情缓存写入抽象。
 *
 * <p>当前阶段先冻结写缓存职责，不直接接入 Redis 客户端。
 * 后续实现必须遵循市场数据契约中的 key 结构和 TTL 策略。
 */
public interface MarketQuoteCacheWriter {

    /**
     * 写入最新价缓存。
     *
     * @param quote 标准报价对象
     */
    void writeLatestQuote(StandardQuote quote);
}
