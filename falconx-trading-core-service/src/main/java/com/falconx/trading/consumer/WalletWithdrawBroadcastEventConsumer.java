package com.falconx.trading.consumer;

import com.falconx.trading.entity.TradingWithdrawOrder;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.repository.TradingWithdrawOrderRepository;
import com.falconx.wallet.contract.event.WalletWithdrawBroadcastedEventPayload;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * STAGE-7-WITHDRAW Phase 3：消费 {@code falconx.wallet.withdraw.broadcast} 事件 →
 * {@code t_withdraw_order.status} APPROVED(2) → PROCESSING(4) + 写 tx_hash + processing_started_at。
 *
 * <p>幂等：CAS {@code WHERE status=2}；重复事件返回 0 行直接跳过。状态已是
 * {@code PROCESSING / COMPLETED / FAILED} 等终态时同样安全跳过。
 */
@Component
public class WalletWithdrawBroadcastEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(WalletWithdrawBroadcastEventConsumer.class);

    private final TradingWithdrawOrderRepository withdrawOrderRepository;
    private final com.falconx.trading.websocket.TradingAdminRealtimePushService adminRealtimePushService;

    public WalletWithdrawBroadcastEventConsumer(TradingWithdrawOrderRepository withdrawOrderRepository,
                                                 com.falconx.trading.websocket.TradingAdminRealtimePushService adminRealtimePushService) {
        this.withdrawOrderRepository = withdrawOrderRepository;
        this.adminRealtimePushService = adminRealtimePushService;
    }

    @Transactional
    public void consume(String eventId, WalletWithdrawBroadcastedEventPayload payload) {
        if (payload == null || payload.withdrawId() == null) {
            throw new IllegalArgumentException("wallet.withdraw.broadcast payload missing withdrawId: eventId=" + eventId);
        }
        TradingWithdrawOrder order = withdrawOrderRepository.findById(payload.withdrawId()).orElse(null);
        if (order == null) {
            log.warn("trading.consumer.wallet.withdraw.broadcast.order-not-found eventId={} withdrawId={}",
                    eventId, payload.withdrawId());
            return;
        }
        if (order.status() != TradingWithdrawOrderStatus.APPROVED) {
            log.info("trading.consumer.wallet.withdraw.broadcast.skipped-status eventId={} withdrawId={} status={}",
                    eventId, payload.withdrawId(), order.status());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int updated = withdrawOrderRepository.markProcessingFromApprovedAtomic(
                payload.withdrawId(), payload.txHash(), now);
        if (updated == 0) {
            log.info("trading.consumer.wallet.withdraw.broadcast.cas-mismatch eventId={} withdrawId={}",
                    eventId, payload.withdrawId());
            return;
        }
        log.info("trading.consumer.wallet.withdraw.broadcast.completed eventId={} withdrawId={} txHash={}",
                eventId, payload.withdrawId(), payload.txHash());
        // Phase 4 §4 commit C: best-effort 实时推送给管理后台
        // 2026-05-26 性能加固（Sprint 2 S1 / 性能分析报告 §2 P0）：
        // 原实现把 WebSocket 推送放在 @Transactional 内，WebSocket 断/慢会拖事务持锁，
        // 阻塞其他消费者。改用 afterCommit 回调：事务提交后再 best-effort 推送。
        // 推送失败仍仅 warn，不回滚已落库的状态机推进。
        final Long withdrawId = payload.withdrawId();
        final Long userId = order.userId();
        final String txHash = payload.txHash();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        adminRealtimePushService.publishWithdrawStatusChanged(
                                withdrawId, userId, "PROCESSING", txHash, 0, null);
                    } catch (Exception ex) {
                        log.warn("trading.consumer.wallet.withdraw.broadcast.ws-push-failed eventId={} withdrawId={} reason={}",
                                eventId, withdrawId, ex.toString());
                    }
                }
            });
        } else {
            // 非事务上下文（理论不应发生，但守一手）：同步推送
            try {
                adminRealtimePushService.publishWithdrawStatusChanged(
                        withdrawId, userId, "PROCESSING", txHash, 0, null);
            } catch (Exception ex) {
                log.warn("trading.consumer.wallet.withdraw.broadcast.ws-push-failed eventId={} withdrawId={} reason={}",
                        eventId, withdrawId, ex.toString());
            }
        }
    }
}
