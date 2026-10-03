package com.falconx.market.application;

import com.falconx.market.service.MarketSwapRateWarmupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 隔夜利息费率共享快照启动器。
 *
 * <p>该启动器在 `market-service` 启动时把 owner 费率历史写入 Redis，
 * 让 `trading-core-service` 在首次 `Swap` 结算扫描前即可读取到正式费率来源。
 */
@Component
public class MarketSwapRateBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketSwapRateBootstrapRunner.class);

    private final MarketSwapRateWarmupService marketSwapRateWarmupService;

    public MarketSwapRateBootstrapRunner(MarketSwapRateWarmupService marketSwapRateWarmupService) {
        this.marketSwapRateWarmupService = marketSwapRateWarmupService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("market.swap-rate.bootstrap.start");
        marketSwapRateWarmupService.refreshAll();
        log.info("market.swap-rate.bootstrap.ready");
    }
}
