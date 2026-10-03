package com.falconx.market.cache;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketQuoteQualityStatus;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.repository.MarketLatestQuoteRepository;
import com.falconx.market.repository.MarketReferenceQuoteRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 报价写入热路径测试。
 */
class RedisMarketQuoteCacheWriterTests {

    @Test
    void shouldDelegateQuoteTtlToLatestQuoteRepositoryWithoutDuplicateExpire() {
        MarketLatestQuoteRepository latestQuoteRepository = mock(MarketLatestQuoteRepository.class);
        MarketReferenceQuoteRepository referenceQuoteRepository = mock(MarketReferenceQuoteRepository.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        MarketServiceProperties properties = new MarketServiceProperties();
        RedisMarketQuoteCacheWriter writer = new RedisMarketQuoteCacheWriter(
                latestQuoteRepository,
                referenceQuoteRepository,
                stringRedisTemplate,
                properties
        );
        StandardQuote quote = new StandardQuote(
                "EURUSD",
                new BigDecimal("1.0800"),
                new BigDecimal("1.0802"),
                new BigDecimal("1.0801"),
                new BigDecimal("1.0801"),
                OffsetDateTime.parse("2026-05-25T08:00:00Z"),
                "unit-test",
                false,
                MarketQuoteQualityStatus.FRESH,
                null
        );

        writer.writeLatestQuote(quote);

        verify(latestQuoteRepository).save(quote);
        verify(referenceQuoteRepository).saveLastValid(quote);
        verify(stringRedisTemplate, never()).expire("falconx:market:price:EURUSD", properties.getRedis().getQuoteTtl());
    }
}
