package com.falconx.trading.application;

import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPendingOrderTriggerKind;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-3-PENDING-ORDER 启动时 backfill：
 * 把 t_position 现有 OPEN 持仓的 take_profit_price / stop_loss_price
 * 用雪花 ID 迁移到 t_pending_order_trigger（SL_TP 类型 + parent_position_id 关联）。
 *
 * <p>幂等：跳过已存在对应 parent_position_id + trigger_kind 的 PENDING 行。
 *
 * <p>双轨期：旧字段保留到 V17 删除；该 backfill 只为新挂单表补齐 SL_TP 行，
 * 防止挂单触发引擎与持仓字段触发引擎错位（一边平了另一边漏剪）。
 */
@Component
@Order(100)
public class PendingOrderMigrationBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PendingOrderMigrationBackfill.class);

    private final TradingPositionRepository tradingPositionRepository;
    private final TradingPendingOrderTriggerRepository pendingOrderRepository;
    private final TradingPendingOrderApplicationService pendingOrderApplicationService;

    public PendingOrderMigrationBackfill(TradingPositionRepository tradingPositionRepository,
                                          TradingPendingOrderTriggerRepository pendingOrderRepository,
                                          TradingPendingOrderApplicationService pendingOrderApplicationService) {
        this.tradingPositionRepository = tradingPositionRepository;
        this.pendingOrderRepository = pendingOrderRepository;
        this.pendingOrderApplicationService = pendingOrderApplicationService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<TradingPosition> openPositions = tradingPositionRepository.findAllOpenPositions();
        int processedTp = 0;
        int processedSl = 0;
        int skipped = 0;
        for (TradingPosition position : openPositions) {
            boolean hasTp = position.takeProfitPrice() != null;
            boolean hasSl = position.stopLossPrice() != null;
            if (!hasTp && !hasSl) {
                continue;
            }
            // 幂等：已有对应 PENDING 行就跳过
            var existing = pendingOrderRepository.findSlTpByPositionId(position.positionId());
            boolean existingTp = existing.stream().anyMatch(o -> o.triggerKind() == TradingPendingOrderTriggerKind.TAKE_PROFIT);
            boolean existingSl = existing.stream().anyMatch(o -> o.triggerKind() == TradingPendingOrderTriggerKind.STOP_LOSS);

            try {
                pendingOrderApplicationService.upsertSlTpForPosition(
                        position.positionId(),
                        hasTp && !existingTp ? position.takeProfitPrice() : null,
                        hasSl && !existingSl ? position.stopLossPrice() : null,
                        hasTp && !existingTp,
                        hasSl && !existingSl);
                if (hasTp && !existingTp) processedTp++;
                if (hasSl && !existingSl) processedSl++;
                if ((hasTp && existingTp) || (hasSl && existingSl)) skipped++;
            } catch (RuntimeException ex) {
                log.warn("trading.pending-order.backfill.position-failed positionId={} message={}",
                        position.positionId(), ex.getMessage());
            }
        }
        log.info("trading.pending-order.backfill.completed totalOpenPositions={} processedTp={} processedSl={} alreadyExisting={}",
                openPositions.size(), processedTp, processedSl, skipped);
    }
}
