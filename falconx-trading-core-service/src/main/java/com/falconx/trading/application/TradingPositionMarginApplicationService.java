package com.falconx.trading.application;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.calculator.LiquidationPriceCalculator;
import com.falconx.trading.command.AddIsolatedMarginCommand;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.AddIsolatedMarginResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.entity.FxPauseBehavior;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.FxPauseBehaviorRepository;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingRiskControlActionRepository;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.support.TradingPricingSupport;
import com.falconx.trading.websocket.TradingUserRealtimePushService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 逐仓保证金追加应用服务。
 */
@Service
public class TradingPositionMarginApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingPositionMarginApplicationService.class);

    private final TradingPositionRepository tradingPositionRepository;
    private final TradingAccountService tradingAccountService;
    private final LiquidationPriceCalculator liquidationPriceCalculator;
    private final TradingCoreServiceProperties properties;
    private final OpenPositionSnapshotStore openPositionSnapshotStore;
    private final TradingUserRealtimePushService tradingUserRealtimePushService;
    // STAGE-14D3a Task 5：FX_PAUSED 闸门所需依赖（口径与开仓侧 DefaultTradingRiskService 一致）。
    private final TradingRiskControlActionRepository tradingRiskControlActionRepository;
    private final MarketSymbolSpecRepository marketSymbolSpecRepository;
    private final FxPauseBehaviorRepository fxPauseBehaviorRepository;

    public TradingPositionMarginApplicationService(TradingPositionRepository tradingPositionRepository,
                                                   TradingAccountService tradingAccountService,
                                                   LiquidationPriceCalculator liquidationPriceCalculator,
                                                   TradingCoreServiceProperties properties,
                                                   OpenPositionSnapshotStore openPositionSnapshotStore,
                                                   TradingUserRealtimePushService tradingUserRealtimePushService,
                                                   TradingRiskControlActionRepository tradingRiskControlActionRepository,
                                                   MarketSymbolSpecRepository marketSymbolSpecRepository,
                                                   FxPauseBehaviorRepository fxPauseBehaviorRepository) {
        this.tradingPositionRepository = tradingPositionRepository;
        this.tradingAccountService = tradingAccountService;
        this.liquidationPriceCalculator = liquidationPriceCalculator;
        this.properties = properties;
        this.openPositionSnapshotStore = openPositionSnapshotStore;
        this.tradingUserRealtimePushService = tradingUserRealtimePushService;
        this.tradingRiskControlActionRepository = tradingRiskControlActionRepository;
        this.marketSymbolSpecRepository = marketSymbolSpecRepository;
        this.fxPauseBehaviorRepository = fxPauseBehaviorRepository;
    }

    /**
     * 为 OPEN 持仓追加逐仓保证金，并重算强平价。
     */
    @Transactional
    public AddIsolatedMarginResult addIsolatedMargin(AddIsolatedMarginCommand command) {
        BigDecimal amount = TradingPricingSupport.scaleAmount(command.amount());
        if (amount == null || amount.signum() <= 0) {
            // STAGE-14D1 Task 5：对齐 master §7.4 错误码 30086 SUPPLEMENT_AMOUNT_INVALID
            // （原 IllegalArgumentException 走 500/无业务码，统一为业务异常便于客户端识别）。
            throw new TradingBusinessException(
                    TradingErrorCode.SUPPLEMENT_AMOUNT_INVALID,
                    Map.of(
                            "userId", command.userId(),
                            "positionId", command.positionId(),
                            "rejectionReason", TradingErrorCode.SUPPLEMENT_AMOUNT_INVALID.name()
                    )
            );
        }
        log.info("trading.position.margin.request userId={} positionId={} amount={}",
                command.userId(),
                command.positionId(),
                amount);

        TradingPosition position = tradingPositionRepository.findByIdAndUserIdForUpdate(command.positionId(), command.userId())
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.POSITION_NOT_FOUND,
                        Map.of(
                                "userId", command.userId(),
                                "positionId", command.positionId(),
                                "rejectionReason", TradingErrorCode.POSITION_NOT_FOUND.name()
                        )
                ));
        if (position.isTerminal()) {
            throw new TradingBusinessException(
                    TradingErrorCode.POSITION_ALREADY_CLOSED,
                    Map.of(
                            "userId", command.userId(),
                            "positionId", command.positionId(),
                            "symbol", position.symbol(),
                            "rejectionReason", TradingErrorCode.POSITION_ALREADY_CLOSED.name()
                    )
            );
        }
        // STAGE-14D1 Task 5：仅 ISOLATED 仓可追加保证金（master 语义）。
        // 当前账户全 ISOLATED，CROSS 仓自 D2 起出现；CROSS 仓由账户级保证金统一承担，
        // 追加逐仓保证金无意义 → 30085 POSITION_NOT_ISOLATED（按仓位级 marginMode 精确判定）。
        if (position.marginMode() != TradingMarginMode.ISOLATED) {
            throw new TradingBusinessException(
                    TradingErrorCode.POSITION_NOT_ISOLATED,
                    Map.of(
                            "userId", command.userId(),
                            "positionId", command.positionId(),
                            "symbol", position.symbol(),
                            "rejectionReason", TradingErrorCode.POSITION_NOT_ISOLATED.name()
                    )
            );
        }

        // STAGE-14D3a Task 5：FX_PAUSED 闸门——supplement 视同「开仓侧加保证金」，复用开仓侧
        // allow_open 口径（DefaultTradingRiskService#evaluatePauseOpenRejection）：GLOBAL_PAUSE 活跃时
        // 按品种类目（master §6.5）查 t_fx_pause_behavior 决定是否放行；category==null（过渡期旧快照）
        // 或 behavior 缺失则保守全拒 30087（pause 期缺信息从严不从宽，与开仓侧降级方向一致）。
        // 仅对 ISOLATED 仓生效（在 30085 之后、余额 40001 之前；supplement 本就只对 ISOLATED）。
        if (tradingRiskControlActionRepository.hasActiveGlobalPause()) {
            Integer category = marketSymbolSpecRepository.findByPlatformSymbol(position.symbol())
                    .map(SymbolSpec::category)
                    .orElse(null);
            boolean allowOpen = category != null
                    && fxPauseBehaviorRepository.findByCategory(category)
                            .map(FxPauseBehavior::allowOpen)
                            .orElse(false);
            if (!allowOpen) {
                log.warn("trading.supplement.fx-pause.rejected userId={} positionId={} symbol={} category={} reason=GLOBAL_PAUSE_ACTIVE",
                        command.userId(), command.positionId(), position.symbol(), category);
                throw new TradingBusinessException(
                        TradingErrorCode.GLOBAL_PAUSE_ACTIVE,
                        Map.of(
                                "userId", command.userId(),
                                "positionId", command.positionId(),
                                "symbol", position.symbol(),
                                "rejectionReason", TradingErrorCode.GLOBAL_PAUSE_ACTIVE.name()
                        )
                );
            }
        }

        TradingAccount account = tradingAccountService.getExistingAccountForUpdate(command.userId(), properties.getSettlementToken());
        if (account.available().compareTo(amount) < 0) {
            throw new TradingBusinessException(
                    TradingErrorCode.INSUFFICIENT_MARGIN,
                    Map.of(
                            "userId", command.userId(),
                            "positionId", command.positionId(),
                            "symbol", position.symbol(),
                            "rejectionReason", TradingErrorCode.INSUFFICIENT_MARGIN.name()
                    )
            );
        }

        OffsetDateTime occurredAt = OffsetDateTime.now();
        BigDecimal nextMargin = position.margin().add(amount);
        BigDecimal nextLiquidationPrice = liquidationPriceCalculator.calculate(
                position.side(),
                position.entryPrice(),
                position.quantity(),
                nextMargin,
                properties.getMaintenanceMarginRate()
        );
        TradingAccount updatedAccount = tradingAccountService.supplementIsolatedMargin(
                account,
                amount,
                "ims:" + position.positionId() + ":" + UUID.randomUUID(),
                "isolated-margin-supplement:" + position.positionId(),
                occurredAt
        );
        TradingPosition updatedPosition = tradingPositionRepository.save(position.supplementMargin(
                amount,
                nextLiquidationPrice,
                occurredAt
        ));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                openPositionSnapshotStore.upsert(updatedPosition);
                tradingUserRealtimePushService.publishMarginUpdated(updatedPosition, updatedAccount);
            }
        });

        log.info("trading.position.margin.supplemented userId={} positionId={} amount={} margin={} liquidationPrice={}",
                command.userId(),
                updatedPosition.positionId(),
                amount,
                updatedPosition.margin(),
                updatedPosition.liquidationPrice());
        return new AddIsolatedMarginResult(updatedPosition, updatedAccount);
    }
}
