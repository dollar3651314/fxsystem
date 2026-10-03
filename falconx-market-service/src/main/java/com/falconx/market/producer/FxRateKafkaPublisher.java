package com.falconx.market.producer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.service.FxRateService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1Hz 节流发布 FX rate 快照到 Kafka topic {@code falconx.market.fx.rate.update}.
 *
 * <p>消费者: trading-core-service / console-service.
 * <p>节流策略: 每秒一次 scan {@link FxRateService#snapshotAll()} 全量发布,
 * 与品种 tick (price.tick topic) 解耦, FX rate 变化频率低于品种 tick.
 *
 * <p>发送模式: 与 {@link KafkaMarketEventPublisher} 保持一致,
 * 使用 {@link KafkaTemplate}&lt;String, String&gt; + Jackson 序列化 +
 * {@link KafkaEventMessageSupport#buildJsonMessage} 携带标准 Kafka 事件头（eventId/eventType/source/traceId）.
 *
 * <p>节流间隔可通过 {@code falconx.market.fx.kafka-throttle-interval-ms}（单位 ms）覆盖，默认 1000ms.
 */
@Component
public class FxRateKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(FxRateKafkaPublisher.class);

    private static final String EVENT_TYPE = "market.fx.rate.update";
    private static final String EVENT_SOURCE = "falconx-market-service";

    private final FxRateService fxRateService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MarketServiceProperties props;
    private final IdGenerator idGenerator;

    public FxRateKafkaPublisher(FxRateService fxRateService,
                                KafkaTemplate<String, String> kafkaTemplate,
                                ObjectMapper objectMapper,
                                MarketServiceProperties props,
                                IdGenerator idGenerator) {
        this.fxRateService = fxRateService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
        this.idGenerator = idGenerator;
    }

    /**
     * 1Hz scan + 发布所有 FX rate.
     *
     * <p>每秒执行一次，从 {@link FxRateService#snapshotAll()} 取全量 FX rate 快照，
     * 每条单独发一条 Kafka 消息，key = base:quote。
     * 每条消息独立 try/catch，单 symbol 失败不影响其他 symbol 继续发布。
     * 发送采用同步等待（最长 5 秒），对齐 {@link KafkaMarketEventPublisher} 的处理方式。
     */
    @Scheduled(fixedRateString = "${falconx.market.fx.kafka-throttle-interval-ms:1000}")
    public void publish() {
        String topic = props.getKafka().getFxRateUpdateTopic();
        for (FxRateSnapshotPayload p : fxRateService.snapshotAll()) {
            try {
                String key = p.baseCurrency() + ":" + p.quoteCurrency();
                String payloadJson = toJson(p);
                String eventId = "evt-" + idGenerator.nextId();
                kafkaTemplate.send(KafkaEventMessageSupport.buildJsonMessage(
                        topic,
                        key,
                        payloadJson,
                        eventId,
                        EVENT_TYPE,
                        EVENT_SOURCE
                )).get(5, TimeUnit.SECONDS);
                log.debug("market.fx.rate.publish topic={} key={} eventId={}", topic, key, eventId);
            } catch (RuntimeException ex) {
                log.warn("market.fx.kafka.publish.failed key={}:{} reason={}",
                        p.baseCurrency(), p.quoteCurrency(), ex.getMessage(), ex);
            } catch (Exception ex) {
                log.warn("market.fx.kafka.publish.failed key={}:{} reason={}",
                        p.baseCurrency(), p.quoteCurrency(), ex.getMessage(), ex);
            }
        }
    }

    private String toJson(FxRateSnapshotPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unable to serialize FxRateSnapshotPayload", ex);
        }
    }
}
