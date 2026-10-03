package com.falconx.market.repository;

import com.falconx.market.config.MarketServiceProperties;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisMarketReferenceQuoteRepositoryTests {

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnEmptyWhenHistoryFallbackFails() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        MarketQuoteHistoryRepository historyRepository = Mockito.mock(MarketQuoteHistoryRepository.class);
        RedisMarketReferenceQuoteRepository repository = new RedisMarketReferenceQuoteRepository(
                redisTemplate,
                historyRepository,
                new MarketServiceProperties()
        );

        Mockito.when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        Mockito.when(hashOperations.entries("falconx:market:last-valid-price:XAUUSD")).thenReturn(Map.of());
        Mockito.when(historyRepository.findLatestBySymbol("XAUUSD"))
                .thenThrow(new IllegalStateException("clickhouse unavailable"));

        Assertions.assertTrue(repository.findBySymbol("XAUUSD").isEmpty());
        Mockito.verify(historyRepository).findLatestBySymbol("XAUUSD");
    }
}
