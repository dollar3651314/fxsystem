package com.falconx.market.integration;

import com.falconx.market.MarketServiceApplication;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.entity.MarketSymbol;
import com.falconx.market.repository.MarketSymbolRepository;
import com.falconx.market.service.FxRateService;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
 * STAGE-14A Task 10 — FX 汇率 Redis 三层集成测试（TC-001 / TC-002 / TC-003）。
 *
 * <p>使用真实 MySQL（localhost:3306/falconx_market_it）和真实 Redis（localhost:6380），
 * 与既有 {@code MarketInfrastructureIntegrationTests} 保持相同的测试策略：
 * <ul>
 *   <li>TC-FX-IT-001：V18 Flyway seed 后 t_symbol 中可见 8 个 FX symbol</li>
 *   <li>TC-FX-IT-002：FxRateService.acceptTick → Redis key 命中 + TTL ≤ 5s</li>
 *   <li>TC-FX-IT-003：FxRateService.queryRate(EUR, AUD) USD pivot 返回正确交叉值</li>
 * </ul>
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
                "falconx.market.analytics.jdbc-url=jdbc:clickhouse://localhost:8123/falconx_market_analytics",
                "falconx.market.analytics.username=default",
                "falconx.market.analytics.password=falconx",
                "falconx.market.fx.redis-ttl-seconds=5",
                /* 关闭 LP 连接，防止测试期间产生干扰 tick */
                "falconx.market.lp.enabled=false"
        }
)
class FxRateRedisIntegrationTests {

    /** Redis key 格式: falconx:fx:rate:{base}:{quote}（与 DefaultFxRateService 保持一致） */
    private static final String KEY_PREFIX = "falconx:fx:rate:";

    @Autowired
    private MarketSymbolRepository marketSymbolRepository;

    @Autowired
    private FxRateService fxRateService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void cleanFxRateKeys() {
        /* 清除测试用的 FX rate 键，避免跨 test 污染 */
        stringRedisTemplate.delete(KEY_PREFIX + "EUR:USD");
        stringRedisTemplate.delete(KEY_PREFIX + "AUD:USD");
        stringRedisTemplate.delete(KEY_PREFIX + "USD:JPY");
        stringRedisTemplate.delete(KEY_PREFIX + "GBP:USD");
        stringRedisTemplate.delete(KEY_PREFIX + "USD:CAD");
        stringRedisTemplate.delete(KEY_PREFIX + "USD:CHF");
        stringRedisTemplate.delete(KEY_PREFIX + "NZD:USD");
        stringRedisTemplate.delete(KEY_PREFIX + "USD:CNH");
    }

    /**
     * TC-FX-IT-001: V18 Flyway 迁移后，t_symbol 表中 8 个核心 FX symbol 可见且状态激活（status=1）。
     *
     * <p>V18__seed_fx_symbols.sql 通过 ON DUPLICATE KEY UPDATE 确保 EURUSD/AUDUSD/USDJPY/GBPUSD/
     * USDCAD/USDCHF/NZDUSD/USDCNH 这 8 个核心 FX symbol 在 t_symbol 中存在且激活（status=1）。
     * 注意：MT5 快照（V5）已含大量 FX symbol，所以不验证总数恰好为 8，而是逐一验证这 8 个必须存在。
     */
    @Test
    void tc001_v18SeedAfter8FxSymbolsVisibleInTSymbol() {
        /* V18 必须存在的 8 个核心 FX symbol */
        java.util.List<String> required = java.util.List.of(
                "EURUSD", "AUDUSD", "USDJPY", "GBPUSD",
                "USDCAD", "USDCHF", "NZDUSD", "USDCNH"
        );

        for (String symbol : required) {
            MarketSymbol s = marketSymbolRepository.findBySymbol(symbol)
                    .orElseThrow(() -> new AssertionError(
                            "V18 seed 后 t_symbol 中应存在 symbol=" + symbol + "，但未找到"));
            Assertions.assertEquals(1, s.status(),
                    "symbol=" + symbol + " 应激活（status=1），实际=" + s.status());
            Assertions.assertEquals("FX", s.marketCode(),
                    "symbol=" + symbol + " 的 market_code 应为 FX");
        }
    }

    /**
     * TC-FX-IT-002: FxRateService.acceptTick 写入后，Redis key 可读且 TTL ≤ 5s。
     *
     * <p>验证路径：acceptTick → DefaultFxRateRedisCache.set → StringRedisTemplate。
     */
    @Test
    void tc002_acceptTickWritesRediKeyWithCorrectTtl() {
        FxRateSnapshotPayload eurUsd = new FxRateSnapshotPayload(
                "EUR", "USD",
                new BigDecimal("1.08500"),
                System.currentTimeMillis(),
                "GODSA", "EURUSD"
        );

        fxRateService.acceptTick(eurUsd);

        String redisKey = KEY_PREFIX + "EUR:USD";
        String rateStr = stringRedisTemplate.opsForValue().get(redisKey);
        Long ttl = stringRedisTemplate.getExpire(redisKey);

        Assertions.assertNotNull(rateStr, "acceptTick 后 Redis key 应可读");
        Assertions.assertEquals(0, new BigDecimal(rateStr).compareTo(new BigDecimal("1.08500")),
                "Redis 中存储的 rate 应与 payload.rate 一致");
        Assertions.assertNotNull(ttl, "Redis key 应有 TTL 设置");
        Assertions.assertTrue(ttl > 0 && ttl <= 5,
                "TTL 应在 (0, 5] 秒范围内，实际 TTL=" + ttl);
    }

    /**
     * TC-FX-IT-003: FxRateService.queryRate(EUR, AUD) 通过 USD pivot 返回正确交叉汇率。
     *
     * <p>EUR/USD = 1.085, AUD/USD = 0.65 → EUR/AUD = 1.085 / 0.65 ≈ 1.6692...
     * 验证交叉换算由 FxRateConverter 正确执行，误差小于 0.0001。
     */
    @Test
    void tc003_queryRateEurAudReturnCorrectUsdPivotCrossRate() {
        long now = System.currentTimeMillis();
        fxRateService.acceptTick(new FxRateSnapshotPayload(
                "EUR", "USD", new BigDecimal("1.08500"), now, "GODSA", "EURUSD"
        ));
        fxRateService.acceptTick(new FxRateSnapshotPayload(
                "AUD", "USD", new BigDecimal("0.65000"), now, "GODSA", "AUDUSD"
        ));

        Optional<BigDecimal> eurAud = fxRateService.queryRate("EUR", "AUD");

        Assertions.assertTrue(eurAud.isPresent(), "EUR/AUD 交叉汇率应可计算（USD pivot）");

        // 预期值: 1.085 / 0.65 = 1.66923...
        BigDecimal expected = new BigDecimal("1.08500").divide(new BigDecimal("0.65000"),
                10, java.math.RoundingMode.HALF_UP);
        BigDecimal diff = eurAud.get().subtract(expected).abs();
        Assertions.assertTrue(diff.compareTo(new BigDecimal("0.0001")) < 0,
                "EUR/AUD 交叉汇率误差应小于 0.0001，期望≈" + expected + " 实际=" + eurAud.get());
    }
}
