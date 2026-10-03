package com.falconx.trading.consumer;

import com.falconx.infrastructure.kafka.KafkaEventHeaderConstants;
import com.falconx.trading.entity.TradingLiquidationLog;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.entity.TradingTradeType;
import com.falconx.trading.repository.TradingLiquidationLogRepository;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingTradeRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sprint 3 S6：消费 {@code falconx.trading.position.close.post-process} topic，按
 * {@code eventType} 分发写入 t_trade / t_liquidation_log / 级联撤 SL_TP 挂单。
 *
 * <p>自产自销：trading-core 自己 produce（通过 outbox dispatcher）+ 自己 consume。
 * 把 close 主事务的范围从 7 张表缩小到 5 张表，失败由 Kafka consumer retry 兜底。
 *
 * <p>每个 sub-handler 独立 {@code @Transactional}；handler 内抛异常后重抛由 Spring Kafka 重试。
 */
@Component
public class TradingPositionClosePostProcessConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradingPositionClosePostProcessConsumer.class);

    private final ObjectMapper objectMapper;
    private final TradingTradeRepository tradingTradeRepository;
    private final TradingLiquidationLogRepository tradingLiquidationLogRepository;
    private final TradingPendingOrderTriggerRepository pendingOrderRepository;

    public TradingPositionClosePostProcessConsumer(
            ObjectMapper objectMapper,
            TradingTradeRepository tradingTradeRepository,
            TradingLiquidationLogRepository tradingLiquidationLogRepository,
            @Autowired(required = false) TradingPendingOrderTriggerRepository pendingOrderRepository) {
        this.objectMapper = objectMapper;
        this.tradingTradeRepository = tradingTradeRepository;
        this.tradingLiquidationLogRepository = tradingLiquidationLogRepository;
        this.pendingOrderRepository = pendingOrderRepository;
    }

    @KafkaListener(
            topics = "${falconx.trading.position-close.post-process.topic}",
            groupId = "${falconx.trading.position-close.post-process.consumer-group-id}"
    )
    public void consume(String payloadJson,
                        @Header(KafkaEventHeaderConstants.EVENT_ID_HEADER) String eventId) {
        JsonNode payload = objectMapper.readTree(payloadJson);
        String eventType = payload.get("eventType").asString();
        try {
            switch (eventType) {
                case "TRADE_WRITE" -> handleTradeWrite(payload);
                case "LIQUIDATION_LOG_WRITE" -> handleLiquidationLogWrite(payload);
                case "PENDING_ORDER_CASCADE_CANCEL" -> handlePendingOrderCascadeCancel(payload);
                default -> log.warn("trading.position-close.post-process.unknown-event eventId={} eventType={}",
                        eventId, eventType);
            }
        } catch (RuntimeException ex) {
            log.error("trading.position-close.post-process.failed eventId={} eventType={} reason={}",
                    eventId, eventType, ex.toString(), ex);
            throw ex;
        }
    }

    @Transactional
    void handleTradeWrite(JsonNode payload) {
        Long tradeId = payload.get("tradeId").asLong();
        TradingTrade trade = new TradingTrade(
                tradeId,
                payload.get("orderId").asLong(),
                payload.get("positionId").asLong(),
                payload.get("userId").asLong(),
                payload.get("symbol").asString(),
                TradingOrderSide.valueOf(payload.get("side").asString()),
                TradingTradeType.valueOf(payload.get("tradeType").asString()),
                new BigDecimal(payload.get("quantity").asString()),
                new BigDecimal(payload.get("price").asString()),
                new BigDecimal(payload.get("fee").asString()),
                new BigDecimal(payload.get("realizedPnl").asString()),
                OffsetDateTime.parse(payload.get("tradedAt").asString())
        );
        tradingTradeRepository.saveWithExplicitId(trade);
        log.info("trading.position-close.post-process.trade-write.completed tradeId={} positionId={}",
                tradeId, trade.positionId());
    }

    @Transactional
    void handleLiquidationLogWrite(JsonNode payload) {
        TradingLiquidationLog liquidationLog = new TradingLiquidationLog(
                null,
                payload.get("userId").asLong(),
                payload.get("positionId").asLong(),
                payload.get("symbol").asString(),
                TradingOrderSide.valueOf(payload.get("side").asString()),
                TradingMarginMode.valueOf(payload.get("marginMode").asString()),
                new BigDecimal(payload.get("quantity").asString()),
                new BigDecimal(payload.get("entryPrice").asString()),
                // STAGE-14D2 Task 1：CROSS 强平仓 liquidationPrice=null（账户级触发），需容 null。
                readNullableBigDecimal(payload.get("liquidationPrice")),
                new BigDecimal(payload.get("closePrice").asString()),
                OffsetDateTime.parse(payload.get("quoteTs").asString()),
                payload.get("quoteSource").asString(),
                new BigDecimal(payload.get("netLossAfterMargin").asString()),
                new BigDecimal(payload.get("liquidationFee").asString()),
                new BigDecimal(payload.get("marginReleased").asString()),
                new BigDecimal(payload.get("platformCoveredLoss").asString()),
                OffsetDateTime.parse(payload.get("occurredAt").asString())
        );
        tradingLiquidationLogRepository.save(liquidationLog);
        log.info("trading.position-close.post-process.liquidation-log-write.completed positionId={}",
                liquidationLog.positionId());
    }

    /**
     * STAGE-14D2 Task 1：容 null 的 BigDecimal 读取——CROSS 强平仓 liquidationPrice=null
     * 经 outbox JSON 序列化后为 JSON null（NullNode），不能盲目 new BigDecimal(asString())。
     */
    private static BigDecimal readNullableBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new BigDecimal(node.asString());
    }

    @Transactional
    void handlePendingOrderCascadeCancel(JsonNode payload) {
        if (pendingOrderRepository == null) {
            log.info("trading.position-close.post-process.pending-order-cascade-cancel.skipped reason=repository-not-bound");
            return;
        }
        Long positionId = payload.get("positionId").asLong();
        String closeReason = payload.get("closeReason").asString();
        int cancelled = pendingOrderRepository.cancelAllSlTpByPositionId(
                positionId, "PARENT_POSITION_CLOSED_BY_" + closeReason);
        log.info("trading.position-close.post-process.pending-order-cascade-cancel.completed positionId={} cancelled={}",
                positionId, cancelled);
    }
}
