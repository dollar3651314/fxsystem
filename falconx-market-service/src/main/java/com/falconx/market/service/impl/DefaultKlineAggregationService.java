package com.falconx.market.service.impl;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.KlineAggregationResult;
import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.service.KlineAggregationService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 默认 K 线聚合实现。
 *
 * <p>该实现把 Stage 6A 里的 “真实 K 线聚合落 ClickHouse” 收敛成一条清晰规则：
 *
 * <ol>
 *   <li>每条标准报价使用 `mid` 价（公允中间价）推进 K 线 OHLC，业界 MT4/MT5/OKX/Binance 同款；
 *       与前端 REST 历史 K 线和 WSS kline.{interval} 展示口径一致</li>
 *   <li>同一条报价会同时推进所有已配置周期，例如 `1m / 5m / 15m / 1h / 4h / 1d`</li>
 *   <li>只有当报价跨过当前周期窗口时，才返回上一根“已收盘”的 K 线快照</li>
 *   <li>未收盘中的 K 线仅保存在服务内存中，不提前写 ClickHouse，也不提前发事件</li>
 * </ol>
 *
 * <p>这样可以保证：
 *
 * <ul>
 *   <li>ClickHouse `kline` 只保存最终收盘数据，符合当前文档冻结的写入策略</li>
 *   <li>`market.kline.update` 只在真正收盘时进入 Outbox，不把高频中间态事件塞进消息总线</li>
 *   <li>当前实现只依赖外部标准 tick，也能稳定产出平台内部的收盘 K 线</li>
 * </ul>
 *
 * <p>当前 volume 采用“该周期内接收到的报价条数”作为平台定义量。
 * 这是因为一期外部报价流不提供真实成交量，但下游表结构和事件契约要求保留 volume 字段。
 */
