package com.falconx.trading.service.impl;

import com.falconx.trading.application.TradingSwapSettlementApplicationService;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.repository.TradingLedgerRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingSwapRateSnapshotRepository;
import com.falconx.trading.service.TradingSwapSettlementService;
import com.falconx.trading.service.model.TradingSwapRateRule;
import com.falconx.trading.service.model.TradingSwapRateSnapshot;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 隔夜利息批量结算服务默认实现。
 *
 * <p>该实现负责在每轮调度中：
 *
 * <ol>
 *   <li>扫描全部 `OPEN` 持仓</li>
 *   <li>读取 owner 费率共享快照</li>
 *   <li>根据最近一次已结算账本推导仍待补跑的 rollover 时点</li>
 *   <li>把每个候选时点委托给单笔事务服务完成正式落账</li>
 * </ol>
 *
 * <p>这样可以在不新增 schema 的前提下覆盖“重复调度、服务重启、stale 恢复后的补跑”路径。
 */
@Service
public class DefaultTradingSwapSettlementService implements TradingSwapSettlementService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTradingSwapSettlementService.class);

    private final TradingPositionRepository tradingPositionRepository;
    private final TradingSwapRateSnapshotRepository tradingSwapRateSnapshotRepository;
    private final TradingLedgerRepository tradingLedgerRepository;
    private final TradingSwapSettlementApplicationService tradingSwapSettlementApplicationService;

    public DefaultTradingSwapSettlementService(TradingPositionRepository tradingPositionRepository,
                                               TradingSwapRateSnapshotRepository tradingSwapRateSnapshotRepository,
                                               TradingLedgerRepository tradingLedgerRepository,
                                               TradingSwapSettlementApplicationService tradingSwapSettlementApplicationService) {
        this.tradingPositionRepository = tradingPositionRepository;
        this.tradingSwapRateSnapshotRepository = tradingSwapRateSnapshotRepository;
        this.tradingLedgerRepository = tradingLedgerRepository;
        this.tradingSwapSettlementApplicationService = tradingSwapSettlementApplicationService;
    }

    @Override
    public int settleDuePositions(OffsetDateTime now) {
        List<TradingPosition> openPositions = tradingPositionRepository.findAllOpenPositions();
        BatchStats batchStats = new BatchStats(openPositions.size());
        OffsetDateTime current = now == null ? OffsetDateTime.now(ZoneOffset.UTC) : now.withOffsetSameInstant(ZoneOffset.UTC);
        // 2026-05-20 perf: 把 N 次 Redis findBySymbol 改为 1 次 MGET 批量预加载到 Map。
        // 1000 持仓跨 50 品种 → 网络往返从 1000 次降到 1 次，调度延迟显著降低。
        java.util.Set<String> distinctSymbols = openPositions.stream()
                .map(TradingPosition::symbol)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        java.util.Map<String, TradingSwapRateSnapshot> snapshotBySymbol =
                tradingSwapRateSnapshotRepository.findBySymbols(distinctSymbols);
        for (TradingPosition position : openPositions) {
            TradingSwapRateSnapshot snapshot = snapshotBySymbol.get(position.symbol());
            if (snapshot == null) {
                batchStats.skippedNoSnapshot++;
                continue;
            }
            List<OffsetDateTime> dueRollovers = resolveDueRollovers(position, snapshot, current, batchStats);
            batchStats.dueCandidates += dueRollovers.size();
            for (OffsetDateTime rolloverAt : dueRollovers) {
                if (tradingSwapSettlementApplicationService.settlePositionAtRollover(position.positionId(), rolloverAt)) {
                    batchStats.appliedCount++;
                }
            }
        }
        if (batchStats.shouldLog()) {
            log.info("trading.swap.settlement.batch.completed openPositions={} dueCandidates={} appliedCount={} skippedNoSnapshot={} skippedNoRule={} skippedAlreadySettled={} now={}",
                    batchStats.openPositions,
                    batchStats.dueCandidates,
                    batchStats.appliedCount,
                    batchStats.skippedNoSnapshot,
                    batchStats.skippedNoRule,
                    batchStats.skippedAlreadySettled,
                    current);
        }
        return batchStats.appliedCount;
    }

    private List<OffsetDateTime> resolveDueRollovers(TradingPosition position,
                                                     TradingSwapRateSnapshot snapshot,
                                                     OffsetDateTime now,
                                                     BatchStats batchStats) {
        var settlementDate = now.toLocalDate();
        TradingSwapRateRule rule = snapshot.resolveEffectiveRule(settlementDate).orElse(null);
        if (rule == null || rule.rolloverTime() == null) {
            batchStats.skippedNoRule++;
            return List.of();
        }
        OffsetDateTime rolloverAt = OffsetDateTime.of(settlementDate, rule.rolloverTime(), ZoneOffset.UTC);
        if (position.openedAt().withOffsetSameInstant(ZoneOffset.UTC).isAfter(rolloverAt)
                || position.openedAt().withOffsetSameInstant(ZoneOffset.UTC).isEqual(rolloverAt)) {
            return List.of();
        }
        if (rolloverAt.isAfter(now)) {
            return List.of();
        }
        if (tradingLedgerRepository.findLatestSwapSettlementAt(position.userId(), position.positionId())
                .map(time -> {
                    boolean alreadySettled = time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().isEqual(settlementDate);
                    if (alreadySettled) {
                        batchStats.skippedAlreadySettled++;
                        log.info("trading.swap.settlement.duplicate positionId={} userId={} rolloverAt={}",
                                position.positionId(),
                                position.userId(),
                                rolloverAt);
                    }
                    return alreadySettled;
                })
                .orElse(false)) {
            return List.of();
        }
        return List.of(rolloverAt);
    }

    private static final class BatchStats {

        private final int openPositions;
        private int dueCandidates;
        private int appliedCount;
        private int skippedNoSnapshot;
        private int skippedNoRule;
        private int skippedAlreadySettled;

        private BatchStats(int openPositions) {
            this.openPositions = openPositions;
        }

        private boolean shouldLog() {
            return dueCandidates > 0
                    || appliedCount > 0
                    || skippedNoSnapshot > 0
                    || skippedNoRule > 0
                    || skippedAlreadySettled > 0;
        }
    }
}
