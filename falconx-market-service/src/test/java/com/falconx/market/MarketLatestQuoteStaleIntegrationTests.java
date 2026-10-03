package com.falconx.market;

import com.falconx.market.entity.StandardQuote;
import com.falconx.market.repository.MarketLatestQuoteRepository;
import com.falconx.market.repository.MarketReferenceQuoteRepository;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * 最新报价 stale 语义集成测试。
 *
 * <p>该测试专门锁定“stale 必须在读取时按当前时间动态计算”的规则，
 * 防止后续又退回成“写入时计算一次、之后永久沿用”的静态布尔值实现。
 */
@ActiveProfiles("stage5")
@ContextConfiguration(initializers = MarketTestDatabaseInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {
                MarketServiceApplication.class,
                MarketMybatisTestSupportConfiguration.class
        },
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_market_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "falconx.market.redis.quote-ttl=2s",
                "falconx.market.redis.reference-quote-ttl=30s",
                "falconx.market.stale.max-age=100ms",
                "falconx.market.analytics.jdbc-url=jdbc:clickhouse://localhost:8123/falconx_market_analytics",
                "falconx.market.analytics.username=default",
                "falconx.market.analytics.password=falconx"
        }
)
class MarketLatestQuoteStaleIntegrationTests {

    @Autowired
    private MarketLatestQuoteRepository marketLatestQuoteRepository;

    @Autowired
    private MarketReferenceQuoteRepository marketReferenceQuoteRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void clearLatestQuote() {
        stringRedisTemplate.delete("falconx:market:price:EURUSD");
        stringRedisTemplate.delete("falconx:market:last-valid-price:EURUSD");
    }

    @Test
    void shouldMarkQuoteStaleWhenReadHappensAfterMaxAge() throws InterruptedException {
        marketLatestQuoteRepository.save(new StandardQuote(
                "EURUSD",
                new BigDecimal("1.08100000"),
                new BigDecimal("1.08120000"),
                new BigDecimal("1.08110000"),
                new BigDecimal("1.08110000"),
                OffsetDateTime.now(),
                "integration-test",
                false
        ));

        StandardQuote freshQuote = marketLatestQuoteRepository.findBySymbol("EURUSD").orElseThrow();
        Thread.sleep(180L);
        StandardQuote staleQuote = marketLatestQuoteRepository.findBySymbol("EURUSD").orElseThrow();

        Assertions.assertFalse(freshQuote.stale());
        Assertions.assertTrue(staleQuote.stale());
    }

    @Test
    void shouldSetRedisTtlForMarketLatestQuote() {
        marketLatestQuoteRepository.save(new StandardQuote(
                "EURUSD",
                new BigDecimal("1.08100000"),
                new BigDecimal("1.08120000"),
                new BigDecimal("1.08110000"),
                new BigDecimal("1.08110000"),
                OffsetDateTime.now(),
                "integration-test",
                false
        ));

        Long ttl = stringRedisTemplate.getExpire("falconx:market:price:EURUSD");

        Assertions.assertNotNull(ttl);
        Assertions.assertTrue(ttl >= 1L && ttl <= 2L);
    }

    @Test
    void shouldTreatIncompleteRedisLatestQuoteAsCacheMiss() {
        stringRedisTemplate.opsForHash().put("falconx:market:price:EURUSD", "bid", "1.08100000");

        Assertions.assertTrue(marketLatestQuoteRepository.findBySymbol("EURUSD").isEmpty());
    }

    @Test
    void shouldSetRedisTtlForMarketReferenceQuote() {
        marketReferenceQuoteRepository.saveLastValid(new StandardQuote(
                "EURUSD",
                new BigDecimal("1.08100000"),
                new BigDecimal("1.08120000"),
                new BigDecimal("1.08110000"),
                new BigDecimal("1.08110000"),
                OffsetDateTime.now(),
                "integration-test",
                false
        ));

        Long ttl = stringRedisTemplate.getExpire("falconx:market:last-valid-price:EURUSD");
        StandardQuote referenceQuote = marketReferenceQuoteRepository.findBySymbol("EURUSD").orElseThrow();

        Assertions.assertNotNull(ttl);
        Assertions.assertTrue(ttl >= 25L && ttl <= 30L);
        Assertions.assertEquals("EURUSD", referenceQuote.symbol());
        Assertions.assertTrue(referenceQuote.stale());
    }
}
