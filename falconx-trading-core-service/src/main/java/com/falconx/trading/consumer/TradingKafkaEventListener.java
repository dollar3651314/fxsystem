package com.falconx.trading.consumer;

import tools.jackson.databind.ObjectMapper;
import com.falconx.identity.contract.event.KycReviewedEventPayload;
import com.falconx.infrastructure.kafka.KafkaEventHeaderConstants;
import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.contract.event.MarketKlineUpdateEventPayload;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.wallet.contract.event.WalletDepositConfirmedEventPayload;
import com.falconx.wallet.contract.event.WalletDepositReversedEventPayload;
import com.falconx.wallet.contract.event.WalletWithdrawBroadcastedEventPayload;
import com.falconx.wallet.contract.event.WalletWithdrawConfirmedEventPayload;
import com.falconx.wallet.contract.event.WalletWithdrawFailedEventPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * trading-core Kafka 监听适配器。
 *
 * <p>该组件承担“Kafka 框架入口 -> 领域消费者”的适配职责：
 *
 * <ul>
 *   <li>从消息头提取 `eventId` 与 `traceId`</li>
 *   <li>把 JSON 字符串反序列化为契约 payload</li>
 *   <li>调用既有的业务消费者完成领域处理</li>
 * </ul>
 *
 * <p>这样可以保持原有 `*EventConsumer` 类只关注业务语义，不直接依赖 Kafka 注解和字符串反序列化。
 */
@Component
public class TradingKafkaEventListener {

    private static final Logger log = LoggerFactory.getLogger(TradingKafkaEventListener.class);

    private final ObjectMapper objectMapper;
    private final TradingCoreServiceProperties properties;
    private final MarketKlineUpdateEventConsumer marketKlineUpdateEventConsumer;
    private final MarketPriceTickEventConsumer marketPriceTickEventConsumer;
    private final TradingManagedKafkaExecutionSupport tradingManagedKafkaExecutionSupport;
    private final WalletDepositConfirmedEventConsumer walletDepositConfirmedEventConsumer;
    private final WalletDepositReversedEventConsumer walletDepositReversedEventConsumer;
    private final KycReviewedEventConsumer kycReviewedEventConsumer;
    private final WalletWithdrawBroadcastEventConsumer walletWithdrawBroadcastEventConsumer;
    private final WalletWithdrawConfirmedEventConsumer walletWithdrawConfirmedEventConsumer;
    private final WalletWithdrawFailedEventConsumer walletWithdrawFailedEventConsumer;
    private final FxRateUpdateEventConsumer fxRateUpdateEventConsumer;

