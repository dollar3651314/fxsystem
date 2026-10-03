package com.falconx.market.cache;

import com.falconx.market.entity.StandardQuote;
import com.falconx.market.repository.MarketLatestQuoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Redis 写入骨架实现。
 *
 * <p>当前实现只打印关键日志，用于在 Stage 2A 骨架阶段保留明确的写缓存调用点。
 * 后续接入真实 Redis 时，应在该类中替换为实际缓存写入逻辑，并继续保留日志节点。
 */
@Component
@Profile("stub")
public class LoggingMarketQuoteCacheWriter implements MarketQuoteCacheWriter {

    private static final Logger log = LoggerFactory.getLogger(LoggingMarketQuoteCacheWriter.class);
    private final MarketLatestQuoteRepository marketLatestQuoteRepository;

    public LoggingMarketQuoteCacheWriter(MarketLatestQuoteRepository marketLatestQuoteRepository) {
        this.marketLatestQuoteRepository = marketLatestQuoteRepository;
    }

    /**
     * 记录最新价写缓存动作。
     *
     * @param quote 标准报价对象
     */
    @Override
    public void writeLatestQuote(StandardQuote quote) {
        marketLatestQuoteRepository.save(quote);
        log.info("market.redis.written symbol={} quoteTs={} stale={}", quote.symbol(), quote.ts(), quote.stale());
    }
}
