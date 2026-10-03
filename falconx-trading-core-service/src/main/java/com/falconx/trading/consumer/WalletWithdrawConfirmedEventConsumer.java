package com.falconx.trading.consumer;

import com.falconx.trading.application.TradingNotificationApplicationService;
import com.falconx.trading.entity.TradingWithdrawOrder;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.repository.TradingNotificationRepository;
import com.falconx.trading.repository.TradingWithdrawOrderRepository;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.wallet.contract.event.WalletWithdrawConfirmedEventPayload;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-7-WITHDRAW Phase 3：消费 {@code falconx.wallet.withdraw.confirmed} 事件 →
 * {@code t_withdraw_order.status} PROCESSING(4) → COMPLETED(5)
 * + {@code t_account.balance -= amount} + {@code t_account.frozen -= amount}
 * + {@code t_ledger biz_type=WITHDRAW_SETTLE} + 站内信。
 *
 * <p>幂等：{@code relatedKey="withdraw.confirmed" + relatedId=withdrawId} 通过
 * {@link TradingNotificationRepository#existsByRelated} 查重；重复事件直接跳过。
 */
@Component
public class WalletWithdrawConfirmedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(WalletWithdrawConfirmedEventConsumer.class);
    public static final String RELATED_KEY = "withdraw.confirmed";

    private final TradingWithdrawOrderRepository withdrawOrderRepository;
    private final TradingAccountService accountService;
    private final TradingNotificationApplicationService notificationService;
    private final TradingNotificationRepository notificationRepository;
    private final com.falconx.trading.websocket.TradingAdminRealtimePushService adminRealtimePushService;

    public WalletWithdrawConfirmedEventConsumer(TradingWithdrawOrderRepository withdrawOrderRepository,
                                                  TradingAccountService accountService,
                                                  TradingNotificationApplicationService notificationService,
                                                  TradingNotificationRepository notificationRepository,
                                                  com.falconx.trading.websocket.TradingAdminRealtimePushService adminRealtimePushService) {
        this.withdrawOrderRepository = withdrawOrderRepository;
        this.accountService = accountService;
        this.notificationService = notificationService;
        this.notificationRepository = notificationRepository;
        this.adminRealtimePushService = adminRealtimePushService;
    }

    @Transactional
    public void consume(String eventId, WalletWithdrawConfirmedEventPayload payload) {
        if (payload == null || payload.withdrawId() == null) {
            throw new IllegalArgumentException("wallet.withdraw.confirmed payload missing withdrawId: eventId=" + eventId);
        }
        Long withdrawId = payload.withdrawId();
        if (notificationRepository.existsByRelated(RELATED_KEY, withdrawId)) {
            log.info("trading.consumer.wallet.withdraw.confirmed.skipped-duplicate eventId={} withdrawId={}",
                    eventId, withdrawId);
            return;
        }
        TradingWithdrawOrder order = withdrawOrderRepository.findById(withdrawId).orElse(null);
        if (order == null) {
            log.warn("trading.consumer.wallet.withdraw.confirmed.order-not-found eventId={} withdrawId={}",
                    eventId, withdrawId);
            return;
        }
        if (order.status() == TradingWithdrawOrderStatus.COMPLETED) {
            log.info("trading.consumer.wallet.withdraw.confirmed.already-completed eventId={} withdrawId={}",
                    eventId, withdrawId);
            return;
        }
        if (order.status() != TradingWithdrawOrderStatus.PROCESSING) {
            log.warn("trading.consumer.wallet.withdraw.confirmed.unexpected-status eventId={} withdrawId={} status={}",
                    eventId, withdrawId, order.status());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int updated = withdrawOrderRepository.markCompletedFromProcessingAtomic(
                withdrawId, payload.confirmations(), now);
        if (updated == 0) {
            log.info("trading.consumer.wallet.withdraw.confirmed.cas-mismatch eventId={} withdrawId={}",
                    eventId, withdrawId);
            return;
        }
        accountService.settleConfirmedWithdraw(
                order.userId(), order.currency(), order.amount(),
                "withdraw-settle:" + order.idempotencyKey(),
                "withdraw:" + withdrawId,
                now
        );
        notificationService.send(
                "WITHDRAW_COMPLETED",
                order.userId(),
                "WITHDRAW_COMPLETED",
                java.util.Map.of(
                        "amount", order.amount().toPlainString(),
                        "currency", order.currency()
                ),
                RELATED_KEY,
                withdrawId,
                null
        );
        log.info("trading.consumer.wallet.withdraw.confirmed.completed eventId={} withdrawId={} amount={} userId={}",
                eventId, withdrawId, order.amount(), order.userId());
        // Phase 4 §4 commit C: best-effort 实时推送给管理后台
        try {
            adminRealtimePushService.publishWithdrawStatusChanged(
                    withdrawId, order.userId(), "COMPLETED",
                    payload.txHash(), payload.confirmations(), null);
        } catch (Exception ex) {
            log.warn("trading.consumer.wallet.withdraw.confirmed.ws-push-failed eventId={} withdrawId={} reason={}",
                    eventId, withdrawId, ex.toString());
        }
    }
}
