package com.falconx.market.service.impl;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketQuoteQualityStatus;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.provider.ExternalRawQuote;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 默认报价标准化测试。
 */
class DefaultQuoteStandardizationServiceTests {

    @Test
    void shouldKeepExternalQuoteSourceWhenStandardizingLpQuote() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getStale().setMaxAge(Duration.ofSeconds(5));
        DefaultQuoteStandardizationService service = new DefaultQuoteStandardizationService(properties);

        StandardQuote standardQuote = service.standardize(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08000"),
                new BigDecimal("1.08010"),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));

        Assertions.assertEquals("EURUSD", standardQuote.symbol());
        Assertions.assertEquals(0, new BigDecimal("1.08005").compareTo(standardQuote.mid()));
        Assertions.assertEquals("TM_QUOTE", standardQuote.source());
        Assertions.assertFalse(standardQuote.stale());
        Assertions.assertEquals(MarketQuoteQualityStatus.FRESH, standardQuote.qualityStatus());
    }

    @Test
    void shouldMarkQuoteStaleWhenTimestampDriftsTooFarInFuture() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getStale().setMaxAge(Duration.ofSeconds(5));
        DefaultQuoteStandardizationService service = new DefaultQuoteStandardizationService(properties);

        StandardQuote standardQuote = service.standardize(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08000"),
                new BigDecimal("1.08010"),
                OffsetDateTime.now().plusSeconds(6),
                "TM_QUOTE"
        ));

        Assertions.assertTrue(standardQuote.stale());
        Assertions.assertEquals(MarketQuoteQualityStatus.STALE, standardQuote.qualityStatus());
        Assertions.assertEquals("QUOTE_TIME_DRIFT_EXCEEDED", standardQuote.qualityReason());
    }

    @Test
    void shouldMarkQuoteAbnormalWhenBidAskAreCrossed() {
        MarketServiceProperties properties = new MarketServiceProperties();
        DefaultQuoteStandardizationService service = new DefaultQuoteStandardizationService(properties);

        StandardQuote standardQuote = service.standardize(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08010"),
                new BigDecimal("1.08000"),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));

        Assertions.assertTrue(standardQuote.stale());
        Assertions.assertFalse(standardQuote.executable());
        Assertions.assertEquals(MarketQuoteQualityStatus.ABNORMAL, standardQuote.qualityStatus());
        Assertions.assertEquals("BID_ASK_CROSSED", standardQuote.qualityReason());
    }

    @Test
    void shouldMarkQuoteAbnormalWhenSpreadExceedsMaxRate() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getQuoteQuality().setMaxSpreadRate(new java.math.BigDecimal("0.01"));
        DefaultQuoteStandardizationService service = new DefaultQuoteStandardizationService(properties);

        // bid=1.00000, ask=1.02000 => spread/mid ≈ 1.98% > 1%
        StandardQuote standardQuote = service.standardize(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.00000"),
                new BigDecimal("1.02000"),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));

        Assertions.assertFalse(standardQuote.executable());
        Assertions.assertEquals(MarketQuoteQualityStatus.ABNORMAL, standardQuote.qualityStatus());
        Assertions.assertEquals("SPREAD_TOO_LARGE", standardQuote.qualityReason());
    }

    @Test
    void shouldKeepFreshWhenSpreadIsWithinLimit() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getQuoteQuality().setMaxSpreadRate(new java.math.BigDecimal("0.05"));
        DefaultQuoteStandardizationService service = new DefaultQuoteStandardizationService(properties);

        // bid=1.08000, ask=1.08010 => spread/mid ≈ 0.0009% < 5%
        StandardQuote standardQuote = service.standardize(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08000"),
                new BigDecimal("1.08010"),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));

        Assertions.assertTrue(standardQuote.executable());
        Assertions.assertEquals(MarketQuoteQualityStatus.FRESH, standardQuote.qualityStatus());
    }

    @Test
    void shouldMarkQuoteAbnormalWhenPriceIsNonPositive() {
        MarketServiceProperties properties = new MarketServiceProperties();
        DefaultQuoteStandardizationService service = new DefaultQuoteStandardizationService(properties);

        StandardQuote standardQuote = service.standardize(new ExternalRawQuote(
                "EURUSD",
                BigDecimal.ZERO,
                new BigDecimal("1.08000"),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));

        Assertions.assertTrue(standardQuote.stale());
        Assertions.assertFalse(standardQuote.executable());
        Assertions.assertEquals(MarketQuoteQualityStatus.ABNORMAL, standardQuote.qualityStatus());
        Assertions.assertEquals("NON_POSITIVE_PRICE", standardQuote.qualityReason());
    }
}
