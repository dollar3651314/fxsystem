package com.falconx.trading.application;

import com.falconx.trading.entity.TradingRiskSwitch;
import com.falconx.trading.repository.RedisTradingRiskSwitchCache;
import com.falconx.trading.repository.TradingRiskSwitchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把 t_trading_risk_switch 全量写入 Redis，
 * 让 QuoteDrivenEngine 在收第一条 tick 前已能从缓存命中开关。
 */
@Component
public class TradingRiskSwitchBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TradingRiskSwitchBootstrapRunner.class);

    private final TradingRiskSwitchRepository repository;
    private final RedisTradingRiskSwitchCache cache;

    public TradingRiskSwitchBootstrapRunner(TradingRiskSwitchRepository repository,
                                            RedisTradingRiskSwitchCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    @Override
    public void run(ApplicationArguments args) {
        int loaded = 0;
        for (TradingRiskSwitch riskSwitch : repository.findAll()) {
            cache.write(riskSwitch);
            loaded++;
        }
        log.info("trading.risk-switch.warmup.completed loadedKeys={}", loaded);
    }
}
