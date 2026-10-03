package com.falconx.market.application;

import com.falconx.market.analytics.MarketAnalyticsWriter;
import com.falconx.market.cache.MarketQuoteCacheWriter;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.contract.event.MarketKlineUpdateEventPayload;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.market.entity.KlineAggregationResult;
import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.entity.MarketQuoteQualityStatus;
import com.falconx.market.entity.MarketSymbol;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.producer.MarketEventPublisher;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.repository.MarketSymbolRepository;
import com.falconx.market.service.FxRateService;
import com.falconx.market.service.KlineAggregationService;
import com.falconx.market.service.MarketQuoteQualityGuardService;
import com.falconx.market.service.MarketQuoteMappingService;
import com.falconx.market.service.MarketTradingScheduleGuardService;
import com.falconx.market.service.QuoteStandardizationService;
import com.falconx.market.websocket.MarketWebSocketPushService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 市场数据接入应用服务。
 *
 * <p>该服务是 Stage 2A 最核心的应用层编排骨架。
 * 它负责把外部原始报价串成完整市场链路：
 *
 * <ol>
 *   <li>原始报价标准化</li>
 *   <li>写 Redis 最新价</li>
 *   <li>写 ClickHouse 报价历史</li>
 *   <li>发布价格事件</li>
 *   <li>更新 K 线聚合状态</li>
 *   <li>必要时写 ClickHouse K 线并发布 K 线事件</li>
 * </ol>
 *
 * <p>当前阶段已经接入真实 Redis、ClickHouse 与 Kafka。
 * 外部源接入由 Provider 层负责，应用层只处理统一原始报价对象。
 */
