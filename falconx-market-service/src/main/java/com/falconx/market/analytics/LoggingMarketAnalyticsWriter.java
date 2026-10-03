package com.falconx.market.analytics;

import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.entity.StandardQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * ClickHouse 写入骨架实现。
 *
 * <p>当前实现只保留日志节点，不直接连接 ClickHouse。
 * 这样可以在不引入运行时外部依赖的前提下，先冻结应用层的写入调用链和职责边界。
 */
@Component
@Profile("stub")
public class LoggingMarketAnalyticsWriter implements MarketAnalyticsWriter {

    private static final Logger log = LoggerFactory.getLogger(LoggingMarketAnalyticsWriter.class);

    /**
     * 记录报价历史写入动作。
     *
     * @param quote 标准报价对象
     */
    @Override
    public void writeQuoteTick(StandardQuote quote) {
        log.info("market.analytics.quote.write symbol={} ts={}", quote.symbol(), quote.ts());
    }

    /**
     * 记录 K 线写入动作。
     *
     * @param snapshot 已聚合的 K 线快照
     */
    @Override
    public void writeKline(KlineSnapshot snapshot) {
        log.info("market.analytics.kline.write symbol={} interval={} closeTime={}",
                snapshot.symbol(),
                snapshot.interval(),
                snapshot.closeTime());
    }
}