@Service
public class DefaultKlineAggregationService implements KlineAggregationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultKlineAggregationService.class);
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final ZoneId zoneId;
    private final List<KlineInterval> intervals;
    private final ConcurrentMap<SymbolIntervalKey, KlineBucketState> buckets = new ConcurrentHashMap<>();

    public DefaultKlineAggregationService(MarketServiceProperties properties) {
        this.zoneId = ZoneId.of(properties.getKline().getTimezone());
        this.intervals = properties.getKline().getIntervals().stream()
                .map(KlineInterval::parse)
                .distinct()
                .sorted(Comparator.comparingInt(KlineInterval::sortOrder))
                .toList();
    }

    /**
     * 使用一条标准报价推进所有配置中的 K 线周期。
     *
     * <p>这里的核心约束有三条：
     *
     * <ul>
     *   <li>stale 报价不参与 K 线聚合，避免外部断连后的旧价格污染收盘线</li>
     *   <li>若报价推进到了新的时间窗口，则上一窗口生成最终 K 线并返回给上层落库/发事件</li>
     *   <li>若出现时间倒流的旧报价，则直接跳过，避免已经关闭的窗口被回写篡改</li>
     * </ul>
     *
     * @param quote 标准报价对象
     * @return 本次报价推进后的 active / finalized K 线
     */
    @Override
    public KlineAggregationResult onQuote(StandardQuote quote) {
        if (quote.stale()) {
            log.warn("market.kline.skip_stale symbol={} ts={}", quote.symbol(), quote.ts());
            return new KlineAggregationResult(List.of(), List.of());
        }

        List<KlineSnapshot> activeSnapshots = new ArrayList<>();
        List<KlineSnapshot> finalizedSnapshots = new ArrayList<>();
        for (KlineInterval interval : intervals) {
            SymbolIntervalKey key = new SymbolIntervalKey(quote.symbol(), interval.label());
            KlineBucketState nextState = buckets.compute(key, (ignored, currentState) ->
                    advanceBucket(interval, quote, currentState, finalizedSnapshots));
            if (nextState != null) {
                activeSnapshots.add(nextState.toActiveSnapshot());
            }
        }
        return new KlineAggregationResult(
                List.copyOf(activeSnapshots),
                List.copyOf(finalizedSnapshots)
        );
    }

    private KlineBucketState advanceBucket(KlineInterval interval,
                                           StandardQuote quote,
                                           KlineBucketState currentState,
                                           List<KlineSnapshot> finalizedSnapshots) {
        ZonedDateTime bucketOpenAtZone = interval.bucketOpen(quote.ts(), zoneId);
        OffsetDateTime bucketOpenTime = bucketOpenAtZone.toOffsetDateTime();
        OffsetDateTime bucketCloseTime = interval.bucketCloseInclusive(bucketOpenAtZone).toOffsetDateTime();
        // STAGE-12-GROUP-MARKUP Phase-2: K 线 OHLC 使用 mid 价（业界 MT4/MT5/OKX/Binance 同款）。
        // 前端 K 线图只消费 REST 历史 K 线与 WSS kline.{interval}，不再从 price.tick 做预览合成。
        BigDecimal price = quote.mid() != null ? quote.mid() : quote.bid();

        if (currentState == null) {
            return KlineBucketState.start(quote.symbol(), interval.label(), price, bucketOpenTime, bucketCloseTime);
        }

        // 相同窗口内的报价只更新 OHLC 和平台定义量，不触发落库。
        if (bucketOpenTime.isEqual(currentState.openTime())) {
            currentState.apply(price);
            return currentState;
        }

        // 时间前跳意味着上一根 K 线已经收盘。必须先把最终快照交给上层写 ClickHouse，
        // 再创建新的聚合窗口，保证“收盘落库”和“下一根起始”在同一条报价上顺序一致。
        if (bucketOpenTime.isAfter(currentState.openTime())) {
            KlineSnapshot finalizedSnapshot = currentState.toFinalSnapshot();
            log.info("market.kline.closed symbol={} interval={} openTime={} closeTime={} close={} volume={}",
                    finalizedSnapshot.symbol(),
                    finalizedSnapshot.interval(),
                    finalizedSnapshot.openTime(),
                    finalizedSnapshot.closeTime(),
                    finalizedSnapshot.close(),
                    finalizedSnapshot.volume());
            finalizedSnapshots.add(finalizedSnapshot);
            return KlineBucketState.start(quote.symbol(), interval.label(), price, bucketOpenTime, bucketCloseTime);
        }

        // 旧报价说明到达顺序出现回流。当前系统不支持回填已关闭窗口，因此只记录日志并丢弃，
        // 避免把历史报价重新写进已经收盘的 K 线。
        log.warn("market.kline.skip_out_of_order symbol={} interval={} quoteTs={} activeOpen={}",
                quote.symbol(),
                interval.label(),
                quote.ts(),
                currentState.openTime());
        return currentState;
    }

    private record SymbolIntervalKey(String symbol, String interval) {
    }

    private static final class KlineBucketState {

        private final String symbol;
        private final String interval;
        private final OffsetDateTime openTime;
        private final OffsetDateTime closeTime;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume;

        private KlineBucketState(String symbol,
                                 String interval,
                                 OffsetDateTime openTime,
                                 OffsetDateTime closeTime,
                                 BigDecimal open,
                                 BigDecimal high,
                                 BigDecimal low,
                                 BigDecimal close,
                                 BigDecimal volume) {
            this.symbol = symbol;
            this.interval = interval;
            this.openTime = openTime;
            this.closeTime = closeTime;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }

        private static KlineBucketState start(String symbol,
                                              String interval,
                                              BigDecimal price,
                                              OffsetDateTime openTime,
                                              OffsetDateTime closeTime) {
            return new KlineBucketState(
                    symbol,
                    interval,
                    openTime,
                    closeTime,
                    price,
                    price,
                    price,
                    price,
                    ONE
            );
        }

        private void apply(BigDecimal price) {
            if (price.compareTo(high) > 0) {
                high = price;
            }
            if (price.compareTo(low) < 0) {
                low = price;
            }
            close = price;
            volume = volume.add(ONE);
        }

        private KlineSnapshot toFinalSnapshot() {
            return new KlineSnapshot(
                    symbol,
                    interval,
                    open,
                    high,
                    low,
                    close,
                    volume,
                    openTime,
                    closeTime,
                    true
            );
        }

        private KlineSnapshot toActiveSnapshot() {
            return new KlineSnapshot(
                    symbol,
                    interval,
                    open,
                    high,
                    low,
                    close,
                    volume,
                    openTime,
                    closeTime,
                    false
            );
        }

        private OffsetDateTime openTime() {
            return openTime;
        }
    }

    private record KlineInterval(String label, int sortOrder) {

        private static KlineInterval parse(String raw) {
            String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "1m" -> new KlineInterval("1m", 1);
                case "5m" -> new KlineInterval("5m", 2);
                case "15m" -> new KlineInterval("15m", 3);
                case "1h" -> new KlineInterval("1h", 4);
                case "4h" -> new KlineInterval("4h", 5);
                case "1d" -> new KlineInterval("1d", 6);
                default -> throw new IllegalArgumentException("Unsupported kline interval: " + raw);
            };
        }

        private ZonedDateTime bucketOpen(OffsetDateTime quoteTime, ZoneId zoneId) {
            ZonedDateTime zonedQuoteTime = quoteTime.atZoneSameInstant(zoneId);
            return switch (label) {
                case "1m" -> zonedQuoteTime.withSecond(0).withNano(0);
                case "5m" -> zonedQuoteTime
                        .withMinute((zonedQuoteTime.getMinute() / 5) * 5)
                        .withSecond(0)
                        .withNano(0);
                case "15m" -> zonedQuoteTime
                        .withMinute((zonedQuoteTime.getMinute() / 15) * 15)
                        .withSecond(0)
                        .withNano(0);
                case "1h" -> zonedQuoteTime.withMinute(0).withSecond(0).withNano(0);
                case "4h" -> zonedQuoteTime
                        .withHour((zonedQuoteTime.getHour() / 4) * 4)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
                case "1d" -> zonedQuoteTime.toLocalDate().atStartOfDay(zoneId);
                default -> throw new IllegalStateException("Unsupported kline interval: " + label);
            };
        }

        private ZonedDateTime bucketCloseInclusive(ZonedDateTime openTime) {
            return switch (label) {
                case "1m" -> openTime.plusMinutes(1).minusSeconds(1);
                case "5m" -> openTime.plusMinutes(5).minusSeconds(1);
                case "15m" -> openTime.plusMinutes(15).minusSeconds(1);
                case "1h" -> openTime.plusHours(1).minusSeconds(1);
                case "4h" -> openTime.plusHours(4).minusSeconds(1);
                case "1d" -> openTime.plusDays(1).minusSeconds(1);
                default -> throw new IllegalStateException("Unsupported kline interval: " + label);
            };
        }
    }
}
