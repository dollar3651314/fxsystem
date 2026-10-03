package com.falconx.trading.engine;

import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingPriceAlertRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时从 owner MySQL 预热高频 tick 触发链路的活跃 symbol 索引。
 */
@Component
public class SymbolTriggerActivityBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SymbolTriggerActivityBootstrapRunner.class);

    private final TradingPendingOrderTriggerRepository pendingOrderRepository;
    private final TradingPriceAlertRepository priceAlertRepository;
    private final SymbolTriggerActivityRegistry registry;

    public SymbolTriggerActivityBootstrapRunner(TradingPendingOrderTriggerRepository pendingOrderRepository,
                                                TradingPriceAlertRepository priceAlertRepository,
                                                SymbolTriggerActivityRegistry registry) {
        this.pendingOrderRepository = pendingOrderRepository;
        this.priceAlertRepository = priceAlertRepository;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> pendingOrderSymbols = pendingOrderRepository.findPendingSymbols();
        List<String> priceAlertSymbols = priceAlertRepository.findActiveSymbols();
        registry.resetPendingOrderSymbols(pendingOrderSymbols);
        registry.resetPriceAlertSymbols(priceAlertSymbols);
        log.info("trading.trigger-activity.bootstrap.completed pendingOrderSymbols={} priceAlertSymbols={}",
                pendingOrderSymbols.size(), priceAlertSymbols.size());
    }
}
