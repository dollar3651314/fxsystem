package com.falconx.market.service.impl;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketQuoteQualityStatus;
import com.falconx.market.entity.StandardQuote;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 行情质量保护测试。
 */
class DefaultMarketQuoteQualityGuardServiceTests {

    // ─── NO_QUOTE ──────────────────────────────────────────────────────────

    @Test
    void shouldTreatUnchangedQuoteBeyondThresholdAsNoQuote() throws InterruptedException {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getQuoteQuality().setUnchangedMaxAge(Duration.ofMillis(50));
        DefaultMarketQuoteQualityGuardService service = new DefaultMarketQuoteQualityGuardService(properties);

        StandardQuote firstQuote = quote("EURUSD", "1.08000", "1.08010");
        StandardQuote firstResult = service.evaluate(firstQuote);
        Thread.sleep(80L);
        StandardQuote secondResult = service.evaluate(quote("EURUSD", "1.08000", "1.08010"));

        Assertions.assertEquals(MarketQuoteQualityStatus.FRESH, firstResult.qualityStatus());
        Assertions.assertEquals(MarketQuoteQualityStatus.NO_QUOTE, secondResult.qualityStatus());
        Assertions.assertTrue(secondResult.stale());
        Assertions.assertEquals("UNCHANGED_TOO_LONG", secondResult.qualityReason());
    }

    @Test
    void shouldResetUnchangedWindowWhenPriceChanges() throws InterruptedException {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getQuoteQuality().setUnchangedMaxAge(Duration.ofMillis(50));
        DefaultMarketQuoteQualityGuardService service = new DefaultMarketQuoteQualityGuardService(properties);

        service.evaluate(quote("EURUSD", "1.08000", "1.08010"));
        Thread.sleep(80L);
        StandardQuote changed = service.evaluate(quote("EURUSD", "1.08001", "1.08011"));

        Assertions.assertEquals(MarketQuoteQualityStatus.FRESH, changed.qualityStatus());
        Assertions.assertFalse(changed.stale());
    }

    // ─── Tick 跳变 ──────────────────────────────────────────────────────────

    @Test
    void shouldMarkAbnormalWhenTickJumpExceedsMaxRate() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getQuoteQuality().setMaxTickJumpRate(new BigDecimal("0.05"));
        DefaultMarketQuoteQualityGuardService service = new DefaultMarketQuoteQualityGuardService(properties);

        // 建立基准价 100
        service.evaluate(quote("BTCUSD", "100.00", "100.10"));
        // 单 tick 跳变至 115：(115-100)/100 = 15% > 5%
        StandardQuote jumped = service.evaluate(quote("BTCUSD", "114.95", "115.05"));

