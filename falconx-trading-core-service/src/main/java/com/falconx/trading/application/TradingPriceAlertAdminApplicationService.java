package com.falconx.trading.application;

import com.falconx.trading.entity.TradingPriceAlert;
import com.falconx.trading.entity.TradingPriceAlertStatus;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingPriceAlertRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-4-PRICE-ALERT：管理端价格告警监控 + 强制删除。
 */
@Service
public class TradingPriceAlertAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingPriceAlertAdminApplicationService.class);

    private final TradingPriceAlertRepository priceAlertRepository;

    public TradingPriceAlertAdminApplicationService(TradingPriceAlertRepository priceAlertRepository) {
        this.priceAlertRepository = priceAlertRepository;
    }

    public List<TradingPriceAlert> listAdmin(Long userId, String symbol, Integer statusCode, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return priceAlertRepository.findAdminPaginated(userId, symbol, statusCode, offset, pageSize);
    }

    public long countAdmin(Long userId, String symbol, Integer statusCode) {
        return priceAlertRepository.countAdminFiltered(userId, symbol, statusCode);
    }

    /**
     * 强制删除：仅 ACTIVE 可删；写入 cancel_source=ADMIN:{adminUserId}。
     */
    @Transactional
    public TradingPriceAlert forceDelete(Long alertId, long adminUserId, String reason) {
        TradingPriceAlert alert = priceAlertRepository.findByIdForUpdate(alertId)
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.PRICE_ALERT_NOT_FOUND, Map.of("id", alertId)));
        if (alert.status() != TradingPriceAlertStatus.ACTIVE) {
            // 已是终态：幂等返回快照（避免重复删）
            return alert;
        }
        String source = "ADMIN:" + adminUserId + (reason == null ? "" : ":" + reason);
        int updated = priceAlertRepository.markCancelled(alertId,
                TradingPriceAlertStatus.ADMIN_DELETED.code(), source);
        if (updated == 0) {
            throw new TradingBusinessException(TradingErrorCode.PRICE_ALERT_NOT_FOUND, Map.of("id", alertId));
        }
        log.warn("trading.price-alert.admin-deleted id={} adminUserId={} reason={}", alertId, adminUserId, reason);
        return priceAlertRepository.findById(alertId).orElseThrow();
    }
}
