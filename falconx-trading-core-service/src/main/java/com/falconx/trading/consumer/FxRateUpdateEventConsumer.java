package com.falconx.trading.consumer;

import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.trading.service.FxRateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * STAGE-14B Task 3：FX 汇率更新事件消费者（领域层，无 Kafka 框架耦合）。
 *
 * <p>接收来自 {@code TradingKafkaEventListener#onMarketFxRateUpdate} 的 FX 更新事件，
 * 委托给 {@link FxRateService#acceptUpdate} 写入内存缓存。
 *
 * <p>单条失败隔离：异常不向外传播，仅 warn 日志，不停止 Kafka listener。
 *
 * <p>线程模型：{@code acceptUpdate} 内部仅 {@code ConcurrentHashMap.put}，为 O(1) 非阻塞，
 * 可在 Kafka 回调线程直接执行（无需切换到自管线程，参考 {@code MarketPriceTickEventConsumer} 模式注释）。
 */
@Component
public class FxRateUpdateEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(FxRateUpdateEventConsumer.class);

    private final FxRateService fxRateService;

    public FxRateUpdateEventConsumer(FxRateService fxRateService) {
        this.fxRateService = fxRateService;
    }

    /**
     * 消费一条 FX rate 更新事件。
     *
     * @param eventId 事件 ID
     * @param payload FX 快照 payload
     */
    public void consume(String eventId, FxRateSnapshotPayload payload) {
        try {
            fxRateService.acceptUpdate(
                    payload.baseCurrency(),
                    payload.quoteCurrency(),
                    payload.rate(),
                    payload.eventTimeMillis()
            );
            log.debug("trading.fx.kafka.consume.ok eventId={} pair={}/{} rate={}",
                    eventId, payload.baseCurrency(), payload.quoteCurrency(), payload.rate());
        } catch (RuntimeException ex) {
            // 隔离：单条失败不停 listener
            log.warn("trading.fx.kafka.consume.failed eventId={} pair={}/{} reason={}",
                    eventId, payload.baseCurrency(), payload.quoteCurrency(), ex.getMessage(), ex);
        }
    }
}
