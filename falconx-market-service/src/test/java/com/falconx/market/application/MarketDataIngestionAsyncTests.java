package com.falconx.market.application;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.market.analytics.MarketAnalyticsWriter;
import com.falconx.market.cache.MarketQuoteCacheWriter;
import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.KlineAggregationResult;
import com.falconx.market.entity.MarketQuoteQualityStatus;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.producer.MarketEventPublisher;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.service.KlineAggregationService;
import com.falconx.market.service.MarketQuoteMappingService;
import com.falconx.market.service.MarketQuoteQualityGuardService;
import com.falconx.market.service.MarketTradingScheduleGuardService;
import com.falconx.market.service.QuoteStandardizationService;
import com.falconx.market.service.impl.DefaultQuoteStandardizationService;
import com.falconx.market.websocket.MarketWebSocketPushService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sprint 3 S5 Task 4：异步路径 + 同步回退 + 异常隔离单测。
 */
class MarketDataIngestionAsyncTests {

    private static final ExternalRawQuote RAW = new ExternalRawQuote(
            "EURUSD",
            new BigDecimal("1.08321"),
            new BigDecimal("1.08331"),
            OffsetDateTime.now(),
            "TM_QUOTE"
    );

    @Test
    void asyncEnabled_dispatchesDownstreamToDispatcher() {
        MarketQuoteCacheWriter cache = mock(MarketQuoteCacheWriter.class);
        MarketAnalyticsWriter analytics = mock(MarketAnalyticsWriter.class);
        MarketEventPublisher publisher = mock(MarketEventPublisher.class);
        KlineAggregationService kline = mock(KlineAggregationService.class);
        MarketWebSocketPushService ws = mock(MarketWebSocketPushService.class);
        MarketQuoteQualityGuardService quality = quote -> quote;
        MarketTradingScheduleGuardService schedule = (symbol, now) -> true;
        MarketQuoteMappingService mapping = new IdentityQuoteMappingService();
        MarketQuoteDownstreamDispatcher dispatcher = mock(MarketQuoteDownstreamDispatcher.class);

        doAnswer(inv -> {
            Runnable task = inv.getArgument(1);
            task.run();
            return null;
        }).when(dispatcher).dispatch(any(), any());

        when(kline.onQuote(any())).thenReturn(new KlineAggregationResult(List.of(), List.of()));

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                newStandardizer(), cache, analytics, publisher, kline, ws,
                quality, schedule, mapping, dispatcher, null, null, true);

        service.ingest(RAW);

        // 同步链路必须发生
        verify(cache, times(1)).writeLatestQuote(any());
        verify(kline, times(1)).onQuote(any());

        // dispatcher 按 symbol 分片调度一次
        verify(dispatcher, times(1)).dispatch(eq("EURUSD"), any());