    public TradingKafkaEventListener(ObjectMapper objectMapper,
                                     TradingCoreServiceProperties properties,
                                     MarketKlineUpdateEventConsumer marketKlineUpdateEventConsumer,
                                     MarketPriceTickEventConsumer marketPriceTickEventConsumer,
                                     TradingManagedKafkaExecutionSupport tradingManagedKafkaExecutionSupport,
                                     WalletDepositConfirmedEventConsumer walletDepositConfirmedEventConsumer,
                                     WalletDepositReversedEventConsumer walletDepositReversedEventConsumer,
                                     KycReviewedEventConsumer kycReviewedEventConsumer,
                                     WalletWithdrawBroadcastEventConsumer walletWithdrawBroadcastEventConsumer,
                                     WalletWithdrawConfirmedEventConsumer walletWithdrawConfirmedEventConsumer,
                                     WalletWithdrawFailedEventConsumer walletWithdrawFailedEventConsumer,
                                     FxRateUpdateEventConsumer fxRateUpdateEventConsumer) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.marketKlineUpdateEventConsumer = marketKlineUpdateEventConsumer;
        this.marketPriceTickEventConsumer = marketPriceTickEventConsumer;
        this.tradingManagedKafkaExecutionSupport = tradingManagedKafkaExecutionSupport;
        this.walletDepositConfirmedEventConsumer = walletDepositConfirmedEventConsumer;
        this.walletDepositReversedEventConsumer = walletDepositReversedEventConsumer;
        this.kycReviewedEventConsumer = kycReviewedEventConsumer;
        this.walletWithdrawBroadcastEventConsumer = walletWithdrawBroadcastEventConsumer;
        this.walletWithdrawConfirmedEventConsumer = walletWithdrawConfirmedEventConsumer;
        this.walletWithdrawFailedEventConsumer = walletWithdrawFailedEventConsumer;
        this.fxRateUpdateEventConsumer = fxRateUpdateEventConsumer;
    }

    /**
     * 消费市场高频价格事件。
     *
     * @param payloadJson Kafka 里的 JSON payload
     * @param eventId 事件 ID
     * @param traceId 链路 traceId
     */
    @KafkaListener(
            topics = "${falconx.trading.kafka.market-price-tick-topic}",
            groupId = "${falconx.trading.kafka.consumer-group-id}",
            containerFactory = "marketPriceTickKafkaListenerContainerFactory"
    )
    public void onMarketPriceTick(String payloadJson,
                                  @Header(KafkaEventHeaderConstants.EVENT_ID_HEADER) String eventId,
                                  @Header(value = KafkaEventHeaderConstants.TRACE_ID_HEADER, required = false)
                                  String traceId) {
        withTraceId(traceId, () -> {
            log.debug("trading.kafka.consume.received topic={} eventId={}",
                    properties.getKafka().getMarketPriceTickTopic(),
                    eventId);
            marketPriceTickEventConsumer.consume(
                    eventId,
                    objectMapper.readValue(payloadJson, MarketPriceTickEventPayload.class)
            );
        });
    }

    /**
     * 消费市场收盘 K 线事件。
     *
     * @param payloadJson Kafka 里的 JSON payload
     * @param eventId 事件 ID
     * @param traceId 链路 traceId
     */
    @KafkaListener(
            topics = "${falconx.trading.kafka.market-kline-update-topic}",
            groupId = "${falconx.trading.kafka.consumer-group-id}"
    )
    public void onMarketKlineUpdate(String payloadJson,
                                    @Header(KafkaEventHeaderConstants.EVENT_ID_HEADER) String eventId,
                                    @Header(value = KafkaEventHeaderConstants.TRACE_ID_HEADER, required = false)
                                    String traceId) {
        tradingManagedKafkaExecutionSupport.execute(traceId, () -> {
            log.info("trading.kafka.consume.received topic={} eventId={}",
                    properties.getKafka().getMarketKlineUpdateTopic(),
                    eventId);
            MarketKlineUpdateEventPayload payload =
                    objectMapper.readValue(payloadJson, MarketKlineUpdateEventPayload.class);
            marketKlineUpdateEventConsumer.consume(eventId, payload, payloadJson);
        });
    }

    /**
     * 消费钱包确认入金事件。
     *
     * @param payloadJson Kafka 里的 JSON payload
     * @param eventId 事件 ID
     * @param traceId 链路 traceId
     */
    @KafkaListener(
            topics = "${falconx.trading.kafka.wallet-deposit-confirmed-topic}",
            groupId = "${falconx.trading.kafka.consumer-group-id}"
    )
    public void onWalletDepositConfirmed(String payloadJson,
                                         @Header(KafkaEventHeaderConstants.EVENT_ID_HEADER) String eventId,
                                         @Header(value = KafkaEventHeaderConstants.TRACE_ID_HEADER, required = false)
                                         String traceId) {
        tradingManagedKafkaExecutionSupport.execute(traceId, () -> {
            log.info("trading.kafka.consume.received topic={} eventId={}",
                    properties.getKafka().getWalletDepositConfirmedTopic(),
                    eventId);
            walletDepositConfirmedEventConsumer.consume(
                    eventId,
                    objectMapper.readValue(payloadJson, WalletDepositConfirmedEventPayload.class)
            );
        });
    }

    /**
     * 消费钱包回滚事件。
     *
     * @param payloadJson Kafka 里的 JSON payload
     * @param eventId 事件 ID
     * @param traceId 链路 traceId
     */
    @KafkaListener(
            topics = "${falconx.trading.kafka.wallet-deposit-reversed-topic}",
            groupId = "${falconx.trading.kafka.consumer-group-id}"
    )
    public void onWalletDepositReversed(String payloadJson,
                                        @Header(KafkaEventHeaderConstants.EVENT_ID_HEADER) String eventId,
                                        @Header(value = KafkaEventHeaderConstants.TRACE_ID_HEADER, required = false)
                                        String traceId) {
        tradingManagedKafkaExecutionSupport.execute(traceId, () -> {
            log.info("trading.kafka.consume.received topic={} eventId={}",
                    properties.getKafka().getWalletDepositReversedTopic(),
                    eventId);
            walletDepositReversedEventConsumer.consume(
                    eventId,
                    objectMapper.readValue(payloadJson, WalletDepositReversedEventPayload.class)
            );
        });
    }

    /**
     * STAGE-6-KYC Phase 4：消费 identity KYC 审核完成事件 → 站内信。
     *
     * @param payloadJson Kafka 里的 JSON payload
     * @param eventId 事件 ID
     * @param traceId 链路 traceId
     */
    @KafkaListener(
            topics = "${falconx.trading.kafka.kyc-reviewed-topic}",
            groupId = "${falconx.trading.kafka.kyc-reviewed-consumer-group-id}"
    )
    public void onKycReviewed(String payloadJson,
                              @Header(KafkaEventHeaderConstants.EVENT_ID_HEADER) String eventId,
                              @Header(value = KafkaEventHeaderConstants.TRACE_ID_HEADER, required = false)
                              String traceId) {
        tradingManagedKafkaExecutionSupport.execute(traceId, () -> {
            log.info("trading.kafka.consume.received topic={} eventId={}",
                    properties.getKafka().getKycReviewedTopic(),
                    eventId);
            kycReviewedEventConsumer.consume(
                    eventId,
                    objectMapper.readValue(payloadJson, KycReviewedEventPayload.class)
            );
        });
    }

    /**
     * STAGE-7-WITHDRAW Phase 3：消费 wallet 链上广播完成事件 → t_withdraw_order PROCESSING。
     */
    @KafkaListener(
            topics = "${falconx.trading.kafka.wallet-withdraw-broadcast-topic}",
            groupId = "${falconx.trading.kafka.wallet-withdraw-broadcast-consumer-group-id}"
    )
    public void onWalletWithdrawBroadcast(String payloadJson,
                                           @Header(KafkaEventHeaderConstants.EVENT_ID_HEADER) String eventId,
                                           @Header(value = KafkaEventHeaderConstants.TRACE_ID_HEADER, required = false)
                                           String traceId) {
        tradingManagedKafkaExecutionSupport.execute(traceId, () -> {
            log.info("trading.kafka.consume.received topic={} eventId={}",
                    properties.getKafka().getWalletWithdrawBroadcastTopic(), eventId);
            walletWithdrawBroadcastEventConsumer.consume(eventId,
                    objectMapper.readValue(payloadJson, WalletWithdrawBroadcastedEventPayload.class));
        });
    }

    /**
     * STAGE-7-WITHDRAW Phase 3：消费 wallet 链上确认事件 → t_withdraw_order COMPLETED + 站内信。
     */
    @KafkaListener(
            topics = "${falconx.trading.kafka.wallet-withdraw-confirmed-topic}",
            groupId = "${falconx.trading.kafka.wallet-withdraw-confirmed-consumer-group-id}"
    )
    public void onWalletWithdrawConfirmed(String payloadJson,
                                           @Header(KafkaEventHeaderConstants.EVENT_ID_HEADER) String eventId,
                                           @Header(value = KafkaEventHeaderConstants.TRACE_ID_HEADER, required = false)
                                           String traceId) {
        tradingManagedKafkaExecutionSupport.execute(traceId, () -> {
            log.info("trading.kafka.consume.received topic={} eventId={}",
                    properties.getKafka().getWalletWithdrawConfirmedTopic(), eventId);
            walletWithdrawConfirmedEventConsumer.consume(eventId,
                    objectMapper.readValue(payloadJson, WalletWithdrawConfirmedEventPayload.class));
        });
    }

    /**
     * STAGE-7-WITHDRAW Phase 3：消费 wallet 链上失败事件 → t_withdraw_order FAILED + frozen 释放 + 站内信。
     */
    @KafkaListener(
            topics = "${falconx.trading.kafka.wallet-withdraw-failed-topic}",
            groupId = "${falconx.trading.kafka.wallet-withdraw-failed-consumer-group-id}"
    )
    public void onWalletWithdrawFailed(String payloadJson,
                                        @Header(KafkaEventHeaderConstants.EVENT_ID_HEADER) String eventId,
                                        @Header(value = KafkaEventHeaderConstants.TRACE_ID_HEADER, required = false)
                                        String traceId) {
        tradingManagedKafkaExecutionSupport.execute(traceId, () -> {
            log.info("trading.kafka.consume.received topic={} eventId={}",
                    properties.getKafka().getWalletWithdrawFailedTopic(), eventId);
            walletWithdrawFailedEventConsumer.consume(eventId,
                    objectMapper.readValue(payloadJson, WalletWithdrawFailedEventPayload.class));
        });
    }

    /**
     * STAGE-14B Task 3：消费 market FX 汇率更新事件。
     *
     * <p>{@code acceptUpdate} 内部仅 ConcurrentHashMap.put（O(1) 非阻塞），
     * 直接在 Kafka 回调线程执行，无需切换自管线程。
     *
     * @param payloadJson Kafka 里的 JSON payload
     * @param eventId 事件 ID
     * @param traceId 链路 traceId
     */
    @KafkaListener(
            topics = "${falconx.trading.fx.fx-rate-update-topic}",
            groupId = "${falconx.trading.fx.consumer-group-id}"
    )
    public void onMarketFxRateUpdate(String payloadJson,
                                     @Header(KafkaEventHeaderConstants.EVENT_ID_HEADER) String eventId,
                                     @Header(value = KafkaEventHeaderConstants.TRACE_ID_HEADER, required = false)
                                     String traceId) {
        withTraceId(traceId, () -> {
            log.debug("trading.kafka.consume.received topic={} eventId={}",
                    properties.getFx().getFxRateUpdateTopic(), eventId);
            fxRateUpdateEventConsumer.consume(
                    eventId,
                    objectMapper.readValue(payloadJson, FxRateSnapshotPayload.class)
            );
        });
    }

    /**
     * 统一处理 Kafka 消费线程的 traceId 注入与异常包装。
     *
     * @param traceIdHeader Kafka 头中的 traceId
     * @param action 具体消费动作
     */
    private void withTraceId(String traceIdHeader, ThrowingRunnable action) {
        KafkaEventMessageSupport.bindTraceId(traceIdHeader);
        try {
            action.run();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to process Kafka event in trading-core-service", exception);
        } finally {
            KafkaEventMessageSupport.clearTraceId();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
