package com.falconx.trading.application;

import com.falconx.trading.entity.TradingWithdrawOrder;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingWithdrawOrderRepository;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.websocket.TradingAdminRealtimePushService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-7-WITHDRAW Phase 2：用户冷静期取消出金应用服务。
 *
 * <p>状态机：仅允许 {@code COOLING → CANCELED}。其他状态调用返回
 * {@link TradingErrorCode#WITHDRAW_NOT_CANCELABLE WITHDRAW_NOT_CANCELABLE} (30048)。
 *
 * <p>资金语义：{@code t_account.frozen -= amount}；账本 {@code biz_type=14 WITHDRAW_REFUND_CANCEL}。
 *
 * <p>并发：通过 {@code markCanceledByUserAtomic} CAS 串行化；同一记录并发取消只允许一次成功，
 * 另一次返回 0 行 → 30048。
 */
@Service
public class WithdrawCancelApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawCancelApplicationService.class);

    private final TradingWithdrawOrderRepository withdrawOrderRepository;
    private final TradingAccountService accountService;
    private final TradingAdminRealtimePushService adminRealtimePushService;

    public WithdrawCancelApplicationService(TradingWithdrawOrderRepository withdrawOrderRepository,
                                             TradingAccountService accountService,
                                             TradingAdminRealtimePushService adminRealtimePushService) {
        this.withdrawOrderRepository = withdrawOrderRepository;
        this.accountService = accountService;
        this.adminRealtimePushService = adminRealtimePushService;
    }

    @Transactional
    public TradingWithdrawOrder cancel(long userId, long withdrawId) {
        Optional<TradingWithdrawOrder> found = withdrawOrderRepository.findById(withdrawId);
        if (found.isEmpty() || found.get().userId() != userId) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_NOT_FOUND,
                    Map.of("withdrawId", withdrawId));
        }
        TradingWithdrawOrder order = found.get();
        if (order.status() != TradingWithdrawOrderStatus.COOLING) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_NOT_CANCELABLE,
                    Map.of("status", order.status().name()));
        }

        int updated = withdrawOrderRepository.markCanceledByUserAtomic(withdrawId, userId);
        if (updated == 0) {
            // 并发竞态：另一线程已经把状态切走（极少见，但 CAS 必须返回业务错误而不是静默成功）
            log.warn("trading.withdraw.cancel.cas-conflict userId={} withdrawId={}", userId, withdrawId);
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_NOT_CANCELABLE,
                    Map.of("reason", "cas-conflict"));
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        accountService.refundCanceledWithdraw(
                userId,
                order.currency(),
                order.amount(),
                "withdraw-refund-cancel:" + order.idempotencyKey(),
                "withdraw:" + withdrawId,
                now
        );

        adminRealtimePushService.publishWithdrawStatusChanged(
                withdrawId, userId, "CANCELED", null, null, null);
        log.info("trading.withdraw.cancel.completed userId={} withdrawId={} amount={} currency={}",
                userId, withdrawId, order.amount(), order.currency());

        // 返回最新视图（status=CANCELED）；不再回查 DB，直接基于已知字段构造
        return new TradingWithdrawOrder(
                order.id(), order.userId(), order.amount(), order.currency(),
                order.network(), order.targetAddress(), order.whitelistId(),
                TradingWithdrawOrderStatus.CANCELED,
                order.coolingUntil(), order.delayedUntil(), order.processingStartedAt(),
                order.reviewerId(), order.reviewAt(), order.reviewNote(), order.rejectReason(),
                order.txHash(), order.confirmations(), order.failureCode(), order.failureReason(),
                order.idempotencyKey(), order.dailyAmountUsdSnapshot(),
                order.createdAt(), now
        );
    }
}