        // dispatcher 同步执行后下游被调
        verify(analytics, times(1)).writeQuoteTick(any());
        verify(publisher, times(1)).publishPriceTick(any());
        verify(ws, times(1)).publishQuote(any());
    }

    @Test
    void asyncDisabled_fallbackToSyncPath_noDispatcherCall() {
        MarketQuoteCacheWriter cache = mock(MarketQuoteCacheWriter.class);
        MarketAnalyticsWriter analytics = mock(MarketAnalyticsWriter.class);
        MarketEventPublisher publisher = mock(MarketEventPublisher.class);
        KlineAggregationService kline = mock(KlineAggregationService.class);
        MarketWebSocketPushService ws = mock(MarketWebSocketPushService.class);
        MarketQuoteQualityGuardService quality = quote -> quote;
        MarketTradingScheduleGuardService schedule = (symbol, now) -> true;
        MarketQuoteMappingService mapping = new IdentityQuoteMappingService();
        MarketQuoteDownstreamDispatcher dispatcher = mock(MarketQuoteDownstreamDispatcher.class);

        when(kline.onQuote(any())).thenReturn(new KlineAggregationResult(List.of(), List.of()));

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                newStandardizer(), cache, analytics, publisher, kline, ws,
                quality, schedule, mapping, dispatcher, null, null, false);

        service.ingest(RAW);

        verify(dispatcher, never()).dispatch(any(), any());

        // 全部同步路径
        verify(cache, times(1)).writeLatestQuote(any());
        verify(analytics, times(1)).writeQuoteTick(any());
        verify(publisher, times(1)).publishPriceTick(any());
        verify(ws, times(1)).publishQuote(any());
    }

    @Test
    void asyncEnabled_oneDownstreamFails_othersStillExecute() {
        MarketQuoteCacheWriter cache = mock(MarketQuoteCacheWriter.class);
        MarketAnalyticsWriter analytics = mock(MarketAnalyticsWriter.class);
        MarketEventPublisher publisher = mock(MarketEventPublisher.class);
        KlineAggregationService kline = mock(KlineAggregationService.class);
        MarketWebSocketPushService ws = mock(MarketWebSocketPushService.class);
        MarketQuoteQualityGuardService quality = quote -> quote;
        MarketTradingScheduleGuardService schedule = (symbol, now) -> true;
        MarketQuoteMappingService mapping = new IdentityQuoteMappingService();
        MarketQuoteDownstreamDispatcher dispatcher = mock(MarketQuoteDownstreamDispatcher.class);

        doAnswer(inv -> {
            Runnable task = inv.getArgument(1);
            task.run();
            return null;
        }).when(dispatcher).dispatch(any(), any());

        when(kline.onQuote(any())).thenReturn(new KlineAggregationResult(List.of(), List.of()));

        // ClickHouse 异常
        doAnswer(inv -> { throw new RuntimeException("clickhouse-down"); })
                .when(analytics).writeQuoteTick(any());

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                newStandardizer(), cache, analytics, publisher, kline, ws,
                quality, schedule, mapping, dispatcher, null, null, true);

        // ingest 不应抛
        StandardQuote result = service.ingest(RAW);
        assertNotNull(result);

        // Kafka / WebSocket 仍然执行
        verify(publisher, times(1)).publishPriceTick(any());
        verify(ws, times(1)).publishQuote(any());
    }

    @Test
    void asyncEnabled_nonExecutableQuote_skipsDispatcher() {
        // executable=false（NO_QUOTE）走 early return，dispatcher 不被触发
        MarketQuoteCacheWriter cache = mock(MarketQuoteCacheWriter.class);
        MarketAnalyticsWriter analytics = mock(MarketAnalyticsWriter.class);
        MarketEventPublisher publisher = mock(MarketEventPublisher.class);
        KlineAggregationService kline = mock(KlineAggregationService.class);
        MarketWebSocketPushService ws = mock(MarketWebSocketPushService.class);
        MarketQuoteQualityGuardService quality = quote -> quote.withQuality(
                MarketQuoteQualityStatus.NO_QUOTE, "UNCHANGED_TOO_LONG");
        MarketTradingScheduleGuardService schedule = (symbol, now) -> true;
        MarketQuoteMappingService mapping = new IdentityQuoteMappingService();
        MarketQuoteDownstreamDispatcher dispatcher = mock(MarketQuoteDownstreamDispatcher.class);

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                newStandardizer(), cache, analytics, publisher, kline, ws,
                quality, schedule, mapping, dispatcher, null, null, true);

        service.ingest(RAW);

        verify(dispatcher, never()).dispatch(any(), any());
        // 非可执行报价只走 Kafka snapshot
        verify(publisher, times(1)).publishPriceTick(any());
        verify(cache, never()).writeLatestQuote(any());
    }

    private QuoteStandardizationService newStandardizer() {
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getStale().setMaxAge(Duration.ofSeconds(5));
        return new DefaultQuoteStandardizationService(properties);
    }

    private static final class IdentityQuoteMappingService implements MarketQuoteMappingService {

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
}
