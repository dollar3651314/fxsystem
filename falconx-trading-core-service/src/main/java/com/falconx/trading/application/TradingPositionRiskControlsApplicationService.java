package com.falconx.trading.application;

import com.falconx.trading.command.UpdatePositionRiskControlsCommand;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.websocket.TradingUserRealtimePushService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 持仓 TP/SL 修改应用服务。
 */
@Service
public class TradingPositionRiskControlsApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingPositionRiskControlsApplicationService.class);

    private final TradingPositionRepository tradingPositionRepository;
    private final OpenPositionSnapshotStore openPositionSnapshotStore;
    private final TradingUserRealtimePushService tradingUserRealtimePushService;
    // STAGE-3-PENDING-ORDER 双轨：可选注入；为 null 时只走旧持仓字段路径
    private final TradingPendingOrderApplicationService pendingOrderApplicationService;

    public TradingPositionRiskControlsApplicationService(TradingPositionRepository tradingPositionRepository,
                                                         OpenPositionSnapshotStore openPositionSnapshotStore,
                                                         TradingUserRealtimePushService tradingUserRealtimePushService,
                                                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                         TradingPendingOrderApplicationService pendingOrderApplicationService) {
        this.tradingPositionRepository = tradingPositionRepository;
        this.openPositionSnapshotStore = openPositionSnapshotStore;
        this.tradingUserRealtimePushService = tradingUserRealtimePushService;
        this.pendingOrderApplicationService = pendingOrderApplicationService;
    }

    /**
     * 修改 OPEN 持仓的 TP/SL。
     */
    @Transactional
    public TradingPosition updateRiskControls(UpdatePositionRiskControlsCommand command) {
        TradingPosition position = tradingPositionRepository.findByIdAndUserIdForUpdate(command.positionId(), command.userId())
                .orElseThrow(() -> new TradingBusinessException(TradingErrorCode.POSITION_NOT_FOUND));
        if (position.isTerminal()) {
            throw new TradingBusinessException(TradingErrorCode.POSITION_ALREADY_CLOSED);
        }

        BigDecimal nextTakeProfitPrice = command.takeProfitPriceProvided() ? command.takeProfitPrice() : position.takeProfitPrice();
        BigDecimal nextStopLossPrice = command.stopLossPriceProvided() ? command.stopLossPrice() : position.stopLossPrice();
        if (sameDecimal(position.takeProfitPrice(), nextTakeProfitPrice)
                && sameDecimal(position.stopLossPrice(), nextStopLossPrice)) {
            log.info("trading.position.risk-controls.noop userId={} positionId={} takeProfitPrice={} stopLossPrice={}",
                    command.userId(),
                    position.positionId(),
                    position.takeProfitPrice(),
                    position.stopLossPrice());
            return position;
        }

        TradingPosition updatedPosition = tradingPositionRepository.save(position.updateRiskControls(
                nextTakeProfitPrice,
                nextStopLossPrice,
                OffsetDateTime.now()
        ));
        // STAGE-3-PENDING-ORDER 双轨过渡：同步 UPSERT t_pending_order_trigger（SL_TP 类型）。
        // V17 阶段（阶段 3 稳定后）切换为只写挂单表 + 删除 t_position SL/TP 字段。
        if (pendingOrderApplicationService != null) {
            try {
                pendingOrderApplicationService.upsertSlTpForPosition(
                        position.positionId(),
                        nextTakeProfitPrice, nextStopLossPrice,
                        command.takeProfitPriceProvided(),
                        command.stopLossPriceProvided());
            } catch (RuntimeException exception) {
                log.warn("trading.position.risk-controls.sl-tp.upsert-failed positionId={} message={}",
                        position.positionId(), exception.getMessage());
            }
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                openPositionSnapshotStore.upsert(updatedPosition);
                tradingUserRealtimePushService.publishRiskControlsUpdated(updatedPosition);
            }
        });
        log.info("trading.position.risk-controls.updated userId={} positionId={} takeProfitPrice={} stopLossPrice={}",
                command.userId(),
                updatedPosition.positionId(),
                updatedPosition.takeProfitPrice(),
                updatedPosition.stopLossPrice());
        return updatedPosition;
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.compareTo(right) == 0;
    }
}
