package com.falconx.market.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * market-service 内部标准报价对象。
 *
 * <p>该对象对应市场数据契约中的标准报价模型，
 * 用于在 Provider、标准化服务、缓存、分析写入和事件发布之间传递统一语义的数据。
 * 该类属于 market-service 内部 owner，不对外作为 contract 暴露。
 */
public record StandardQuote(
        String symbol,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal mid,
        BigDecimal mark,
        OffsetDateTime ts,
        String source,
        boolean stale,
        MarketQuoteQualityStatus qualityStatus,
        String qualityReason
) {
    public StandardQuote(String symbol,
                         BigDecimal bid,
                         BigDecimal ask,
                         BigDecimal mid,
                         BigDecimal mark,
                         OffsetDateTime ts,
                         String source,
                         boolean stale) {
        this(
                symbol,
                bid,
                ask,
                mid,
                mark,
                ts,
                source,
                stale,
                stale ? MarketQuoteQualityStatus.STALE : MarketQuoteQualityStatus.FRESH,
                stale ? "QUOTE_TIME_DRIFT_EXCEEDED" : null
        );
    }

    public StandardQuote withQuality(MarketQuoteQualityStatus status, String reason) {
        return new StandardQuote(
                symbol,
                bid,
                ask,
                mid,
                mark,
                ts,
                source,
                status.stale(),
                status,
                reason
        );
    }

    public boolean executable() {
        return qualityStatus == null
                ? !stale
                : qualityStatus.executable() && !stale;
    }

    /**
     * 应用用户组 Layer 2 加点，返回新对象。
     *
     * <p>新 bid = bid + bidExtra, 新 ask = ask + askExtra, 新 mid = (newBid + newAsk) / 2。
     * mark 跟随 mid（保持与现有 standardize 一致）。零加点直接返回 this，避免无意义对象分配。
     */
    public StandardQuote withExtraMarkup(BigDecimal bidExtra, BigDecimal askExtra) {
        if (bidExtra == null) bidExtra = BigDecimal.ZERO;
        if (askExtra == null) askExtra = BigDecimal.ZERO;
        if (bidExtra.signum() == 0 && askExtra.signum() == 0) {
            return this;
        }
        BigDecimal newBid = bid.add(bidExtra);
        BigDecimal newAsk = ask.add(askExtra);
        BigDecimal newMid = newBid.add(newAsk).divide(BigDecimal.valueOf(2), 8, java.math.RoundingMode.DOWN);
        return new StandardQuote(
                symbol,
                newBid,
                newAsk,
                newMid,
                newMid,
                ts,
                source,
                stale,
                qualityStatus,
                qualityReason
        );
    }
}
