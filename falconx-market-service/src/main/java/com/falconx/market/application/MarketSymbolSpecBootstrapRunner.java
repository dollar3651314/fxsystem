package com.falconx.market.application;

import com.falconx.market.service.MarketSymbolSpecWarmupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * SymbolSpec 共享快照启动器。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B：market 启动时把 mapping
 * 交易参数全量写入 Redis Hash，让 trading-core 在首次下单前可读取到 SymbolSpec。
 *
 * <p>{@link Order} 大于 swap-rate bootstrap（默认 0）确保依赖先加载。
 */
@Component
@Order(10)
public class MarketSymbolSpecBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketSymbolSpecBootstrapRunner.class);

    private final MarketSymbolSpecWarmupService warmupService;

    public MarketSymbolSpecBootstrapRunner(MarketSymbolSpecWarmupService warmupService) {
        this.warmupService = warmupService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("market.symbol-spec.bootstrap.start");
        warmupService.refreshAll();
        log.info("market.symbol-spec.bootstrap.ready");
    }
}
