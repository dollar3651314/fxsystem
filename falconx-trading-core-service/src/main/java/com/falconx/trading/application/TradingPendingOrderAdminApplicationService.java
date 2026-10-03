package com.falconx.trading.application;

import com.falconx.trading.entity.TradingPendingOrderStatus;
import com.falconx.trading.entity.TradingPendingOrderTrigger;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.config.TradingCoreServiceProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-3-PENDING-ORDER：管理端挂单监控 + 强制撤单。
 */
@Service
public class TradingPendingOrderAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingPendingOrderAdminApplicationService.class);

    private final TradingPendingOrderTriggerRepository pendingOrderRepository;
    private final TradingAccountService tradingAccountService;
    private final TradingCoreServiceProperties properties;

    public TradingPendingOrderAdminApplicationService(TradingPendingOrderTriggerRepository pendingOrderRepository,
                                                       TradingAccountService tradingAccountService,
                                                       TradingCoreServiceProperties properties) {
        this.pendingOrderRepository = pendingOrderRepository;
        this.tradingAccountService = tradingAccountService;
        this.properties = properties;
    }

    public List<TradingPendingOrderTrigger> listAdmin(Long userId, String symbol, Integer statusCode,
                                                       boolean includeSlTp, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return pendingOrderRepository.findAdminPaginated(userId, symbol, statusCode, includeSlTp, offset, pageSize);
    }

    public long countAdmin(Long userId, String symbol, Integer statusCode, boolean includeSlTp) {
        return pendingOrderRepository.countAdminFiltered(userId, symbol, statusCode, includeSlTp);
    }

    /**
     * 管理员强制撤单：跳过 user 校验，但仍要求 status=PENDING。
     */
    @Transactional
    public TradingPendingOrderTrigger cancelByAdmin(Long pendingOrderId, String reason) {
        TradingPendingOrderTrigger order = pendingOrderRepository.findByIdForUpdate(pendingOrderId)
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.PENDING_ORDER_NOT_FOUND, Map.of("id", pendingOrderId)));
        if (order.status() != TradingPendingOrderStatus.PENDING) {
            throw new TradingBusinessException(
                    TradingErrorCode.PENDING_ORDER_INVALID_STATE,
                    Map.of("id", pendingOrderId, "currentStatus", order.status().name()));
        }
        int updated = pendingOrderRepository.markCancelled(pendingOrderId, reason);
        if (updated == 0) {
            throw new TradingBusinessException(
                    TradingErrorCode.PENDING_ORDER_INVALID_STATE,
                    Map.of("id", pendingOrderId, "currentStatus", "CONCURRENT_CHANGE"));
        }
        BigDecimal totalFrozen = order.frozenMargin().add(order.frozenFee());
        if (totalFrozen.signum() > 0) {
            tradingAccountService.releaseFrozen(
                    order.userId(), properties.getSettlementToken(),
                    totalFrozen, "admin-pending-order-release:" + order.orderNo(),
                    order.orderNo(), OffsetDateTime.now());
        }
        log.warn("trading.pending-order.admin-cancelled id={} userId={} reason={}", pendingOrderId, order.userId(), reason);
        return pendingOrderRepository.findById(pendingOrderId).orElseThrow();
    }
}
