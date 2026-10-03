package com.falconx.market.application;

import com.falconx.market.service.MarketTradingScheduleWarmupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 交易时间快照启动器。
 *
 * <p>该启动器在 `market-service` 启动时把交易时间规则写入 Redis，
 * 让 `trading-core-service` 在本轮开始后能够真正执行交易时段校验。
 */
@Component
public class MarketTradingScheduleBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketTradingScheduleBootstrapRunner.class);

    private final MarketTradingScheduleWarmupService marketTradingScheduleWarmupService;

    public MarketTradingScheduleBootstrapRunner(MarketTradingScheduleWarmupService marketTradingScheduleWarmupService) {
        this.marketTradingScheduleWarmupService = marketTradingScheduleWarmupService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("market.trading.schedule.bootstrap.start");
        marketTradingScheduleWarmupService.refreshAll();
        log.info("market.trading.schedule.bootstrap.ready");
    }
}
