package com.falconx.trading.consumer;

import com.falconx.trading.application.TradingNotificationApplicationService;
import com.falconx.trading.entity.TradingWithdrawOrder;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.repository.TradingNotificationRepository;
import com.falconx.trading.repository.TradingWithdrawOrderRepository;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.wallet.contract.event.WalletWithdrawFailedEventPayload;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-7-WITHDRAW Phase 3：消费 {@code falconx.wallet.withdraw.failed} 事件 →
 * {@code t_withdraw_order.status} 任意非终态 → FAILED(6)
 * + {@code t_account.frozen -= amount}（balance 不变）
 * + {@code t_ledger biz_type=WITHDRAW_REFUND_CHAIN_FAILED} + 站内信。
 *
 * <p>幂等：{@code relatedKey="withdraw.failed" + relatedId=withdrawId} 查重；重复事件跳过。
 */
@Component
public class WalletWithdrawFailedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(WalletWithdrawFailedEventConsumer.class);
    public static final String RELATED_KEY = "withdraw.failed";

    private final TradingWithdrawOrderRepository withdrawOrderRepository;
    private final TradingAccountService accountService;
    private final TradingNotificationApplicationService notificationService;
    private final TradingNotificationRepository notificationRepository;
    private final com.falconx.trading.websocket.TradingAdminRealtimePushService adminRealtimePushService;

    public WalletWithdrawFailedEventConsumer(TradingWithdrawOrderRepository withdrawOrderRepository,
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
    public void consume(String eventId, WalletWithdrawFailedEventPayload payload) {
        if (payload == null || payload.withdrawId() == null) {
            throw new IllegalArgumentException("wallet.withdraw.failed payload missing withdrawId: eventId=" + eventId);
        }
        Long withdrawId = payload.withdrawId();
        if (notificationRepository.existsByRelated(RELATED_KEY, withdrawId)) {
            log.info("trading.consumer.wallet.withdraw.failed.skipped-duplicate eventId={} withdrawId={}",
                    eventId, withdrawId);
            return;
        }
        TradingWithdrawOrder order = withdrawOrderRepository.findById(withdrawId).orElse(null);
        if (order == null) {
            log.warn("trading.consumer.wallet.withdraw.failed.order-not-found eventId={} withdrawId={}",
                    eventId, withdrawId);
            return;
        }
        TradingWithdrawOrderStatus status = order.status();
        if (status == TradingWithdrawOrderStatus.FAILED
                || status == TradingWithdrawOrderStatus.COMPLETED
                || status == TradingWithdrawOrderStatus.CANCELED
                || status == TradingWithdrawOrderStatus.REJECTED) {
            log.info("trading.consumer.wallet.withdraw.failed.terminal-skip eventId={} withdrawId={} status={}",
                    eventId, withdrawId, status);
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String failureCode = payload.failureCode() == null ? "20010" : payload.failureCode();
        String failureReason = payload.failureReason() == null ? "wallet chain failure" : payload.failureReason();
        int updated = withdrawOrderRepository.markFailedAtomic(withdrawId, failureCode, failureReason, now);
        if (updated == 0) {
            log.info("trading.consumer.wallet.withdraw.failed.cas-mismatch eventId={} withdrawId={}",
                    eventId, withdrawId);
            return;
        }
        accountService.refundFailedWithdraw(
                order.userId(), order.currency(), order.amount(),
                "withdraw-refund-chain-failed:" + order.idempotencyKey(),
                "withdraw:" + withdrawId,
                now
        );
        notificationService.send(
                "WITHDRAW_FAILED",
                order.userId(),
                "WITHDRAW_FAILED",
                java.util.Map.of(
                        "amount", order.amount().toPlainString(),
                        "currency", order.currency(),
                        "failureReason", failureReason
                ),
                RELATED_KEY,
                withdrawId,
                null
        );
        log.info("trading.consumer.wallet.withdraw.failed.completed eventId={} withdrawId={} failureCode={} userId={}",
                eventId, withdrawId, failureCode, order.userId());
        // Phase 4 §4 commit C: best-effort 实时推送给管理后台
        try {
            adminRealtimePushService.publishWithdrawStatusChanged(
                    withdrawId, order.userId(), "FAILED",
                    payload.txHash(), null, failureReason);
        } catch (Exception ex) {
            log.warn("trading.consumer.wallet.withdraw.failed.ws-push-failed eventId={} withdrawId={} reason={}",
                    eventId, withdrawId, ex.toString());
        }
    }
}
