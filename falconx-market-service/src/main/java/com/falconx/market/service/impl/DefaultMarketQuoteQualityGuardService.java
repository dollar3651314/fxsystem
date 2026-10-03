package com.falconx.market.service.impl;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketQuoteQualityStatus;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.service.MarketQuoteQualityGuardService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 默认行情质量保护实现。
 *
 * <p>依次执行以下检测（任一触发即返回）：
 * <ol>
 *   <li>NO_QUOTE：bid/ask 连续不变超过 unchanged-max-age</li>
 *   <li>TICK_JUMP_TOO_LARGE：单 tick 跳变超过 max-tick-jump-rate（相对上一 mid）</li>
 *   <li>SHORT_TERM_VOLATILITY_EXCEEDED：滑动窗口内 (max-min)/min 超过 max-volatility-rate</li>
 * </ol>
 */
@Service
public class DefaultMarketQuoteQualityGuardService implements MarketQuoteQualityGuardService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMarketQuoteQualityGuardService.class);
    /** symbol 状态 24h 未刷新视为已下线 / 长时间停盘，清理释放堆内存。 */
    private static final Duration STALE_SYMBOL_THRESHOLD = Duration.ofHours(24);

    private final MarketServiceProperties properties;
    private final ConcurrentMap<String, LastQuoteState> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PriceHistory> priceHistories = new ConcurrentHashMap<>();

    public DefaultMarketQuoteQualityGuardService(MarketServiceProperties properties) {
        this.properties = properties;
    }

    /**
     * 周期性清理长时间未刷新的 symbol entry，防止 states / priceHistories map 无界增长。
     * 2026-05-20 引入：长寿命进程内交易对下线 / 停盘的 symbol 永不会被新报价刷新，
     * 原实现没有清理机制，长期累积上千个死 entry 占堆。1 小时跑一次成本极低。
     */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void cleanupStaleSymbols() {
        OffsetDateTime threshold = OffsetDateTime.now().minus(STALE_SYMBOL_THRESHOLD);
        int beforeSize = states.size();
        states.entrySet().removeIf(e -> {
            boolean stale = e.getValue().lastChangedAt().isBefore(threshold);
            if (stale) {
                priceHistories.remove(e.getKey());
            }
            return stale;
        });
        int afterSize = states.size();
        if (beforeSize != afterSize) {
            log.info("market.quote.quality.cleanup removed={} remaining={} thresholdHours={}",
                    beforeSize - afterSize, afterSize, STALE_SYMBOL_THRESHOLD.toHours());
        }
    }

    @Override
    public StandardQuote evaluate(StandardQuote quote) {
        if (quote == null || quote.stale()) {
            return quote;
        }
        // 已被标准化阶段判定为 ABNORMAL，不再重复评估
        if (quote.qualityStatus() == MarketQuoteQualityStatus.ABNORMAL) {
            return quote;
        }

        OffsetDateTime now = OffsetDateTime.now();
        MarketServiceProperties.QuoteQuality quality = properties.getQuoteQuality();
        BigDecimal currentMid = quote.mid();

        // 原子更新 NO_QUOTE 状态，并捕获上一次的 mid 用于 tick 跳变检测
        BigDecimal[] prevMidRef = {null};
        LastQuoteState state = states.compute(quote.symbol(), (sym, previous) -> {
            prevMidRef[0] = previous != null ? previous.mid() : null;
            if (previous == null || priceChanged(previous, quote)) {
                return new LastQuoteState(quote.bid(), quote.ask(), currentMid, now);
            }
            return previous;
        });

        // NO_QUOTE：价格长时间不变
        if (state != null && !priceChanged(state, quote)
                && Duration.between(state.lastChangedAt(), now).compareTo(quality.getUnchangedMaxAge()) > 0) {
            return quote.withQuality(MarketQuoteQualityStatus.NO_QUOTE, "UNCHANGED_TOO_LONG");
        }

        // Tick 跳变：当前 mid 与上一 mid 之差超过比率阈值
        BigDecimal maxTickJumpRate = quality.getMaxTickJumpRate();
        BigDecimal prevMid = prevMidRef[0];
        if (maxTickJumpRate != null && maxTickJumpRate.signum() > 0
                && prevMid != null && prevMid.signum() > 0 && currentMid != null) {
            BigDecimal jumpRate = currentMid.subtract(prevMid).abs().divide(prevMid, 8, RoundingMode.DOWN);
            if (jumpRate.compareTo(maxTickJumpRate) > 0) {
                return quote.withQuality(MarketQuoteQualityStatus.ABNORMAL, "TICK_JUMP_TOO_LARGE");
            }
        }

        // 短时波动：滑动窗口内 (max-min)/min 超过比率阈值
        BigDecimal maxVolatilityRate = quality.getMaxVolatilityRate();
        if (maxVolatilityRate != null && maxVolatilityRate.signum() > 0 && currentMid != null) {
            PriceHistory history = priceHistories.computeIfAbsent(quote.symbol(), k -> new PriceHistory());
            Optional<BigDecimal> volatilityRate = history.addAndComputeRate(
                    currentMid, now.toInstant().toEpochMilli(), quality.getVolatilityWindow().toMillis());
            if (volatilityRate.isPresent() && volatilityRate.get().compareTo(maxVolatilityRate) > 0) {
                return quote.withQuality(MarketQuoteQualityStatus.ABNORMAL, "SHORT_TERM_VOLATILITY_EXCEEDED");
            }
        }

        return quote.withQuality(MarketQuoteQualityStatus.FRESH, null);
    }

    private boolean priceChanged(LastQuoteState state, StandardQuote quote) {
        return compare(state.bid(), quote.bid()) != 0 || compare(state.ask(), quote.ask()) != 0;
    }

    private int compare(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return Objects.equals(left, right) ? 0 : 1;
        }
        return left.compareTo(right);
    }

    private record LastQuoteState(BigDecimal bid, BigDecimal ask, BigDecimal mid, OffsetDateTime lastChangedAt) {
    }

    /**
     * 滑动窗口价格历史，用于短时波动检测。
     * 每个 symbol 各持有一个独立实例。
     */
    private static final class PriceHistory {

        private record Entry(long epochMillis, BigDecimal mid) {
        }

        private final ArrayDeque<Entry> entries = new ArrayDeque<>();

        /**
         * 记录新价格并计算窗口内波动率。
         *
         * @param mid         当前 mid 价
         * @param nowMillis   当前时间（毫秒）
         * @param windowMillis 窗口时长（毫秒）
         * @return 窗口内 (max-min)/min，窗口内少于 2 条记录时返回 empty
         */
        synchronized Optional<BigDecimal> addAndComputeRate(BigDecimal mid, long nowMillis, long windowMillis) {
            entries.addLast(new Entry(nowMillis, mid));
            long cutoff = nowMillis - windowMillis;
            while (!entries.isEmpty() && entries.peekFirst().epochMillis() < cutoff) {
                entries.pollFirst();
            }
            if (entries.size() < 2) {
                return Optional.empty();
            }
            BigDecimal min = null;
            BigDecimal max = null;
            for (Entry e : entries) {
                if (min == null || e.mid().compareTo(min) < 0) min = e.mid();
                if (max == null || e.mid().compareTo(max) > 0) max = e.mid();
            }
            if (min == null || min.signum() <= 0) {
                return Optional.empty();
            }
            return Optional.of(max.subtract(min).divide(min, 8, RoundingMode.DOWN));
        }
    }
}
