package com.falconx.trading.application;

import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.contract.event.TradingWithdrawReviewedEventPayload;
import com.falconx.trading.entity.TradingOutboxMessage;
import com.falconx.trading.entity.TradingOutboxStatus;
import com.falconx.trading.entity.TradingWithdrawOrder;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingOutboxRepository;
import com.falconx.trading.repository.TradingWithdrawOrderRepository;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.websocket.TradingAdminRealtimePushService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-7-WITHDRAW Phase 2：admin 审核 / 紧急取消应用服务。
 *
 * <p>负责 admin 端三类状态迁移：
 * <ul>
 *   <li>{@code PENDING → APPROVED} 或 {@code APPROVED_DELAYED}（按金额 ≥ $3K 决定）</li>
 *   <li>{@code PENDING → REJECTED} + 退冻 ({@code biz_type=15})</li>
 *   <li>{@code APPROVED_DELAYED → CANCELED} + 退冻 ({@code biz_type=16})</li>
 * </ul>
 *
 * <p>所有状态迁移通过 CAS 串行化。审核完成后通过 Outbox 发布
 * {@code trading.withdraw.reviewed}（payload result = APPROVED / APPROVED_DELAYED / REJECTED）。
 * 紧急取消不发布 reviewed 事件（其状态机意义为撤回审核结论，不应再向 wallet 触发链上动作）。
 */
