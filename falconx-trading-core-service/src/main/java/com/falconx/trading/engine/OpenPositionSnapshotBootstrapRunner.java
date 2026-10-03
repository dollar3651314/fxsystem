package com.falconx.trading.engine;

import com.falconx.trading.repository.TradingPositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * OPEN 持仓内存快照启动装载器。
 *
 * <p>该启动器在服务可用前从数据库读取当前全部 OPEN 持仓，
 * 构建 `QuoteDrivenEngine` 后续运行时依赖的按 symbol 分组索引。
 */
@Component
public class OpenPositionSnapshotBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OpenPositionSnapshotBootstrapRunner.class);

    private final TradingPositionRepository tradingPositionRepository;
    private final OpenPositionSnapshotStore openPositionSnapshotStore;

    public OpenPositionSnapshotBootstrapRunner(TradingPositionRepository tradingPositionRepository,
                                              OpenPositionSnapshotStore openPositionSnapshotStore) {
        this.tradingPositionRepository = tradingPositionRepository;
        this.openPositionSnapshotStore = openPositionSnapshotStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        openPositionSnapshotStore.replaceAll(tradingPositionRepository.findAllOpenPositions());
        log.info("trading.position.snapshot.bootstrap.completed");
    }
}
