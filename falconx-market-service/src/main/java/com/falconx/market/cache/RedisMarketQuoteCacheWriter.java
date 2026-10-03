package com.falconx.market.cache;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.repository.MarketLatestQuoteRepository;
import com.falconx.market.repository.MarketReferenceQuoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 最新价缓存真实写入实现。
 *
 * <p>TTL 由最新价仓储统一设置，避免同一热路径重复写 Redis 过期时间。
 */
@Component
@Profile("!stub")
public class RedisMarketQuoteCacheWriter implements MarketQuoteCacheWriter {

    private static final Logger log = LoggerFactory.getLogger(RedisMarketQuoteCacheWriter.class);

    private final MarketLatestQuoteRepository marketLatestQuoteRepository;
    private final MarketReferenceQuoteRepository marketReferenceQuoteRepository;

    public RedisMarketQuoteCacheWriter(MarketLatestQuoteRepository marketLatestQuoteRepository,
                                       MarketReferenceQuoteRepository marketReferenceQuoteRepository,
                                       StringRedisTemplate stringRedisTemplate,
                                       MarketServiceProperties properties) {
        this.marketLatestQuoteRepository = marketLatestQuoteRepository;
        this.marketReferenceQuoteRepository = marketReferenceQuoteRepository;
    }

    @Override
    public void writeLatestQuote(StandardQuote quote) {
        marketLatestQuoteRepository.save(quote);
        if (!quote.stale()) {
            marketReferenceQuoteRepository.saveLastValid(quote);
        }
        log.debug("market.redis.written symbol={} quoteTs={} stale={}", quote.symbol(), quote.ts(), quote.stale());
    }
}