@Service
public class WithdrawAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawAdminApplicationService.class);

    private final TradingWithdrawOrderRepository withdrawOrderRepository;
    private final TradingAccountService accountService;
    private final TradingOutboxRepository outboxRepository;
    private final TradingCoreServiceProperties properties;
    private final TradingAdminRealtimePushService adminRealtimePushService;

    public WithdrawAdminApplicationService(TradingWithdrawOrderRepository withdrawOrderRepository,
                                            TradingAccountService accountService,
                                            TradingOutboxRepository outboxRepository,
                                            TradingCoreServiceProperties properties,
                                            TradingAdminRealtimePushService adminRealtimePushService) {
        this.withdrawOrderRepository = withdrawOrderRepository;
        this.accountService = accountService;
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.adminRealtimePushService = adminRealtimePushService;
    }

    @Transactional(readOnly = true)
    public List<TradingWithdrawOrder> list(String statusName, Long userId, String network,
                                             BigDecimal minAmount, int page, int pageSize) {
        Integer statusCode = parseStatusCode(statusName);
        int offset = (page - 1) * pageSize;
        return withdrawOrderRepository.findAdminPaginated(statusCode, userId, network, minAmount, offset, pageSize);
    }

    @Transactional(readOnly = true)
    public long count(String statusName, Long userId, String network, BigDecimal minAmount) {
        Integer statusCode = parseStatusCode(statusName);
        return withdrawOrderRepository.countAdminFiltered(statusCode, userId, network, minAmount);
    }

    @Transactional(readOnly = true)
    public TradingWithdrawOrder detail(long withdrawId) {
        return withdrawOrderRepository.findById(withdrawId)
                .orElseThrow(() -> new TradingBusinessException(TradingErrorCode.WITHDRAW_NOT_FOUND,
                        Map.of("withdrawId", withdrawId)));
    }

    /**
     * admin 审核通过。amount &lt; $3K → APPROVED；≥ $3K → APPROVED_DELAYED（delayed_until = 审核时间 + 6h）。
     */
    @Transactional
    public TradingWithdrawOrder approve(long withdrawId, long adminUserId, String reviewNote) {
        TradingWithdrawOrder order = loadOrderRequirePending(withdrawId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        boolean isLargeAmount = order.amount().compareTo(properties.getWithdraw().getDelayedAmountThresholdUsd()) >= 0;
        TradingWithdrawOrderStatus nextStatus = isLargeAmount
                ? TradingWithdrawOrderStatus.APPROVED_DELAYED
                : TradingWithdrawOrderStatus.APPROVED;
        OffsetDateTime delayedUntil = isLargeAmount ? now.plus(properties.getWithdraw().getDelayedDuration()) : null;

        int updated = withdrawOrderRepository.markReviewedFromPendingAtomic(
                withdrawId, nextStatus.code(), adminUserId, now, reviewNote, delayedUntil);
        if (updated == 0) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_NOT_PENDING,
                    Map.of("reason", "cas-conflict"));
        }

        publishReviewedEvent(order, nextStatus.name(), adminUserId, now, null, delayedUntil);
        adminRealtimePushService.publishWithdrawStatusChanged(
                withdrawId, order.userId(), nextStatus.name(), null, null, null);
        log.info("trading.withdraw.admin.approve.completed withdrawId={} adminUserId={} nextStatus={} delayedUntil={}",
                withdrawId, adminUserId, nextStatus, delayedUntil);
        return rebuild(order, nextStatus, adminUserId, now, reviewNote, null, delayedUntil);
    }

    /**
     * admin 拒绝出金，PENDING → REJECTED + 退冻（biz_type=15）。
     */
    @Transactional
    public TradingWithdrawOrder reject(long withdrawId, long adminUserId, String rejectReason) {
        if (rejectReason == null || rejectReason.isBlank()) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_NOT_PENDING,
                    Map.of("reason", "reject-reason-required"));
        }
        TradingWithdrawOrder order = loadOrderRequirePending(withdrawId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        int updated = withdrawOrderRepository.markRejectedFromPendingAtomic(
                withdrawId, adminUserId, now, rejectReason);
        if (updated == 0) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_NOT_PENDING,
                    Map.of("reason", "cas-conflict"));
        }

        accountService.refundRejectedWithdraw(
                order.userId(), order.currency(), order.amount(),
                "withdraw-refund-reject:" + order.idempotencyKey(),
                "withdraw:" + withdrawId,
                now
        );

        publishReviewedEvent(order, "REJECTED", adminUserId, now, rejectReason, null);
        adminRealtimePushService.publishWithdrawStatusChanged(
                withdrawId, order.userId(), "REJECTED", null, null, rejectReason);
        log.info("trading.withdraw.admin.reject.completed withdrawId={} adminUserId={} amount={}",
                withdrawId, adminUserId, order.amount());
        return rebuild(order, TradingWithdrawOrderStatus.REJECTED, adminUserId, now, null, rejectReason, null);
    }

    /**
     * admin 紧急取消，APPROVED_DELAYED → CANCELED + 退冻（biz_type=16）。
     */
    @Transactional
    public TradingWithdrawOrder emergencyCancel(long withdrawId, long adminUserId, String reason) {
        TradingWithdrawOrder order = withdrawOrderRepository.findById(withdrawId)
                .orElseThrow(() -> new TradingBusinessException(TradingErrorCode.WITHDRAW_NOT_FOUND,
                        Map.of("withdrawId", withdrawId)));
        if (order.status() != TradingWithdrawOrderStatus.APPROVED_DELAYED) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_EMERGENCY_CANCEL_NOT_ALLOWED,
                    Map.of("status", order.status().name()));
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        int updated = withdrawOrderRepository.markCanceledByAdminAtomic(
                withdrawId, adminUserId, now, reason);
        if (updated == 0) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_EMERGENCY_CANCEL_NOT_ALLOWED,
                    Map.of("reason", "cas-conflict"));
        }

        accountService.refundEmergencyCanceledWithdraw(
                order.userId(), order.currency(), order.amount(),
                "withdraw-refund-emergency:" + order.idempotencyKey(),
                "withdraw:" + withdrawId,
                now
        );

        adminRealtimePushService.publishWithdrawStatusChanged(
                withdrawId, order.userId(), "CANCELED", null, null, reason);
        log.info("trading.withdraw.admin.emergency-cancel.completed withdrawId={} adminUserId={} amount={}",
                withdrawId, adminUserId, order.amount());
        return rebuild(order, TradingWithdrawOrderStatus.CANCELED, adminUserId, now, null, reason, null);
    }

    private TradingWithdrawOrder loadOrderRequirePending(long withdrawId) {
        TradingWithdrawOrder order = withdrawOrderRepository.findById(withdrawId)
                .orElseThrow(() -> new TradingBusinessException(TradingErrorCode.WITHDRAW_NOT_FOUND,
                        Map.of("withdrawId", withdrawId)));
        if (order.status() != TradingWithdrawOrderStatus.PENDING) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_NOT_PENDING,
                    Map.of("status", order.status().name()));
        }
        return order;
    }

    private void publishReviewedEvent(TradingWithdrawOrder order, String result, long adminUserId,
                                       OffsetDateTime reviewAt, String rejectReason, OffsetDateTime delayedUntil) {
        outboxRepository.save(new TradingOutboxMessage(
                null,
                "withdraw-reviewed:" + order.id() + ":" + result,
                "trading.withdraw.reviewed",
                String.valueOf(order.userId()),
                new TradingWithdrawReviewedEventPayload(
                        order.id(),
                        order.userId(),
                        result,
                        adminUserId,
                        reviewAt,
                        rejectReason,
                        delayedUntil,
                        order.amount(),
                        order.currency(),
                        order.network().name(),
                        order.targetAddress()
                ),
                TradingOutboxStatus.PENDING,
                reviewAt,
                null,
                0,
                reviewAt,
                null
        ));
    }

    private static TradingWithdrawOrder rebuild(TradingWithdrawOrder order, TradingWithdrawOrderStatus status,
                                                  long reviewerId, OffsetDateTime reviewAt,
                                                  String reviewNote, String rejectReason,
                                                  OffsetDateTime delayedUntil) {
        return new TradingWithdrawOrder(
                order.id(), order.userId(), order.amount(), order.currency(),
                order.network(), order.targetAddress(), order.whitelistId(),
                status,
                order.coolingUntil(),
                delayedUntil != null ? delayedUntil : order.delayedUntil(),
                order.processingStartedAt(),
                reviewerId, reviewAt,
                reviewNote != null ? reviewNote : order.reviewNote(),
                rejectReason != null ? rejectReason : order.rejectReason(),
                order.txHash(), order.confirmations(), order.failureCode(), order.failureReason(),
                order.idempotencyKey(), order.dailyAmountUsdSnapshot(),
                order.createdAt(), reviewAt
        );
    }

    private static Integer parseStatusCode(String statusName) {
        if (statusName == null || statusName.isBlank()) return null;
        try {
            return TradingWithdrawOrderStatus.valueOf(statusName).code();
        } catch (IllegalArgumentException ex) {
            return -1;
        }
    }
}