        Assertions.assertFalse(jumped.executable());
        Assertions.assertEquals(MarketQuoteQualityStatus.ABNORMAL, jumped.qualityStatus());
        Assertions.assertEquals("TICK_JUMP_TOO_LARGE", jumped.qualityReason());
    }

    @Test
    void shouldKeepFreshWhenTickJumpIsWithinLimit() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getQuoteQuality().setMaxTickJumpRate(new BigDecimal("0.10"));
        DefaultMarketQuoteQualityGuardService service = new DefaultMarketQuoteQualityGuardService(properties);

        service.evaluate(quote("BTCUSD", "100.00", "100.10"));
        // 跳变 3%：(103-100)/100 = 3% < 10%
        StandardQuote result = service.evaluate(quote("BTCUSD", "102.95", "103.05"));

        Assertions.assertEquals(MarketQuoteQualityStatus.FRESH, result.qualityStatus());
    }

    @Test
    void shouldNotCheckTickJumpOnFirstQuote() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getQuoteQuality().setMaxTickJumpRate(new BigDecimal("0.001"));
        DefaultMarketQuoteQualityGuardService service = new DefaultMarketQuoteQualityGuardService(properties);

        // 第一条报价没有上一价格参照，不应触发 tick 跳变
        StandardQuote first = service.evaluate(quote("EURUSD", "1.08000", "1.08010"));

        Assertions.assertEquals(MarketQuoteQualityStatus.FRESH, first.qualityStatus());
    }

    @Test
    void shouldNotCheckTickJumpWhenRateIsNull() {
        MarketServiceProperties properties = new MarketServiceProperties();
        // maxTickJumpRate = null（默认），不启用检测
        DefaultMarketQuoteQualityGuardService service = new DefaultMarketQuoteQualityGuardService(properties);

        service.evaluate(quote("BTCUSD", "100.00", "100.10"));
        StandardQuote result = service.evaluate(quote("BTCUSD", "200.00", "200.10"));

        Assertions.assertEquals(MarketQuoteQualityStatus.FRESH, result.qualityStatus());
    }

    // ─── 短时波动 ───────────────────────────────────────────────────────────

    @Test
    void shouldMarkAbnormalWhenVolatilityExceedsThresholdInWindow() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getQuoteQuality().setMaxVolatilityRate(new BigDecimal("0.05"));
        properties.getQuoteQuality().setVolatilityWindow(Duration.ofSeconds(10));
        DefaultMarketQuoteQualityGuardService service = new DefaultMarketQuoteQualityGuardService(properties);

        // 窗口内价格从 100 到 110：(110-100)/100 = 10% > 5%
        service.evaluate(quote("XAUUSD", "99.95", "100.05"));
        StandardQuote result = service.evaluate(quote("XAUUSD", "109.95", "110.05"));

        Assertions.assertFalse(result.executable());
        Assertions.assertEquals(MarketQuoteQualityStatus.ABNORMAL, result.qualityStatus());
        Assertions.assertEquals("SHORT_TERM_VOLATILITY_EXCEEDED", result.qualityReason());
    }

    @Test
    void shouldKeepFreshWhenVolatilityIsWithinLimit() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getQuoteQuality().setMaxVolatilityRate(new BigDecimal("0.05"));
        properties.getQuoteQuality().setVolatilityWindow(Duration.ofSeconds(10));
        DefaultMarketQuoteQualityGuardService service = new DefaultMarketQuoteQualityGuardService(properties);

        // 窗口内 2% 波动：(102-100)/100 = 2% < 5%
        service.evaluate(quote("XAUUSD", "99.95", "100.05"));
        StandardQuote result = service.evaluate(quote("XAUUSD", "101.95", "102.05"));

        Assertions.assertEquals(MarketQuoteQualityStatus.FRESH, result.qualityStatus());
    }

    @Test
    void shouldIgnoreExpiredPricesOutsideVolatilityWindow() throws InterruptedException {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getQuoteQuality().setMaxVolatilityRate(new BigDecimal("0.05"));
        properties.getQuoteQuality().setVolatilityWindow(Duration.ofMillis(80));
        DefaultMarketQuoteQualityGuardService service = new DefaultMarketQuoteQualityGuardService(properties);

        // 先写入低价
        service.evaluate(quote("XAUUSD", "99.95", "100.05"));
        // 等待低价条目超出窗口
        Thread.sleep(100L);
        // 窗口内只有新价 110，单条不触发波动
        StandardQuote result = service.evaluate(quote("XAUUSD", "109.95", "110.05"));

        Assertions.assertEquals(MarketQuoteQualityStatus.FRESH, result.qualityStatus());
    }

    // ─── 已有 ABNORMAL 报价不被重新评估 ─────────────────────────────────────

    @Test
    void shouldPassThroughAlreadyAbnormalQuoteWithoutReEvaluation() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getQuoteQuality().setMaxTickJumpRate(new BigDecimal("0.001"));
        DefaultMarketQuoteQualityGuardService service = new DefaultMarketQuoteQualityGuardService(properties);

        // 构造已被标准化标记为 ABNORMAL 的报价（如零价）
        BigDecimal mid = new BigDecimal("0");
        StandardQuote abnormal = new StandardQuote(
                "EURUSD",
                BigDecimal.ZERO,
                new BigDecimal("1.08010"),
                mid, mid,
                OffsetDateTime.now(),
                "test",
                true,
                MarketQuoteQualityStatus.ABNORMAL,
                "NON_POSITIVE_PRICE"
        );
        StandardQuote result = service.evaluate(abnormal);

        Assertions.assertEquals(MarketQuoteQualityStatus.ABNORMAL, result.qualityStatus());
        Assertions.assertEquals("NON_POSITIVE_PRICE", result.qualityReason());
    }

    // ─── 工具方法 ────────────────────────────────────────────────────────────

    private StandardQuote quote(String symbol, String bid, String ask) {
        BigDecimal bidPrice = new BigDecimal(bid);
        BigDecimal askPrice = new BigDecimal(ask);
        BigDecimal mid = bidPrice.add(askPrice).divide(BigDecimal.valueOf(2));
        return new StandardQuote(
                symbol,
                bidPrice,
                askPrice,
                mid,
                mid,
                OffsetDateTime.now(),
                "quality-test",
                false
        );
    }
}
