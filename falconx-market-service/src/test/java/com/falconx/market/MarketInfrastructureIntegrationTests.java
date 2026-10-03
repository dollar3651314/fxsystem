package com.falconx.market;

import com.falconx.market.application.MarketDataIngestionApplicationService;
import com.falconx.market.entity.MarketSymbolQuoteMapping;
import com.falconx.market.entity.MarketTradingScheduleSnapshot;
import com.falconx.market.entity.MarketSwapRateSnapshot;
import com.falconx.market.analytics.mapper.test.MarketAnalyticsTestSupportMapper;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.repository.MarketLatestQuoteRepository;
import com.falconx.market.repository.MarketReferenceQuoteRepository;
import com.falconx.market.repository.MarketSymbolQuoteMappingRepository;
import com.falconx.market.repository.MarketSymbolRepository;
import com.falconx.market.repository.RedisMarketSwapRateSnapshotRepository;
import com.falconx.market.service.KlineAggregationService;
import com.falconx.market.service.MarketSwapRateWarmupService;
import com.falconx.market.service.MarketTradingScheduleWarmupService;
import com.falconx.market.repository.RedisMarketTradingScheduleSnapshotRepository;
import com.falconx.market.repository.mapper.test.MarketSwapRateTestSupportMapper;
import com.falconx.market.repository.mapper.test.MarketSymbolTestSupportMapper;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * market-service 真实基础设施集成测试。
 *
 * <p>该测试直接验证 Stage 5 下 market owner 的真实写入路径：
 *
 * <ol>
 *   <li>应用层接收原始报价</li>
 *   <li>最新价写入 Redis</li>
 *   <li>报价历史写入 ClickHouse</li>
 * </ol>
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
                "falconx.market.stale.max-age=10m",
                "falconx.market.analytics.quote-batch-size=1",
                "falconx.market.analytics.quote-flush-interval=50",
                "falconx.market.analytics.jdbc-url=jdbc:clickhouse://localhost:8123/falconx_market_analytics",
                "falconx.market.analytics.username=default",
                "falconx.market.analytics.password=falconx"
        }
)
@ExtendWith(OutputCaptureExtension.class)
class MarketInfrastructureIntegrationTests {

    @Autowired
    private MarketDataIngestionApplicationService marketDataIngestionApplicationService;

    @Autowired
    private MarketLatestQuoteRepository marketLatestQuoteRepository;

    @Autowired
    private MarketReferenceQuoteRepository marketReferenceQuoteRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MarketAnalyticsTestSupportMapper marketAnalyticsTestSupportMapper;

    @Autowired
    private MarketTradingScheduleWarmupService marketTradingScheduleWarmupService;

    @Autowired
    private RedisMarketTradingScheduleSnapshotRepository marketTradingScheduleSnapshotRepository;

    @Autowired
    private MarketSwapRateWarmupService marketSwapRateWarmupService;

    @Autowired
    private RedisMarketSwapRateSnapshotRepository marketSwapRateSnapshotRepository;

    @Autowired
    private MarketSwapRateTestSupportMapper marketSwapRateTestSupportMapper;

    @Autowired
    private KlineAggregationService klineAggregationService;

    @Autowired
    private MarketSymbolRepository marketSymbolRepository;

    @Autowired
    private MarketSymbolQuoteMappingRepository marketSymbolQuoteMappingRepository;

    @Autowired
    private MarketSymbolTestSupportMapper marketSymbolTestSupportMapper;

    @BeforeEach
    void cleanMarketStores() {
        stringRedisTemplate.delete("falconx:market:price:EURUSD");
        stringRedisTemplate.delete("falconx:market:last-valid-price:EURUSD");
        stringRedisTemplate.delete("falconx:market:trading:schedule:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:BTCUSD");
        stringRedisTemplate.delete("falconx:market:trading:schedule:EURUSD");
        stringRedisTemplate.delete("falconx:market:swap-rate:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:swap-rate:BTCUSD");
        marketAnalyticsTestSupportMapper.clearAnalyticsTables();
        marketSwapRateTestSupportMapper.deleteAllSwapRates();
        clearKlineAggregationState();
    }

