package com.falconx.trading.application;

import com.falconx.trading.api.AdminRiskActionListResponse;
import com.falconx.trading.api.AdminRiskConfigListResponse;
import com.falconx.trading.api.AdminRiskMarketConfigListResponse;
import com.falconx.trading.entity.TradingRiskConfig;
import com.falconx.trading.entity.TradingRiskControlAction;
import com.falconx.trading.entity.TradingRiskControlActionType;
import com.falconx.trading.entity.TradingRiskMarketConfig;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.repository.TradingRiskControlActionRepository;
import com.falconx.trading.repository.TradingRiskMarketConfigRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-2-RISK-ADMIN R4：风控管理端编排服务。
 *
 * <p>承接 {@code AdminInternalTradingRiskController}：风控动作（list / activate MANUAL_ADMIN / deactivate）+
 * risk_config CRUD + risk_market_config 编辑。
 */
@Service
public class TradingRiskAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingRiskAdminApplicationService.class);

    public static final String TRIGGER_SOURCE_MANUAL_ADMIN = "MANUAL_ADMIN";

    private static final int MAX_LEVERAGE_LOWER = 1;
    private static final int MAX_LEVERAGE_UPPER = 500;

    private final TradingRiskControlActionRepository riskControlActionRepository;
    private final TradingRiskConfigRepository riskConfigRepository;
    private final TradingRiskMarketConfigRepository riskMarketConfigRepository;
    private final com.falconx.trading.websocket.TradingAdminRealtimePushService adminRealtimePushService;

    public TradingRiskAdminApplicationService(TradingRiskControlActionRepository riskControlActionRepository,
                                              TradingRiskConfigRepository riskConfigRepository,
                                              TradingRiskMarketConfigRepository riskMarketConfigRepository,
                                              com.falconx.trading.websocket.TradingAdminRealtimePushService adminRealtimePushService) {
        this.riskControlActionRepository = riskControlActionRepository;
        this.riskConfigRepository = riskConfigRepository;
        this.riskMarketConfigRepository = riskMarketConfigRepository;
        this.adminRealtimePushService = adminRealtimePushService;
    }

    // ---- risk-actions ----

    public AdminRiskActionListResponse listRiskActions(String symbol, TradingRiskControlActionType actionType,
                                                       String triggerSource, Boolean isActive,
                                                       java.time.OffsetDateTime fromCreatedAt,
                                                       java.time.OffsetDateTime toCreatedAt,
                                                       int page, int size) {
        int offset = (page - 1) * size;
        List<TradingRiskControlAction> actions = riskControlActionRepository.findAdminPaginated(
                symbol, actionType, triggerSource, isActive, fromCreatedAt, toCreatedAt, offset, size);
        long total = riskControlActionRepository.countAdminFiltered(
                symbol, actionType, triggerSource, isActive, fromCreatedAt, toCreatedAt);
        List<AdminRiskActionListResponse.Item> items = actions.stream()
                .map(a -> new AdminRiskActionListResponse.Item(
                        a.actionId(),
                        a.symbol(),
                        a.actionType().name(),
                        a.triggerSource(),
                        a.triggerReason(),
                        a.hedgeLogId(),
                        a.active(),
                        a.createdAt() == null ? null : a.createdAt().toLocalDateTime(),
                        a.updatedAt() == null ? null : a.updatedAt().toLocalDateTime()
                ))
                .toList();
        return new AdminRiskActionListResponse(items, total, page, size);
    }

    @Transactional
    public TradingRiskControlAction activateRiskAction(String symbol, TradingRiskControlActionType actionType,
                                                       String reason) {
        if (actionType == TradingRiskControlActionType.GLOBAL_PAUSE && symbol != null && !symbol.isBlank()) {
            symbol = null; // GLOBAL_PAUSE 必须全局
        }
        boolean inserted = riskControlActionRepository.activateIfAbsent(
                symbol, actionType, TRIGGER_SOURCE_MANUAL_ADMIN, reason, null);
        if (!inserted) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_RISK_ACTION_ALREADY_ACTIVE,
                    Map.of("symbol", symbol == null ? "*global*" : symbol, "actionType", actionType.name())
            );
        }
        log.warn("trading.admin.risk-action.activated symbol={} actionType={} reason={}", symbol, actionType, reason);
        // 重新读 most-severe 拿不到具体 id，本接口直接构造响应字段；列表 list 会带 id
        TradingRiskControlAction action = new TradingRiskControlAction(
                null, symbol, actionType, true, TRIGGER_SOURCE_MANUAL_ADMIN, reason, null, null, null);
        // STAGE-2-REALTIME-DATA Phase 2：管理端推送
        try {
            adminRealtimePushService.publishRiskActionChanged(action, java.time.OffsetDateTime.now());
        } catch (RuntimeException exception) {
            log.warn("trading.admin.risk-action.push.failed symbol={} actionType={} message={}",
                    symbol, actionType, exception.getMessage());
        }
        return action;
    }

    @Transactional
    public TradingRiskControlAction deactivateRiskAction(long id, String reason) {
        TradingRiskControlAction current = riskControlActionRepository.findById(id)
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.ADMIN_RISK_ACTION_NOT_FOUND, Map.of("id", id)));
        if (!TRIGGER_SOURCE_MANUAL_ADMIN.equals(current.triggerSource())) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_RISK_ACTION_NOT_DEACTIVATABLE,
                    Map.of("id", id, "triggerSource", current.triggerSource())
            );
        }
        int affected = riskControlActionRepository.deactivateAdminById(id);
        if (affected == 0) {
            // 已被其他链路停用
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_RISK_ACTION_NOT_FOUND, Map.of("id", id));
        }
        log.warn("trading.admin.risk-action.deactivated id={} reason={}", id, reason);
        TradingRiskControlAction updated = riskControlActionRepository.findById(id).orElseThrow();
        // STAGE-2-REALTIME-DATA Phase 2：管理端推送
        try {
            adminRealtimePushService.publishRiskActionChanged(updated, java.time.OffsetDateTime.now());
        } catch (RuntimeException exception) {
            log.warn("trading.admin.risk-action.push.failed id={} message={}",
                    id, exception.getMessage());
        }
        return updated;
    }

    // ---- risk-configs ----

    public AdminRiskConfigListResponse listRiskConfigs(String symbol, String marketCode, int page, int size) {
        int offset = (page - 1) * size;
        List<TradingRiskConfig> configs = riskConfigRepository.findAdminPaginated(symbol, marketCode, offset, size);
        long total = riskConfigRepository.countAdminFiltered(symbol, marketCode);
        List<AdminRiskConfigListResponse.Item> items = configs.stream().map(this::toRiskConfigItem).toList();
        return new AdminRiskConfigListResponse(items, total, page, size);
    }

    public AdminRiskConfigListResponse.Item getRiskConfig(String symbol) {
        TradingRiskConfig config = riskConfigRepository.findBySymbol(symbol)
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.ADMIN_RISK_CONFIG_NOT_FOUND, Map.of("symbol", symbol)));
        return toRiskConfigItem(config);
    }

    @Transactional
    public AdminRiskConfigListResponse.Item createRiskConfig(String symbol, String marketCode,
                                                             BigDecimal maxPositionPerUser, BigDecimal maxPositionTotal,
                                                             BigDecimal maintenanceMarginRate, Integer maxLeverage,
                                                             BigDecimal hedgeThresholdUsd) {
        if (riskConfigRepository.findBySymbol(symbol).isPresent()) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_RISK_CONFIG_DUPLICATE_SYMBOL, Map.of("symbol", symbol));
        }
        validateLeverage(maxLeverage);
        validatePositionLimits(maxPositionPerUser, maxPositionTotal);
        validateHedgeThreshold(hedgeThresholdUsd);

        TradingRiskConfig newConfig = new TradingRiskConfig(
                null, symbol, marketCode, maxPositionPerUser, maxPositionTotal,
                maintenanceMarginRate, maxLeverage, hedgeThresholdUsd,
                null, null, null, null, null);
        riskConfigRepository.insertAdmin(newConfig);
        TradingRiskConfig stored = riskConfigRepository.findBySymbol(symbol).orElseThrow();
        log.warn("trading.admin.risk-config.created symbol={} marketCode={}", symbol, marketCode);
        return toRiskConfigItem(stored);
    }

    @Transactional
    public AdminRiskConfigListResponse.Item updateRiskConfig(String symbol, BigDecimal maxPositionPerUser,
                                                             BigDecimal maxPositionTotal, Integer maxLeverage,
                                                             BigDecimal hedgeThresholdUsd) {
        if (riskConfigRepository.findBySymbol(symbol).isEmpty()) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_RISK_CONFIG_NOT_FOUND, Map.of("symbol", symbol));
        }
        validateLeverage(maxLeverage);
        validatePositionLimits(maxPositionPerUser, maxPositionTotal);
        validateHedgeThreshold(hedgeThresholdUsd);

        riskConfigRepository.updateAdminBySymbol(symbol, maxPositionPerUser, maxPositionTotal, maxLeverage, hedgeThresholdUsd);
        TradingRiskConfig updated = riskConfigRepository.findBySymbol(symbol).orElseThrow();
        log.warn("trading.admin.risk-config.updated symbol={}", symbol);
        return toRiskConfigItem(updated);
    }

    @Transactional
    public void deleteRiskConfig(String symbol) {
        if (riskConfigRepository.findBySymbol(symbol).isEmpty()) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_RISK_CONFIG_NOT_FOUND, Map.of("symbol", symbol));
        }
        riskConfigRepository.deleteBySymbol(symbol);
        log.warn("trading.admin.risk-config.deleted symbol={}", symbol);
    }

    // ---- risk-market-configs ----

    public AdminRiskMarketConfigListResponse listRiskMarketConfigs() {
        List<TradingRiskMarketConfig> configs = riskMarketConfigRepository.findAll();
        List<AdminRiskMarketConfigListResponse.Item> items = configs.stream()
                .map(c -> new AdminRiskMarketConfigListResponse.Item(
                        c.marketCode(),
                        c.concentrationThresholdUsd(),
                        c.enabled(),
                        c.createdAt(),
                        c.updatedAt()
                ))
                .toList();
        return new AdminRiskMarketConfigListResponse(items);
    }

    @Transactional
    public AdminRiskMarketConfigListResponse.Item updateRiskMarketConfig(String marketCode,
                                                                          BigDecimal concentrationThresholdUsd,
                                                                          boolean enabled) {
        if (concentrationThresholdUsd.signum() < 0) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_RISK_MARKET_CONFIG_INVALID_THRESHOLD,
                    Map.of("threshold", concentrationThresholdUsd));
        }
        int affected = riskMarketConfigRepository.updateAdminByMarketCode(marketCode, concentrationThresholdUsd, enabled);
        if (affected == 0) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_RISK_MARKET_CONFIG_NOT_FOUND, Map.of("marketCode", marketCode));
        }
        TradingRiskMarketConfig updated = riskMarketConfigRepository.findByMarketCode(marketCode).orElseThrow();
        log.warn("trading.admin.risk-market-config.updated marketCode={} enabled={}", marketCode, enabled);
        return new AdminRiskMarketConfigListResponse.Item(
                updated.marketCode(),
                updated.concentrationThresholdUsd(),
                updated.enabled(),
                updated.createdAt(),
                updated.updatedAt()
        );
    }

    // ---- helpers ----

    private AdminRiskConfigListResponse.Item toRiskConfigItem(TradingRiskConfig c) {
        return new AdminRiskConfigListResponse.Item(
                c.riskConfigId(),
                c.symbol(),
                c.marketCode(),
                c.maxPositionPerUser(),
                c.maxPositionTotal(),
                c.maintenanceMarginRate(),
                c.maxLeverage(),
                c.hedgeThresholdUsd(),
                c.createdAt() == null ? null : c.createdAt().toLocalDateTime(),
                c.updatedAt() == null ? null : c.updatedAt().toLocalDateTime()
        );
    }

    private void validateLeverage(Integer maxLeverage) {
        if (maxLeverage == null || maxLeverage < MAX_LEVERAGE_LOWER || maxLeverage > MAX_LEVERAGE_UPPER) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_RISK_CONFIG_INVALID_LEVERAGE,
                    Map.of("maxLeverage", String.valueOf(maxLeverage))
            );
        }
    }

    private void validatePositionLimits(BigDecimal maxPositionPerUser, BigDecimal maxPositionTotal) {
        if (maxPositionPerUser == null || maxPositionPerUser.signum() < 0
                || maxPositionTotal == null || maxPositionTotal.signum() < 0
                || maxPositionPerUser.compareTo(maxPositionTotal) > 0) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_RISK_CONFIG_INVALID_POSITION_LIMIT,
                    Map.of("maxPositionPerUser", String.valueOf(maxPositionPerUser),
                            "maxPositionTotal", String.valueOf(maxPositionTotal))
            );
        }
    }

    private void validateHedgeThreshold(BigDecimal hedgeThresholdUsd) {
        if (hedgeThresholdUsd == null || hedgeThresholdUsd.signum() < 0) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_RISK_CONFIG_INVALID_HEDGE_THRESHOLD,
                    Map.of("hedgeThresholdUsd", String.valueOf(hedgeThresholdUsd))
            );
        }
    }
}