@Service
public class MarketDataIngestionApplicationService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataIngestionApplicationService.class);

    private final QuoteStandardizationService quoteStandardizationService;
    private final MarketQuoteCacheWriter marketQuoteCacheWriter;
    private final MarketAnalyticsWriter marketAnalyticsWriter;
    private final MarketEventPublisher marketEventPublisher;
    private final KlineAggregationService klineAggregationService;
    private final MarketWebSocketPushService marketWebSocketPushService;
    private final MarketQuoteQualityGuardService marketQuoteQualityGuardService;
    private final MarketTradingScheduleGuardService marketTradingScheduleGuardService;
    private final MarketQuoteMappingService marketQuoteMappingService;
    private final MarketQuoteDownstreamDispatcher quoteDownstreamDispatcher;
    private final FxRateService fxRateService;
    private final MarketSymbolRepository marketSymbolRepository;
    private final boolean asyncDownstreamEnabled;

    /**
     * STAGE-14A 内存缓存设计（AGENTS.md §3.9.1 三件套）：
     * - TTL: 无（永久缓存，category 为运行时不变属性）
     * - 刷新策略: 启动后首次 tick 触发 DB 查询，后续 O(1) 命中。admin 改 symbol category 后需重启服务刷新（admin 变更频率极低，可接受）
     * - cache miss 业务降级: 返回 SENTINEL_NON_FX 跳过分流（DB 异常时不写缓存，下次 tick 重试自愈）
     */
    private static final MarketSymbol SENTINEL_NON_FX = new MarketSymbol(
            null, null, null, -1, null, null, null, 0, 0, 0);
    private final ConcurrentHashMap<String, MarketSymbol> fxSymbolCache = new ConcurrentHashMap<>();

    public MarketDataIngestionApplicationService(QuoteStandardizationService quoteStandardizationService,
                                                 MarketQuoteCacheWriter marketQuoteCacheWriter,
                                                 MarketAnalyticsWriter marketAnalyticsWriter,
                                                 MarketEventPublisher marketEventPublisher,
                                                 KlineAggregationService klineAggregationService,
                                                 MarketWebSocketPushService marketWebSocketPushService,
                                                 MarketQuoteQualityGuardService marketQuoteQualityGuardService,
                                                 MarketTradingScheduleGuardService marketTradingScheduleGuardService,
                                                 MarketQuoteMappingService marketQuoteMappingService,
                                                 MarketQuoteDownstreamDispatcher quoteDownstreamDispatcher,
                                                 FxRateService fxRateService,
                                                 MarketSymbolRepository marketSymbolRepository,
                                                 @Value("${falconx.market.ingestion.async-downstream.enabled:true}") boolean asyncDownstreamEnabled) {
        this.quoteStandardizationService = quoteStandardizationService;
        this.marketQuoteCacheWriter = marketQuoteCacheWriter;
        this.marketAnalyticsWriter = marketAnalyticsWriter;
        this.marketEventPublisher = marketEventPublisher;
        this.klineAggregationService = klineAggregationService;
        this.marketWebSocketPushService = marketWebSocketPushService;
        this.marketQuoteQualityGuardService = marketQuoteQualityGuardService;
        this.marketTradingScheduleGuardService = marketTradingScheduleGuardService;
        this.marketQuoteMappingService = marketQuoteMappingService;
        this.quoteDownstreamDispatcher = quoteDownstreamDispatcher;
        this.fxRateService = fxRateService;
        this.marketSymbolRepository = marketSymbolRepository;
        this.asyncDownstreamEnabled = asyncDownstreamEnabled;
        if (fxRateService == null) {
            log.warn("market.ingestion.fx-rate-service.not-configured — FX symbol forwarding disabled");
        }
    }

    /**
     * 处理一条外部原始报价。
     *
     * <p>当前方法是 market-service Stage 2A 的最小可用主调用链。
     * 后续只允许在该链路内继续补全真实客户端实现，不应破坏其总体依赖方向。
     *
     * @param rawQuote 外部原始报价
     * @return 标准化后的报价对象，便于上层测试和调用方观察最终结果
     */
    public StandardQuote ingest(ExternalRawQuote rawQuote) {
        List<ExternalRawQuote> platformQuotes = marketQuoteMappingService.mapToPlatformQuotes(rawQuote);
        if (platformQuotes.isEmpty()) {
            log.warn("market.quote.mapping.dropped sourceSymbol={} source={} quoteTs={} reason=no-enabled-platform-mapping",
                    rawQuote.ticker(),
                    rawQuote.source(),
                    rawQuote.ts());
            return quoteStandardizationService.standardize(rawQuote);
        }
        StandardQuote firstQuote = null;
        for (ExternalRawQuote platformQuote : platformQuotes) {
            StandardQuote processed = ingestPlatformQuote(platformQuote);
            if (firstQuote == null) {
                firstQuote = processed;
            }
        }
        return firstQuote;
    }

    private StandardQuote ingestPlatformQuote(ExternalRawQuote rawQuote) {
        // 市场数据链路必须先完成标准化，再进入缓存、分析存储和事件分发。
        // 这样可以保证后续所有下游看到的是统一语义的 bid/ask/mark/stale 字段，
        // 避免每个消费者各自解释外部行情源原始格式。
        StandardQuote standardQuote = quoteStandardizationService.standardize(rawQuote);
        if (!marketTradingScheduleGuardService.isQuoteProcessingAllowed(standardQuote.symbol(), OffsetDateTime.now())) {
            StandardQuote closedQuote = standardQuote.withQuality(MarketQuoteQualityStatus.MARKET_CLOSED, "MARKET_CLOSED");
            log.debug("market.quote.skipped symbol={} source={} quoteTs={} quoteStatus={} reason={}",
                    closedQuote.symbol(),
                    closedQuote.source(),
                    closedQuote.ts(),
                    closedQuote.qualityStatus(),
                    closedQuote.qualityReason());
            return closedQuote;
        }

        standardQuote = marketQuoteQualityGuardService.evaluate(standardQuote);
        log.debug("market.quote.received symbol={} source={} quoteTs={} stale={} quoteStatus={} qualityReason={}",
                standardQuote.symbol(),
                standardQuote.source(),
                standardQuote.ts(),
                standardQuote.stale(),
                standardQuote.qualityStatus(),
                standardQuote.qualityReason());
        if (!standardQuote.executable()) {
            marketEventPublisher.publishPriceTick(toPriceTickPayload(standardQuote));
            log.warn("market.quote.non-executable symbol={} source={} quoteTs={} quoteStatus={} action=kafka-snapshot-only reason={}",
                    standardQuote.symbol(),
                    standardQuote.source(),
                    standardQuote.ts(),
                    standardQuote.qualityStatus(),
                    standardQuote.qualityReason());
            return standardQuote;
        }
        // ===== 同步链路（必须）：Redis 写 + K 线状态机推进 =====
        // 这两步是其他业务的同读依赖（trading-core 同读 quote / chart 取活动 K 线快照），
        // 必须在 ingest() 返回前完成。
        marketQuoteCacheWriter.writeLatestQuote(standardQuote);
        KlineAggregationResult aggregationResult = klineAggregationService.onQuote(standardQuote);

        // ===== STAGE-14A：FX 汇率分支 =====
        // 若 symbol category=2 (FX)，转发到 FxRateService 更新内存快照 + Redis。
        // 在主路径（Redis price + K 线推进）完成后调用，不干扰现有同步链路。
        // FxRateService.acceptTick 内部 ConcurrentHashMap + Redis.set，O(1) 不阻塞。
        maybeForwardToFxRateService(standardQuote);
        List<KlineSnapshot> finalizedSnapshots = aggregationResult.finalizedSnapshots();

        if (asyncDownstreamEnabled) {
            // ===== 异步链路（S5 / 性能报告 §4 P0）：按 symbol 分区 dispatcher =====
            // 同 symbol 任务严格 FIFO；不同 symbol 并行；CallerRunsPolicy 背压保证不丢。
            // 每个下游独立 try-catch：任一失败仅 warn，不影响其他下游。
            final StandardQuote dispatchQuote = standardQuote;
            quoteDownstreamDispatcher.dispatch(standardQuote.symbol(),
                    () -> dispatchDownstreamAsync(dispatchQuote, aggregationResult, finalizedSnapshots));
        } else {
            // ===== 同步回退路径（feature flag = false）=====
            marketAnalyticsWriter.writeQuoteTick(standardQuote);
            marketEventPublisher.publishPriceTick(toPriceTickPayload(standardQuote));
            marketWebSocketPushService.publishQuote(standardQuote);
            aggregationResult.activeSnapshots().forEach(marketWebSocketPushService::publishKline);
            finalizedSnapshots.forEach(snapshot -> {
                marketEventPublisher.publishKlineUpdate(toKlinePayload(snapshot));
                marketAnalyticsWriter.writeKline(snapshot);
                marketWebSocketPushService.publishKline(snapshot);
            });
        }

        log.debug("market.ingestion.completed symbol={} ts={} stale={}",
                standardQuote.symbol(),
                standardQuote.ts(),
                standardQuote.stale());
        return standardQuote;
    }

    /**
     * STAGE-14A：若 symbol 是 FX（category == 2），转发到 FxRateService。
     *
     * <p>symbol 元数据通过 {@link #fxSymbolCache} 懒加载：第一次遇到某 symbol 时查一次 DB，
     * 此后从内存命中。非 FX symbol 以 SENTINEL_NON_FX 填充，跳过后续分流。
     *
     * <p>DB 查询异常时 {@link #lookupFxSymbol} 返回 null，本次跳过 FX 分支且不写缓存，
     * 保证主路径（Redis price + K 线推进 + 异步下游）不被中断，下次 tick 重试自愈。
     */
    private void maybeForwardToFxRateService(StandardQuote quote) {
        if (fxRateService == null) {
            return;
        }
        MarketSymbol sym = fxSymbolCache.get(quote.symbol());
        if (sym == null) {
            MarketSymbol fresh = lookupFxSymbol(quote.symbol());
            if (fresh == null) {
                // DB 异常 — 本次跳过 FX 分支，不污染缓存，下次 tick 重试自愈
                return;
            }
            // putIfAbsent 保证并发安全：若另一线程已写入，使用其结果
            MarketSymbol existing = fxSymbolCache.putIfAbsent(quote.symbol(), fresh);
            sym = existing != null ? existing : fresh;
        }
        if (sym == SENTINEL_NON_FX || sym.category() != 2) {
            return;
        }
        try {
            fxRateService.acceptTick(new FxRateSnapshotPayload(
                    sym.baseCurrency(),
                    sym.quoteCurrency(),
                    quote.mid(),
                    quote.ts().toInstant().toEpochMilli(),
                    sym.lpCode() != null ? sym.lpCode() : quote.source(),
                    quote.symbol()
            ));
        } catch (RuntimeException ex) {
            log.warn("market.ingestion.fx-rate.failed symbol={} reason={}", quote.symbol(), ex.toString());
        }
    }

    /**
     * 按 platformSymbol 查询 t_symbol 元数据，供 FX 分流懒加载使用。
     * 查不到或仓储不可用时返回 {@link #SENTINEL_NON_FX}，避免重复 DB 查询。
     * DB 抖动导致 RuntimeException 时返回 null（调用方不写缓存，下次重试自愈）。
     */
    private MarketSymbol lookupFxSymbol(String platformSymbol) {
        if (marketSymbolRepository == null) {
            return SENTINEL_NON_FX;
        }
        try {
            return marketSymbolRepository.findBySymbol(platformSymbol).orElse(SENTINEL_NON_FX);
        } catch (RuntimeException ex) {
            log.warn("market.ingestion.fx-symbol-lookup.failed symbol={} reason={}",
                    platformSymbol, ex.toString());
            // 返回 null 告诉 caller 跳过缓存写入，让下次 tick 重试自愈
            return null;
        }
    }

    private MarketPriceTickEventPayload toPriceTickPayload(StandardQuote quote) {
        return new MarketPriceTickEventPayload(
                quote.symbol(),
                quote.bid(),
                quote.ask(),
                quote.mid(),
                quote.mark(),
                quote.ts(),
                quote.source(),
                quote.stale(),
                quote.qualityStatus() == null ? null : quote.qualityStatus().name(),
                quote.qualityReason()
        );
    }

    /**
     * 异步执行 ingestPlatformQuote 的 4 类下游：ClickHouse 入队 + Kafka publish +
     * WebSocket 推 quote/active K 线 + finalized K 线 3 步。
     *
     * <p>每个下游独立 try-catch 实现异常隔离：任一失败仅 warn 不重抛。
     * 在 {@link MarketQuoteDownstreamDispatcher} 的 worker 线程上执行，同 symbol FIFO 由 dispatcher 保证。
     */
    private void dispatchDownstreamAsync(StandardQuote quote,
                                         KlineAggregationResult aggregationResult,
                                         List<KlineSnapshot> finalizedSnapshots) {
        try {
            marketAnalyticsWriter.writeQuoteTick(quote);
        } catch (RuntimeException ex) {
            log.warn("market.ingestion.async.clickhouse-tick.failed symbol={} reason={}",
                    quote.symbol(), ex.toString());
        }
        try {
            marketEventPublisher.publishPriceTick(toPriceTickPayload(quote));
        } catch (RuntimeException ex) {
            log.warn("market.ingestion.async.kafka-price-tick.failed symbol={} reason={}",
                    quote.symbol(), ex.toString());
        }
        try {
            marketWebSocketPushService.publishQuote(quote);
        } catch (RuntimeException ex) {
            log.warn("market.ingestion.async.ws-quote.failed symbol={} reason={}",
                    quote.symbol(), ex.toString());
        }
        try {
            aggregationResult.activeSnapshots().forEach(marketWebSocketPushService::publishKline);
        } catch (RuntimeException ex) {
            log.warn("market.ingestion.async.ws-active-kline.failed symbol={} reason={}",
                    quote.symbol(), ex.toString());
        }
        for (KlineSnapshot snapshot : finalizedSnapshots) {
            try {
                marketEventPublisher.publishKlineUpdate(toKlinePayload(snapshot));
            } catch (RuntimeException ex) {
                log.warn("market.ingestion.async.kafka-kline-update.failed symbol={} interval={} reason={}",
                        snapshot.symbol(), snapshot.interval(), ex.toString());
            }
            try {
                marketAnalyticsWriter.writeKline(snapshot);
            } catch (RuntimeException ex) {
                log.warn("market.ingestion.async.clickhouse-kline.failed symbol={} interval={} reason={}",
                        snapshot.symbol(), snapshot.interval(), ex.toString());
            }
            try {
                marketWebSocketPushService.publishKline(snapshot);
            } catch (RuntimeException ex) {
                log.warn("market.ingestion.async.ws-finalized-kline.failed symbol={} interval={} reason={}",
                        snapshot.symbol(), snapshot.interval(), ex.toString());
            }
        }
    }

    private MarketKlineUpdateEventPayload toKlinePayload(KlineSnapshot snapshot) {
        return new MarketKlineUpdateEventPayload(
                snapshot.symbol(),
                snapshot.interval(),
                snapshot.open(),
                snapshot.high(),
                snapshot.low(),
                snapshot.close(),
                snapshot.volume(),
                snapshot.openTime(),
                snapshot.closeTime(),
                snapshot.isFinal()
        );
    }
}