    @Test
    void shouldPersistLatestQuoteToRedisAndClickHouse(CapturedOutput output) {
        OffsetDateTime now = OffsetDateTime.now().minusSeconds(1);
        StandardQuote standardQuote = marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08100000"),
                new BigDecimal("1.08120000"),
                now,
                "TM_QUOTE"
        ));

        StandardQuote persistedQuote = marketLatestQuoteRepository.findBySymbol("EURUSD").orElseThrow();
        Long quoteTickCount = awaitQuoteTickCount("EURUSD");

        Assertions.assertEquals(standardQuote.bid(), persistedQuote.bid());
        Assertions.assertEquals(standardQuote.ask(), persistedQuote.ask());
        Assertions.assertNotNull(quoteTickCount);
        Assertions.assertTrue(quoteTickCount >= 1L);
        Assertions.assertTrue(output.toString().contains("traceId="));
    }

    @Test
    void shouldRestoreReferenceQuoteFromClickHouseWhenRedisReferenceMisses() {
        marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08100000"),
                new BigDecimal("1.08120000"),
                OffsetDateTime.now().minusSeconds(1),
                "TM_QUOTE"
        ));
        stringRedisTemplate.delete("falconx:market:price:EURUSD");
        stringRedisTemplate.delete("falconx:market:last-valid-price:EURUSD");

        StandardQuote referenceQuote = awaitReferenceQuote("EURUSD").orElseThrow();
        Long ttl = stringRedisTemplate.getExpire("falconx:market:last-valid-price:EURUSD");

        Assertions.assertEquals(new BigDecimal("1.08100000"), referenceQuote.bid());
        Assertions.assertEquals(new BigDecimal("1.08120000"), referenceQuote.ask());
        Assertions.assertEquals("TM_QUOTE", referenceQuote.source());
        Assertions.assertTrue(referenceQuote.stale());
        Assertions.assertNotNull(ttl);
        Assertions.assertTrue(ttl > 0);
    }

    private Long awaitQuoteTickCount(String symbol) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Long quoteTickCount = 0L;
        while (System.nanoTime() < deadline) {
            quoteTickCount = marketAnalyticsTestSupportMapper.countQuoteTickBySymbol(symbol);
            if (quoteTickCount != null && quoteTickCount >= 1L) {
                return quoteTickCount;
            }
            sleepQuietly();
        }
        return quoteTickCount;
    }

    private Optional<StandardQuote> awaitReferenceQuote(String symbol) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Optional<StandardQuote> referenceQuote = Optional.empty();
        while (System.nanoTime() < deadline) {
            referenceQuote = marketReferenceQuoteRepository.findBySymbol(symbol);
            if (referenceQuote.isPresent()) {
                return referenceQuote;
            }
            sleepQuietly();
        }
        return referenceQuote;
    }

    private void sleepQuietly() {
        try {
            TimeUnit.MILLISECONDS.sleep(50);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for market analytics persistence", exception);
        }
    }

    @Test
    void shouldPersistFinalizedOneMinuteKlineToClickHouse(CapturedOutput output) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime firstTickTime = now.withSecond(10).withNano(0);
        if (firstTickTime.isAfter(now)) {
            firstTickTime = firstTickTime.minusMinutes(1);
        }
        OffsetDateTime secondTickTime = firstTickTime.withSecond(50);
        OffsetDateTime thirdTickTime = firstTickTime.plusMinutes(1).withSecond(1);

        marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08200000"),
                new BigDecimal("1.08220000"),
                firstTickTime,
                "TM_QUOTE"
        ));
        marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08300000"),
                new BigDecimal("1.08320000"),
                secondTickTime,
                "TM_QUOTE"
        ));
        marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08150000"),
                new BigDecimal("1.08170000"),
                thirdTickTime,
                "TM_QUOTE"
        ));

        Long klineCount = marketAnalyticsTestSupportMapper.countKlineBySymbolAndInterval("EURUSD", "1m");

        Assertions.assertNotNull(klineCount);
        Assertions.assertEquals(1L, klineCount);
        Assertions.assertTrue(output.toString().contains("market.kline.closed symbol=EURUSD interval=1m"));
    }

    @Test
    void shouldWarmTradingScheduleSnapshotToRedis() {
        marketTradingScheduleWarmupService.refreshAll();

        MarketTradingScheduleSnapshot btcSnapshot = marketTradingScheduleSnapshotRepository.findBySymbol("BTCUSD")
                .orElseThrow();
        MarketTradingScheduleSnapshot eurusdSnapshot = marketTradingScheduleSnapshotRepository.findBySymbol("EURUSD")
                .orElseThrow();
        MarketTradingScheduleSnapshot gbpusdSnapshot = marketTradingScheduleSnapshotRepository.findBySymbol("GBPUSD")
                .orElseThrow();
        Long btcScheduleTtl = stringRedisTemplate.getExpire("falconx:market:trading:schedule:BTCUSD");

        Assertions.assertEquals("CRYPTO", btcSnapshot.marketCode());
        Assertions.assertEquals(7, btcSnapshot.sessions().size());
        Assertions.assertEquals("FX", eurusdSnapshot.marketCode());
        Assertions.assertEquals(5, eurusdSnapshot.sessions().size());
        Assertions.assertEquals("FX", gbpusdSnapshot.marketCode());
        Assertions.assertEquals(5, gbpusdSnapshot.sessions().size());
        Assertions.assertNotNull(btcScheduleTtl);
        Assertions.assertTrue(btcScheduleTtl <= 90_000L && btcScheduleTtl >= 89_000L);
    }

    @Test
    void shouldWarmSwapRateSnapshotToRedis() {
        marketSwapRateTestSupportMapper.upsertSwapRate(
                "BTCUSD",
                new BigDecimal("-0.00010000"),
                new BigDecimal("0.00012000"),
                LocalTime.of(22, 0),
                LocalDate.now().minusDays(1)
        );
        marketSwapRateTestSupportMapper.upsertSwapRate(
                "BTCUSD",
                new BigDecimal("-0.00020000"),
                new BigDecimal("0.00030000"),
                LocalTime.of(22, 0),
                LocalDate.now()
        );

        marketSwapRateWarmupService.refreshAll();

        MarketSwapRateSnapshot snapshot = marketSwapRateSnapshotRepository.findBySymbol("BTCUSD")
                .orElseThrow();
        Long ttl = stringRedisTemplate.getExpire("falconx:market:swap-rate:BTCUSD");

        Assertions.assertEquals("BTCUSD", snapshot.symbol());
        Assertions.assertEquals(2, snapshot.rates().size());
        Assertions.assertEquals(LocalDate.now().minusDays(1), snapshot.rates().getFirst().effectiveFrom());
        Assertions.assertEquals(LocalDate.now(), snapshot.rates().getLast().effectiveFrom());
        Assertions.assertEquals(new BigDecimal("-0.00020000"), snapshot.rates().getLast().longRate());
        Assertions.assertNotNull(ttl);
        Assertions.assertTrue(ttl <= 90_000L && ttl >= 89_000L);
    }

    @Test
    void shouldAppendNewSymbolWithoutOverwritingExistingSymbol() {
        int insertedCount = marketSymbolRepository.appendIfAbsent(List.of(
                new com.falconx.market.entity.MarketSymbol(
                        999_001L,
                        "GODSA",
                        "TESTXUSDT",
                        1,
                        "CRYPTO",
                        "TESTX",
                        "USDT",
                        8,
                        6,
                        2
                ),
                new com.falconx.market.entity.MarketSymbol(
                        999_002L,
                        "GODSA",
                        "BTCUSD",
                        1,
                        "CRYPTO",
                        "BTC",
                        "USD",
                        2,
                        2,
                        2
                )
        ));

        com.falconx.market.entity.MarketSymbol appended = marketSymbolRepository.findBySymbol("TESTXUSDT").orElseThrow();
        com.falconx.market.entity.MarketSymbol existing = marketSymbolRepository.findBySymbol("BTCUSD").orElseThrow();

        Assertions.assertEquals(1, insertedCount);
        Assertions.assertEquals("TESTX", appended.baseCurrency());
        Assertions.assertEquals("USDT", appended.quoteCurrency());
        Assertions.assertEquals(2, appended.status());
        Assertions.assertEquals(1, existing.status());
        Assertions.assertEquals(2, existing.pricePrecision());
    }

    @Test
    void shouldUseLpMt5SymbolsAsMarketOwnerWhitelist() {
        List<com.falconx.market.entity.MarketSymbol> tradingSymbols = marketSymbolRepository.findAllTradingSymbols();
        List<com.falconx.market.entity.MarketSymbolWithSpec> defaultGroupSymbols =
                marketSymbolRepository.findTradingSymbolsByGroupCode("default");
        com.falconx.market.entity.MarketSymbol btcusd = marketSymbolRepository.findBySymbol("BTCUSD").orElseThrow();
        com.falconx.market.entity.MarketSymbol apple = marketSymbolRepository.findBySymbol("AAPL.NAS").orElseThrow();
        com.falconx.market.entity.MarketSymbol disabled = marketSymbolRepository.findBySymbol("EOSUSD").orElseThrow();
        List<MarketSymbolQuoteMapping> lpSubscribedMappings = marketSymbolQuoteMappingRepository.findAllLpSubscribedMappings();

        Assertions.assertEquals(1571, tradingSymbols.size());
        Assertions.assertEquals(1571, defaultGroupSymbols.size());
        Assertions.assertEquals(1571, lpSubscribedMappings.size());
        Assertions.assertEquals(0L, marketSymbolTestSupportMapper.countBlockedSuffixSymbols());
        Assertions.assertEquals(0L, marketSymbolTestSupportMapper.countBlockedSuffixGroupVisibility());
        Assertions.assertEquals(0L, marketSymbolTestSupportMapper.countBlockedSuffixQuoteMappings());
        Assertions.assertTrue(marketSymbolRepository.findBySymbol("XAUUSD.p").isEmpty());
        Assertions.assertTrue(defaultGroupSymbols.stream().noneMatch(symbol -> hasBlockedLpSuffix(symbol.symbol())));
        Assertions.assertTrue(lpSubscribedMappings.stream().noneMatch(mapping ->
                hasBlockedLpSuffix(mapping.platformSymbol()) || hasBlockedLpSuffix(mapping.sourceSymbol())));
        Assertions.assertTrue(marketSymbolRepository.findVisibleTradingSymbol("BTCUSD", "default").isPresent());
        Assertions.assertTrue(marketSymbolRepository.findVisibleTradingSymbol("BTCUSD", "missing-group").isEmpty());
        Assertions.assertTrue(tradingSymbols.stream().noneMatch(symbol -> "BTCUSDT".equals(symbol.symbol())));
        Assertions.assertEquals("CRYPTO", btcusd.marketCode());
        Assertions.assertEquals("BTC", btcusd.baseCurrency());
        Assertions.assertEquals("USD", btcusd.quoteCurrency());
        Assertions.assertEquals(1, btcusd.status());
        Assertions.assertEquals("US_STOCK", apple.marketCode());
        Assertions.assertEquals(6, apple.category());
        Assertions.assertEquals(1, apple.status());
        Assertions.assertEquals(2, disabled.status());
    }

    private static boolean hasBlockedLpSuffix(String symbol) {
        String normalized = symbol.toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith(".p") || normalized.endsWith(".c") || normalized.endsWith(".f");
    }

    @SuppressWarnings("unchecked")
    private void clearKlineAggregationState() {
        Object bucketsField = ReflectionTestUtils.getField(klineAggregationService, "buckets");
        if (bucketsField instanceof Map<?, ?> buckets) {
            buckets.clear();
        }
    }
}
