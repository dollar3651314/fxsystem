package com.falconx.market.application;

import com.falconx.market.analytics.MarketAnalyticsWriter;
import com.falconx.market.cache.MarketQuoteCacheWriter;
import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketQuoteQualityStatus;
import com.falconx.market.entity.KlineAggregationResult;
import com.falconx.market.contract.event.MarketKlineUpdateEventPayload;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.producer.MarketEventPublisher;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.service.KlineAggregationService;
import com.falconx.market.service.MarketQuoteMappingService;
import com.falconx.market.service.QuoteStandardizationService;
import com.falconx.market.service.impl.DefaultQuoteStandardizationService;
import com.falconx.market.websocket.MarketWebSocketPushService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 市场数据接入应用服务单元测试。
 *
 * <p>该测试覆盖 Stage 2A 的最小市场链路：
 * 收到一条原始报价后，能够完成标准化、缓存写入、分析写入和事件发布。
 * 测试使用本地 fake 实现，不依赖 Redis、Kafka 或 ClickHouse 进程。
 */
class MarketDataIngestionApplicationServiceTests {

    @Test
    void shouldStandardizeAndFanOutQuoteToAllStage2aSinks() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getStale().setMaxAge(Duration.ofSeconds(5));
        QuoteStandardizationService standardizationService = new DefaultQuoteStandardizationService(properties);
        RecordingCacheWriter cacheWriter = new RecordingCacheWriter();
        RecordingAnalyticsWriter analyticsWriter = new RecordingAnalyticsWriter();
        RecordingPublisher publisher = new RecordingPublisher();
        FixedKlineAggregationService aggregationService = new FixedKlineAggregationService();
        RecordingWebSocketPushService webSocketPushService = new RecordingWebSocketPushService();

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                standardizationService,
                cacheWriter,
                analyticsWriter,
                publisher,
                aggregationService,
                webSocketPushService,
                quote -> quote,
                (symbol, now) -> true,
                new IdentityQuoteMappingService(),
                null,
                null,
                null,
                false
        );

        OffsetDateTime now = OffsetDateTime.now();
        ExternalRawQuote rawQuote = new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08321"),
                new BigDecimal("1.08331"),
                now,
                "TM_QUOTE"
        );

        StandardQuote result = service.ingest(rawQuote);

        Assertions.assertEquals("EURUSD", result.symbol());
        Assertions.assertEquals(0, new BigDecimal("1.08326").compareTo(result.mid()));
        Assertions.assertFalse(result.stale());
        Assertions.assertNotNull(cacheWriter.lastQuote);
        Assertions.assertNotNull(analyticsWriter.lastQuote);
        Assertions.assertNotNull(analyticsWriter.lastKline);
        Assertions.assertNotNull(publisher.lastPriceTickPayload);
        Assertions.assertNotNull(publisher.lastKlinePayload);
        Assertions.assertEquals("EURUSD", publisher.lastPriceTickPayload.symbol());
        Assertions.assertNotNull(webSocketPushService.lastQuote);
        Assertions.assertEquals(2, webSocketPushService.klines.size());
        Assertions.assertFalse(webSocketPushService.klines.getFirst().isFinal());
        Assertions.assertTrue(webSocketPushService.klines.getLast().isFinal());
    }

    @Test
    void shouldPublishKlineUpdateBeforeWritingKlineToAnalyticsStore() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getStale().setMaxAge(Duration.ofSeconds(5));
        QuoteStandardizationService standardizationService = new DefaultQuoteStandardizationService(properties);
        List<String> sequence = new ArrayList<>();
        RecordingCacheWriter cacheWriter = new RecordingCacheWriter();
        SequencedAnalyticsWriter analyticsWriter = new SequencedAnalyticsWriter(sequence);
        SequencedPublisher publisher = new SequencedPublisher(sequence);
        FixedKlineAggregationService aggregationService = new FixedKlineAggregationService();
        SequencedWebSocketPushService webSocketPushService = new SequencedWebSocketPushService(sequence);

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                standardizationService,
                cacheWriter,
                analyticsWriter,
                publisher,
                aggregationService,
                webSocketPushService,
                quote -> quote,
                (symbol, now) -> true,
                new IdentityQuoteMappingService(),
                null,
                null,
                null,
                false
        );

        service.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08321"),
                new BigDecimal("1.08331"),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));

        Assertions.assertTrue(
                sequence.indexOf("publish-kline") < sequence.indexOf("write-kline"),
                "sequence=" + sequence
        );
        Assertions.assertTrue(
                sequence.indexOf("publish-active-kline") < sequence.indexOf("publish-kline"),
                "sequence=" + sequence
        );
    }

    @Test
    void shouldPublishNoQuoteTickWithoutRefreshingExecutableMarketStores() {
        MarketServiceProperties properties = new MarketServiceProperties();
        QuoteStandardizationService standardizationService = new DefaultQuoteStandardizationService(properties);
        RecordingCacheWriter cacheWriter = new RecordingCacheWriter();
        RecordingAnalyticsWriter analyticsWriter = new RecordingAnalyticsWriter();
        RecordingPublisher publisher = new RecordingPublisher();
        FixedKlineAggregationService aggregationService = new FixedKlineAggregationService();
        RecordingWebSocketPushService webSocketPushService = new RecordingWebSocketPushService();

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                standardizationService,
                cacheWriter,
                analyticsWriter,
                publisher,
                aggregationService,
                webSocketPushService,
                quote -> quote.withQuality(MarketQuoteQualityStatus.NO_QUOTE, "UNCHANGED_TOO_LONG"),
                (symbol, now) -> true,
                new IdentityQuoteMappingService(),
                null,
                null,
                null,
                false
        );

        StandardQuote result = service.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08321"),
                new BigDecimal("1.08331"),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));

        Assertions.assertEquals(MarketQuoteQualityStatus.NO_QUOTE, result.qualityStatus());
        Assertions.assertNull(cacheWriter.lastQuote);
        Assertions.assertNull(analyticsWriter.lastQuote);
        Assertions.assertNull(webSocketPushService.lastQuote);
        Assertions.assertNotNull(publisher.lastPriceTickPayload);
        Assertions.assertEquals("NO_QUOTE", publisher.lastPriceTickPayload.quoteStatus());
        Assertions.assertEquals("UNCHANGED_TOO_LONG", publisher.lastPriceTickPayload.qualityReason());
    }

    @Test
    void shouldPublishStaleTickWithoutRefreshingExecutableMarketStores() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getStale().setMaxAge(java.time.Duration.ofSeconds(5));
        QuoteStandardizationService standardizationService = new DefaultQuoteStandardizationService(properties);
        RecordingCacheWriter cacheWriter = new RecordingCacheWriter();
        RecordingAnalyticsWriter analyticsWriter = new RecordingAnalyticsWriter();
        RecordingPublisher publisher = new RecordingPublisher();
        FixedKlineAggregationService aggregationService = new FixedKlineAggregationService();
        RecordingWebSocketPushService webSocketPushService = new RecordingWebSocketPushService();

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                standardizationService,
                cacheWriter,
                analyticsWriter,
                publisher,
                aggregationService,
                webSocketPushService,
                quote -> quote,
                (symbol, now) -> true,
                new IdentityQuoteMappingService(),
                null,
                null,
                null,
                false
        );

        StandardQuote result = service.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08321"),
                new BigDecimal("1.08331"),
                OffsetDateTime.now().minusSeconds(6),
                "TM_QUOTE"
        ));

        Assertions.assertEquals(MarketQuoteQualityStatus.STALE, result.qualityStatus());
        Assertions.assertNull(cacheWriter.lastQuote);
        Assertions.assertNull(analyticsWriter.lastQuote);
        Assertions.assertNull(webSocketPushService.lastQuote);
        Assertions.assertNotNull(publisher.lastPriceTickPayload);
        Assertions.assertEquals("STALE", publisher.lastPriceTickPayload.quoteStatus());
        Assertions.assertEquals("QUOTE_TIME_DRIFT_EXCEEDED", publisher.lastPriceTickPayload.qualityReason());
    }

    @Test
    void shouldPublishAbnormalTickWithoutRefreshingExecutableMarketStores() {
        MarketServiceProperties properties = new MarketServiceProperties();
        QuoteStandardizationService standardizationService = new DefaultQuoteStandardizationService(properties);
        RecordingCacheWriter cacheWriter = new RecordingCacheWriter();
        RecordingAnalyticsWriter analyticsWriter = new RecordingAnalyticsWriter();
        RecordingPublisher publisher = new RecordingPublisher();
        FixedKlineAggregationService aggregationService = new FixedKlineAggregationService();
        RecordingWebSocketPushService webSocketPushService = new RecordingWebSocketPushService();

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                standardizationService,
                cacheWriter,
                analyticsWriter,
                publisher,
                aggregationService,
                webSocketPushService,
                quote -> quote,
                (symbol, now) -> true,
                new IdentityQuoteMappingService(),
                null,
                null,
                null,
                false
        );

        StandardQuote result = service.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08331"),
                new BigDecimal("1.08321"),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));

        Assertions.assertEquals(MarketQuoteQualityStatus.ABNORMAL, result.qualityStatus());
        Assertions.assertNull(cacheWriter.lastQuote);
        Assertions.assertNull(analyticsWriter.lastQuote);
        Assertions.assertNull(webSocketPushService.lastQuote);
        Assertions.assertNotNull(publisher.lastPriceTickPayload);
        Assertions.assertEquals("ABNORMAL", publisher.lastPriceTickPayload.quoteStatus());
        Assertions.assertEquals("BID_ASK_CROSSED", publisher.lastPriceTickPayload.qualityReason());
    }

    @Test
    void shouldSkipAllSinksWhenMarketIsClosed() {
        MarketServiceProperties properties = new MarketServiceProperties();
        QuoteStandardizationService standardizationService = new DefaultQuoteStandardizationService(properties);
        RecordingCacheWriter cacheWriter = new RecordingCacheWriter();
        RecordingAnalyticsWriter analyticsWriter = new RecordingAnalyticsWriter();
        RecordingPublisher publisher = new RecordingPublisher();
        FixedKlineAggregationService aggregationService = new FixedKlineAggregationService();
        RecordingWebSocketPushService webSocketPushService = new RecordingWebSocketPushService();

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                standardizationService,
                cacheWriter,
                analyticsWriter,
                publisher,
                aggregationService,
                webSocketPushService,
                quote -> quote,
                (symbol, now) -> false,
                new IdentityQuoteMappingService(),
                null,
                null,
                null,
                false
        );

        StandardQuote result = service.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08321"),
                new BigDecimal("1.08331"),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));

        Assertions.assertEquals(MarketQuoteQualityStatus.MARKET_CLOSED, result.qualityStatus());
        Assertions.assertNull(cacheWriter.lastQuote);
        Assertions.assertNull(analyticsWriter.lastQuote);
        Assertions.assertNull(webSocketPushService.lastQuote);
        Assertions.assertNull(publisher.lastPriceTickPayload);
        Assertions.assertNull(publisher.lastKlinePayload);
    }

    private static class RecordingCacheWriter implements MarketQuoteCacheWriter {
        private StandardQuote lastQuote;

        @Override
        public void writeLatestQuote(StandardQuote quote) {
            this.lastQuote = quote;
        }
    }

    private static class IdentityQuoteMappingService implements MarketQuoteMappingService {

        @Override
        public void refreshMappings() {
        }

        @Override
        public List<String> sourceSymbols() {
            return List.of("EURUSD");
        }

        @Override
        public List<ExternalRawQuote> mapToPlatformQuotes(ExternalRawQuote sourceQuote) {
            return List.of(sourceQuote);
        }
    }

    private static class RecordingAnalyticsWriter implements MarketAnalyticsWriter {
        private StandardQuote lastQuote;
        private KlineSnapshot lastKline;

        @Override
        public void writeQuoteTick(StandardQuote quote) {
            this.lastQuote = quote;
        }

        @Override
        public void writeKline(KlineSnapshot snapshot) {
            this.lastKline = snapshot;
        }
    }

    private static class RecordingPublisher implements MarketEventPublisher {
        private MarketPriceTickEventPayload lastPriceTickPayload;
        private MarketKlineUpdateEventPayload lastKlinePayload;

        @Override
        public void publishPriceTick(MarketPriceTickEventPayload payload) {
            this.lastPriceTickPayload = payload;
        }

        @Override
        public void publishKlineUpdate(MarketKlineUpdateEventPayload payload) {
            this.lastKlinePayload = payload;
        }
    }

    private static final class SequencedAnalyticsWriter extends RecordingAnalyticsWriter {
        private final List<String> sequence;

        private SequencedAnalyticsWriter(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public void writeQuoteTick(StandardQuote quote) {
            sequence.add("write-quote");
            super.writeQuoteTick(quote);
        }

        @Override
        public void writeKline(KlineSnapshot snapshot) {
            sequence.add("write-kline");
            super.writeKline(snapshot);
        }
    }

    private static final class SequencedPublisher extends RecordingPublisher {
        private final List<String> sequence;

        private SequencedPublisher(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public void publishPriceTick(MarketPriceTickEventPayload payload) {
            sequence.add("publish-tick");
            super.publishPriceTick(payload);
        }

        @Override
        public void publishKlineUpdate(MarketKlineUpdateEventPayload payload) {
            sequence.add("publish-kline");
            super.publishKlineUpdate(payload);
        }
    }

    private static final class FixedKlineAggregationService implements KlineAggregationService {
        @Override
        public KlineAggregationResult onQuote(StandardQuote quote) {
            KlineSnapshot snapshot = new KlineSnapshot(
                    quote.symbol(),
                    "1m",
                    quote.mid(),
                    quote.mid(),
                    quote.mid(),
                    quote.mid(),
                    BigDecimal.ZERO,
                    quote.ts().withSecond(0).withNano(0),
                    quote.ts().withSecond(59).withNano(0),
                    true
            );
            KlineSnapshot activeSnapshot = new KlineSnapshot(
                    snapshot.symbol(),
                    snapshot.interval(),
                    snapshot.open(),
                    snapshot.high(),
                    snapshot.low(),
                    snapshot.close(),
                    snapshot.volume(),
                    snapshot.openTime(),
                    snapshot.closeTime(),
                    false
            );
            return new KlineAggregationResult(List.of(activeSnapshot), List.of(snapshot));
        }
    }

    private static class RecordingWebSocketPushService extends MarketWebSocketPushService {
        private StandardQuote lastQuote;
        private final List<KlineSnapshot> klines = new ArrayList<>();

        private RecordingWebSocketPushService() {
            super(null, null, new MarketServiceProperties());
        }

        @Override
        public void publishQuote(StandardQuote quote) {
            this.lastQuote = quote;
        }

        @Override
        public void publishKline(KlineSnapshot snapshot) {
            this.klines.add(snapshot);
        }
    }

    private static final class SequencedWebSocketPushService extends RecordingWebSocketPushService {
        private final List<String> sequence;

        private SequencedWebSocketPushService(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public void publishQuote(StandardQuote quote) {
            sequence.add("publish-quote-ws");
            super.publishQuote(quote);
        }

        @Override
        public void publishKline(KlineSnapshot snapshot) {
            sequence.add(snapshot.isFinal() ? "publish-kline" : "publish-active-kline");
            super.publishKline(snapshot);
        }
    }
}
