package com.falconx.trading;

import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 交易域最新价格 stale 语义集成测试。
 *
 * <p>该测试验证 trading-core-service 读取 Redis 快照时会重新按当前时间计算 stale，
 * 防止过期价格在 Tiingo 断流后仍被误判为可下单价格。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "falconx.trading.stale.max-age=100ms",
                "falconx.trading.cache.quote-ttl=2s"
        }
)
class TradingQuoteSnapshotStaleIntegrationTests {

    @Autowired
    private TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private TradingTestSupportMapper tradingTestSupportMapper;

    @BeforeEach
    void clearQuoteSnapshot() {
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:ETHUSDT");
    }

    @Test
    void shouldMarkTradingQuoteStaleWhenReadHappensAfterMaxAge() {
        tradingQuoteSnapshotRepository.save(new TradingQuoteSnapshot(
                "ETHUSDT",
                new BigDecimal("1990.00000000"),
                new BigDecimal("2000.00000000"),
                new BigDecimal("1995.00000000"),
                OffsetDateTime.now(ZoneOffset.UTC),
                "integration-test",
                false
        ));

        TradingQuoteSnapshot freshSnapshot = tradingQuoteSnapshotRepository.findBySymbol("ETHUSDT").orElseThrow();
        tradingQuoteSnapshotRepository.save(new TradingQuoteSnapshot(
                "ETHUSDT",
                new BigDecimal("1990.00000000"),
                new BigDecimal("2000.00000000"),
                new BigDecimal("1995.00000000"),
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                "integration-test",
                false
        ));
        TradingQuoteSnapshot staleSnapshot = tradingQuoteSnapshotRepository.findBySymbol("ETHUSDT").orElseThrow();

        Assertions.assertFalse(freshSnapshot.stale());
        Assertions.assertTrue(staleSnapshot.stale());
        Assertions.assertEquals(TradingQuoteQualityStatus.STALE, staleSnapshot.qualityStatus());
    }

    @Test
    void shouldMarkTradingQuoteStaleWhenTimestampDriftsTooFarInFuture() {
        tradingQuoteSnapshotRepository.save(new TradingQuoteSnapshot(
                "ETHUSDT",
                new BigDecimal("1990.00000000"),
                new BigDecimal("2000.00000000"),
                new BigDecimal("1995.00000000"),
                OffsetDateTime.now().plusSeconds(1),
                "integration-test",
                false
        ));

        TradingQuoteSnapshot staleSnapshot = tradingQuoteSnapshotRepository.findBySymbol("ETHUSDT").orElseThrow();

        Assertions.assertTrue(staleSnapshot.stale());
        Assertions.assertEquals(TradingQuoteQualityStatus.STALE, staleSnapshot.qualityStatus());
    }

    @Test
    void shouldReturnEffectiveStaleSnapshotWhenSavingOldQuote() {
        TradingQuoteSnapshot savedSnapshot = tradingQuoteSnapshotRepository.save(new TradingQuoteSnapshot(
                "ETHUSDT",
                new BigDecimal("1990.00000000"),
                new BigDecimal("2000.00000000"),
                new BigDecimal("1995.00000000"),
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                "integration-test",
                false,
                TradingQuoteQualityStatus.FRESH,
                null
        ));

        Assertions.assertTrue(savedSnapshot.stale());
        Assertions.assertEquals(TradingQuoteQualityStatus.STALE, savedSnapshot.qualityStatus());
    }

    @Test
    void shouldKeepNoQuoteQualityAsNonExecutable() {
        tradingQuoteSnapshotRepository.save(new TradingQuoteSnapshot(
                "ETHUSDT",
                new BigDecimal("1990.00000000"),
                new BigDecimal("2000.00000000"),
                new BigDecimal("1995.00000000"),
                OffsetDateTime.now(),
                "integration-test",
                true,
                TradingQuoteQualityStatus.NO_QUOTE,
                "UNCHANGED_TOO_LONG"
        ));

        TradingQuoteSnapshot noQuoteSnapshot = tradingQuoteSnapshotRepository.findBySymbol("ETHUSDT").orElseThrow();

        Assertions.assertTrue(noQuoteSnapshot.stale());
        Assertions.assertFalse(noQuoteSnapshot.executable());
        Assertions.assertEquals(TradingQuoteQualityStatus.NO_QUOTE, noQuoteSnapshot.qualityStatus());
        Assertions.assertEquals("UNCHANGED_TOO_LONG", noQuoteSnapshot.qualityReason());
    }

    @Test
    void shouldSetRedisTtlForTradingQuoteSnapshot() {
        tradingQuoteSnapshotRepository.save(new TradingQuoteSnapshot(
                "ETHUSDT",
                new BigDecimal("1990.00000000"),
                new BigDecimal("2000.00000000"),
                new BigDecimal("1995.00000000"),
                OffsetDateTime.now(),
                "integration-test",
                false
        ));

        Long ttl = stringRedisTemplate.getExpire("falconx:trading:quote:snapshot:ETHUSDT");

        Assertions.assertNotNull(ttl);
        Assertions.assertTrue(ttl >= 1L && ttl <= 2L);
    }

    @Test
    void shouldKeepPositionSchemaWithoutUnrealizedPnlColumn() {
        Assertions.assertEquals(0, tradingTestSupportMapper.countPositionColumnsByName("unrealized_pnl"));
    }
}
