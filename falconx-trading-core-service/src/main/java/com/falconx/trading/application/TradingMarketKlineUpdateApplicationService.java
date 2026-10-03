package com.falconx.trading.application;

import com.falconx.trading.repository.TradingInboxRepository;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 交易核心消费收盘 K 线事件的最小应用服务。
 *
 * <p>Stage 6A 当前只要求形成正式 Kafka 消费链路与 owner 审计事实，
 * 不在交易域内额外派生新的业务状态。
 */
@Service
public class TradingMarketKlineUpdateApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingMarketKlineUpdateApplicationService.class);
    private static final String MARKET_SOURCE = "falconx-market-service";

    private final TradingInboxRepository tradingInboxRepository;

    public TradingMarketKlineUpdateApplicationService(TradingInboxRepository tradingInboxRepository) {
        this.tradingInboxRepository = tradingInboxRepository;
    }

    /**
     * 记录 `market.kline.update` 已被 trading-core 正式消费。
     *
     * @param eventId 事件 ID
     * @param symbol 交易品种
     * @param interval K 线周期
     * @param closeTime K 线收盘时间
     * @param payloadJson 原始 JSON payload
     * @return `true` 表示首次消费，`false` 表示重复事件
     */
    @Transactional
    public boolean recordConsumed(String eventId,
                                  String symbol,
                                  String interval,
                                  OffsetDateTime closeTime,
                                  String payloadJson) {
        log.info("trading.market.kline.update.request eventId={} symbol={} interval={} closeTime={}",
                eventId,
                symbol,
                interval,
                closeTime);
        boolean inserted = tradingInboxRepository.markProcessedIfAbsent(
                eventId,
                "market.kline.update",
                MARKET_SOURCE,
                payloadJson,
                closeTime
        );
        if (!inserted) {
            log.info("trading.market.kline.update.duplicate eventId={} symbol={} interval={} closeTime={}",
                    eventId,
                    symbol,
                    interval,
                    closeTime);
            return false;
        }
        log.info("trading.market.kline.update.completed eventId={} symbol={} interval={} closeTime={}",
                eventId,
                symbol,
                interval,
                closeTime);
        return true;
    }
}
