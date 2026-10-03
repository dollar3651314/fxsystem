package com.falconx.trading.application;

import com.falconx.trading.api.AdminUserRiskThresholdItem;
import com.falconx.trading.api.AdminUserRiskThresholdListResponse;
import com.falconx.trading.entity.TradingUserRiskThreshold;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.repository.TradingUserRiskThresholdRepository;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BBOOK-RISK-CONTROL-01：管理端编排服务（平台 hedge_threshold / 方向集中度 / 用户级阈值）。
 */
@Service
public class TradingBBookRiskControlAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingBBookRiskControlAdminApplicationService.class);

    private final TradingRiskConfigRepository riskConfigRepository;
    private final TradingUserRiskThresholdRepository userRiskThresholdRepository;

    public TradingBBookRiskControlAdminApplicationService(
            TradingRiskConfigRepository riskConfigRepository,
            TradingUserRiskThresholdRepository userRiskThresholdRepository) {
        this.riskConfigRepository = riskConfigRepository;
        this.userRiskThresholdRepository = userRiskThresholdRepository;
    }

    @Transactional
    public void updatePlatformHedgeThreshold(BigDecimal hedgeThresholdUsd) {
        int affected = riskConfigRepository.updatePlatformHedgeThreshold(hedgeThresholdUsd);
        log.warn("trading.admin.risk-config.platform.updated hedgeThresholdUsd={} affected={}",
                hedgeThresholdUsd, affected);
    }

    @Transactional
    public void updateDirectionImbalance(String symbol, BigDecimal ratioThreshold, BigDecimal minTotalUsd) {
        int affected = riskConfigRepository.updateDirectionImbalance(symbol, ratioThreshold, minTotalUsd);
        log.warn("trading.admin.risk-config.direction-imbalance.updated symbol={} ratio={} minTotalUsd={} affected={}",
                symbol, ratioThreshold, minTotalUsd, affected);
    }

    public AdminUserRiskThresholdListResponse listUserRiskThresholds(Long userId, int page, int size) {
        int offset = (page - 1) * size;
        List<TradingUserRiskThreshold> items = userRiskThresholdRepository.findAdminPaginated(userId, offset, size);
        long total = userRiskThresholdRepository.countAdminFiltered(userId);
        return new AdminUserRiskThresholdListResponse(
                items.stream().map(this::toItem).toList(), total, page, size);
    }

    @Transactional
    public AdminUserRiskThresholdItem upsertUserRiskThreshold(Long userId,
                                                              BigDecimal netExposureThresholdUsd,
                                                              BigDecimal profitableNetExposureThresholdUsd,
                                                              boolean profitableUser,
                                                              String updatedBy,
                                                              String updatedReason) {
        userRiskThresholdRepository.upsert(userId, netExposureThresholdUsd, profitableNetExposureThresholdUsd,
                profitableUser, updatedBy, updatedReason);
        log.warn("trading.admin.user-risk-threshold.upserted userId={} profitable={} updatedBy={}",
                userId, profitableUser, updatedBy);
        return userRiskThresholdRepository.findByUserId(userId).map(this::toItem).orElseThrow();
    }

    @Transactional
    public void deleteUserRiskThreshold(Long userId) {
        int affected = userRiskThresholdRepository.deleteByUserId(userId);
        log.warn("trading.admin.user-risk-threshold.deleted userId={} affected={}", userId, affected);
    }

    private AdminUserRiskThresholdItem toItem(TradingUserRiskThreshold t) {
        return new AdminUserRiskThresholdItem(
                t.userId(),
                t.netExposureThresholdUsd(),
                t.profitableNetExposureThresholdUsd(),
                t.profitableUser(),
                t.updatedBy(),
                t.updatedReason(),
                t.updatedAt() == null ? null : t.updatedAt().toLocalDateTime()
        );
    }
}
