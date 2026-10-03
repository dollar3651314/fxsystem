package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 交易域内部行情快照。
 *
 * <p>该对象用于承接 `market-service` 推送过来的标准价格事件，
 * 作为交易核心服务后续下单、风控和强平计算的内部输入。
 * 当前默认由 owner 仓储持久化到真实 Redis 快照读写链路。
 *
 * <p>注意：`mark` 字段当前仅保留为兼容快照字段；
 * 逐仓估值、TP/SL、强平和用户侧持仓估值一律按持仓方向从 `bid / ask` 解析有效标记价。
 */
public record TradingQuoteSnapshot(
        String symbol,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal mark,
        OffsetDateTime ts,
        String source,
        boolean stale,
        TradingQuoteQualityStatus qualityStatus,
        String qualityReason
) {
    public TradingQuoteSnapshot(String symbol,
                                BigDecimal bid,
                                BigDecimal ask,
                                BigDecimal mark,
                                OffsetDateTime ts,
                                String source,
                                boolean stale) {
        this(
                symbol,
                bid,
                ask,
                mark,
                ts,
                source,
                stale,
                stale ? TradingQuoteQualityStatus.STALE : TradingQuoteQualityStatus.FRESH,
                stale ? "QUOTE_TIME_DRIFT_EXCEEDED" : null
        );
    }

    public boolean executable() {
        return qualityStatus == null
                ? !stale
                : qualityStatus.executable() && !stale;
    }
}
