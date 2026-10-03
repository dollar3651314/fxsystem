package com.falconx.wallet.consumer;

import com.falconx.trading.contract.event.TradingWithdrawReviewedEventPayload;
import com.falconx.wallet.withdraw.WalletWithdrawBroadcastApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * STAGE-7-WITHDRAW Phase 3：消费 {@code trading.withdraw.reviewed} 事件，
 * APPROVED 触发 wallet 链上广播；REJECTED / APPROVED_DELAYED 跳过。
 *
 * <p>幂等：交由 {@link WalletWithdrawBroadcastApplicationService} 通过 t_withdraw_tx 唯一约束保证。
 *
 * <p>失败语义：广播异常已由 ApplicationService 内部转换为 {@code wallet.withdraw.failed} 事件 +
 * 数据库终态，本 consumer 不抛异常以避免重复入 DLQ。
 */
@Component
public class TradingWithdrawReviewedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradingWithdrawReviewedEventConsumer.class);

    private final WalletWithdrawBroadcastApplicationService broadcastService;
    private final ObjectMapper objectMapper;

    public TradingWithdrawReviewedEventConsumer(WalletWithdrawBroadcastApplicationService broadcastService,
                                                 ObjectMapper objectMapper) {
        this.broadcastService = broadcastService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${falconx.wallet.kafka.trading-withdraw-reviewed-topic}",
            groupId = "${falconx.wallet.kafka.trading-withdraw-reviewed-group}"
    )
    public void onReviewed(String payload,
                           @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        TradingWithdrawReviewedEventPayload parsed;
        try {
            parsed = objectMapper.readValue(payload, TradingWithdrawReviewedEventPayload.class);
        } catch (RuntimeException ex) {
            log.error("wallet.consumer.trading.withdraw.reviewed.parse-failed key={} reason={} payload={}",
                    key, ex.toString(), payload, ex);
            return; // 坏消息不阻塞 partition
        }
        try {
            consume(key == null ? "no-key" : key, parsed);
        } catch (RuntimeException ex) {
            log.error("wallet.consumer.trading.withdraw.reviewed.dispatch-failed key={} withdrawId={} reason={}",
                    key, parsed.withdrawId(), ex.toString(), ex);
            // ApplicationService 已转换大部分异常；此处兜底防 DLQ 风暴
        }
    }

    public void consume(String eventId, TradingWithdrawReviewedEventPayload payload) {
        if (payload == null || payload.withdrawId() == null || payload.result() == null) {
            throw new IllegalArgumentException("trading.withdraw.reviewed payload missing required fields: eventId=" + eventId);
        }
        log.info("wallet.consumer.trading.withdraw.reviewed.received eventId={} withdrawId={} result={}",
                eventId, payload.withdrawId(), payload.result());
        if (!"APPROVED".equals(payload.result())) {
            return;
        }
        try {
            broadcastService.broadcast(payload);
        } catch (RuntimeException ex) {
            // ApplicationService 已经转换大部分异常；此处兜底防 DLQ 风暴
            log.error("wallet.consumer.trading.withdraw.reviewed.unhandled withdrawId={} eventId={}",
                    payload.withdrawId(), eventId, ex);
        }
    }
}
