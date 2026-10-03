package com.falconx.trading.application;

import com.falconx.trading.entity.TradingRiskSwitch;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.RedisTradingRiskSwitchCache;
import com.falconx.trading.repository.TradingRiskSwitchRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * STAGE-2-TRADING-MONITOR R4：风控全局开关管理服务。
 *
 * <p>变更路径：DB UPSERT → afterCommit 刷 Redis；QuoteDrivenEngine 每 tick 通过 Redis 缓存读取。
 *
 * <p>白名单：只允许已注册的 key（见 {@link #ALLOWED_KEYS}），其他 key 抛 90654。
 */
@Service
public class TradingRiskSwitchApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingRiskSwitchApplicationService.class);

    /** 白名单 key 集合；新增风控开关时在此扩展。 */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            TradingRiskSwitch.KEY_AUTO_LIQUIDATE_ENABLED,
            // STAGE-14D1 Task 3：CROSS margin mode 启用开关（默认 false；D2 强平就位后 admin 开启）
            TradingRiskSwitch.KEY_CROSS_MODE_ENABLED
    );

    private final TradingRiskSwitchRepository repository;
    private final RedisTradingRiskSwitchCache cache;

    public TradingRiskSwitchApplicationService(TradingRiskSwitchRepository repository,
                                               RedisTradingRiskSwitchCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public List<TradingRiskSwitch> listAll() {
        return repository.findAll();
    }

    @Transactional
    public TradingRiskSwitch updateSwitch(String switchKey, boolean enabled, String reason, String updatedBy) {
        if (!ALLOWED_KEYS.contains(switchKey)) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_TRADING_RISK_SWITCH_KEY_INVALID,
                    Map.of("switchKey", switchKey)
            );
        }
        TradingRiskSwitch current = repository.findByKey(switchKey).orElse(null);
        if (current != null && current.enabled() == enabled) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_TRADING_RISK_SWITCH_VALUE_UNCHANGED,
                    Map.of("switchKey", switchKey, "enabled", enabled)
            );
        }
        OffsetDateTime now = OffsetDateTime.now();
        TradingRiskSwitch next = new TradingRiskSwitch(
                switchKey,
                enabled,
                reason,
                updatedBy,
                current != null ? current.createdAt() : now,
                now
        );
        repository.upsert(next);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.write(next);
                log.info("trading.risk-switch.cache.refreshed switchKey={} enabled={} updatedBy={}",
                        switchKey, enabled, updatedBy);
            }
        });
        return next;
    }
}
