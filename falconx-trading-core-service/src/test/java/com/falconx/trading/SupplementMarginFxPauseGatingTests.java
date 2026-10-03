package com.falconx.trading;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.application.TradingPositionMarginApplicationService;
import com.falconx.trading.calculator.LiquidationPriceCalculator;
import com.falconx.trading.command.AddIsolatedMarginCommand;
import com.falconx.trading.config.TradingCoreServiceProperties;
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
import com.falconx.trading.websocket.TradingUserRealtimePushService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * STAGE-14D3a Task 5：supplement-margin 接 FX_PAUSED 闸门单元测试。
 *
 * <p>supplement 视同「开仓侧加保证金」，复用开仓侧 allow_open 口径（DefaultTradingRiskService
 * #evaluatePauseOpenRejection）：GLOBAL_PAUSE 活跃时按品种类目查 t_fx_pause_behavior，
 * allow_open=false→拒 30087；category/behavior 缺失保守全拒 30087。
 *
 * <p>gating 插入点在 30085（POSITION_NOT_ISOLATED）校验之后、40001（余额）校验之前。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplementMarginFxPauseGatingTests {

    private static final long USER_ID = 1001L;
    private static final long POSITION_ID = 5001L;
    private static final String FOREX_SYMBOL = "EURUSD";
    private static final String CRYPTO_SYMBOL = "BTCUSDT";
    private static final int CATEGORY_FOREX = 2;
    private static final int CATEGORY_CRYPTO = 1;

    @Mock
    private TradingPositionRepository tradingPositionRepository;

    @Mock
    private TradingAccountService tradingAccountService;

    @Mock
    private LiquidationPriceCalculator liquidationPriceCalculator;

    @Mock
    private OpenPositionSnapshotStore openPositionSnapshotStore;

    @Mock
    private TradingUserRealtimePushService tradingUserRealtimePushService;

    @Mock
    private TradingRiskControlActionRepository tradingRiskControlActionRepository;

    @Mock
    private MarketSymbolSpecRepository marketSymbolSpecRepository;

    @Mock
    private FxPauseBehaviorRepository fxPauseBehaviorRepository;

    private TradingPositionMarginApplicationService service() {
        return new TradingPositionMarginApplicationService(
                tradingPositionRepository,
                tradingAccountService,
                liquidationPriceCalculator,
                new TradingCoreServiceProperties(),
                openPositionSnapshotStore,
                tradingUserRealtimePushService,
                tradingRiskControlActionRepository,
                marketSymbolSpecRepository,
                fxPauseBehaviorRepository);
    }

    private TradingPosition isolatedPosition(String symbol) {
        TradingPosition position = Mockito.mock(TradingPosition.class);
        Mockito.when(position.isTerminal()).thenReturn(false);
        Mockito.when(position.marginMode()).thenReturn(TradingMarginMode.ISOLATED);
        Mockito.when(position.symbol()).thenReturn(symbol);
        Mockito.when(tradingPositionRepository.findByIdAndUserIdForUpdate(POSITION_ID, USER_ID))
                .thenReturn(Optional.of(position));
        return position;
    }

    private SymbolSpec specWithCategory(String symbol, Integer category) {
        return new SymbolSpec(symbol, 100, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 2, 2, "EUR", "USD", category);
    }

    private FxPauseBehavior behavior(int category, boolean allowOpen) {
        return new FxPauseBehavior(category, "cat-" + category, allowOpen, true, false);
    }

    /** 触发余额不足（40001），证明 gating 已放行、流程进入余额校验。 */
    private void stubInsufficientBalanceAfterGate() {
        TradingAccount account = Mockito.mock(TradingAccount.class);
        Mockito.when(account.available()).thenReturn(BigDecimal.ZERO);
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(Mockito.eq(USER_ID), Mockito.any()))
                .thenReturn(account);
    }

    @Test
    void supplement_pause活跃_forex仓_allowOpenFalse_拒30087() {
        isolatedPosition(FOREX_SYMBOL);
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(true);
        Mockito.when(marketSymbolSpecRepository.findByPlatformSymbol(FOREX_SYMBOL))
                .thenReturn(Optional.of(specWithCategory(FOREX_SYMBOL, CATEGORY_FOREX)));
        Mockito.when(fxPauseBehaviorRepository.findByCategory(CATEGORY_FOREX))
                .thenReturn(Optional.of(behavior(CATEGORY_FOREX, false)));

        assertThatThrownBy(() -> service().addIsolatedMargin(
                new AddIsolatedMarginCommand(USER_ID, POSITION_ID, new BigDecimal("100"))))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.GLOBAL_PAUSE_ACTIVE);
    }

    @Test
    void supplement_pause活跃_crypto仓_allowOpenTrue_放行进入余额校验() {
        isolatedPosition(CRYPTO_SYMBOL);
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(true);
        Mockito.when(marketSymbolSpecRepository.findByPlatformSymbol(CRYPTO_SYMBOL))
                .thenReturn(Optional.of(specWithCategory(CRYPTO_SYMBOL, CATEGORY_CRYPTO)));
        Mockito.when(fxPauseBehaviorRepository.findByCategory(CATEGORY_CRYPTO))
                .thenReturn(Optional.of(behavior(CATEGORY_CRYPTO, true)));
        stubInsufficientBalanceAfterGate();

        // allow_open=true → gating 放行，继续走余额校验（此处余额为 0 → 40001），证明未被 30087 拦截。
        assertThatThrownBy(() -> service().addIsolatedMargin(
                new AddIsolatedMarginCommand(USER_ID, POSITION_ID, new BigDecimal("100"))))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.INSUFFICIENT_MARGIN);
    }

    @Test
    void supplement_pause活跃_categoryNull_保守全拒30087() {
        isolatedPosition(FOREX_SYMBOL);
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(true);
        // spec 缺失（过渡期旧快照）→ category 取不到 → 保守全拒。
        Mockito.when(marketSymbolSpecRepository.findByPlatformSymbol(FOREX_SYMBOL))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().addIsolatedMargin(
                new AddIsolatedMarginCommand(USER_ID, POSITION_ID, new BigDecimal("100"))))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.GLOBAL_PAUSE_ACTIVE);
    }

    @Test
    void supplement_无pause_不触发gating_放行进入余额校验() {
        isolatedPosition(FOREX_SYMBOL);
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        stubInsufficientBalanceAfterGate();

        // 无 GLOBAL_PAUSE → gating 不触发，回归原流程（此处余额 0 → 40001）。
        assertThatThrownBy(() -> service().addIsolatedMargin(
                new AddIsolatedMarginCommand(USER_ID, POSITION_ID, new BigDecimal("100"))))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.INSUFFICIENT_MARGIN);

        Mockito.verifyNoInteractions(marketSymbolSpecRepository);
        Mockito.verifyNoInteractions(fxPauseBehaviorRepository);
    }

    @Test
    void supplement_pause活跃_behaviorMissing_保守全拒30087() {
        isolatedPosition(FOREX_SYMBOL);
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(true);
        Mockito.when(marketSymbolSpecRepository.findByPlatformSymbol(FOREX_SYMBOL))
                .thenReturn(Optional.of(specWithCategory(FOREX_SYMBOL, CATEGORY_FOREX)));
        // behavior 缺失（findByCategory empty）→ 保守全拒。
        Mockito.when(fxPauseBehaviorRepository.findByCategory(CATEGORY_FOREX))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().addIsolatedMargin(
                new AddIsolatedMarginCommand(USER_ID, POSITION_ID, new BigDecimal("100"))))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.GLOBAL_PAUSE_ACTIVE);
    }

    @Test
    void supplement_非ISOLATED仓_先按30085拒_gating不触发() {
        TradingPosition crossPosition = Mockito.mock(TradingPosition.class);
        Mockito.when(crossPosition.isTerminal()).thenReturn(false);
        Mockito.when(crossPosition.marginMode()).thenReturn(TradingMarginMode.CROSS);
        Mockito.when(crossPosition.symbol()).thenReturn(FOREX_SYMBOL);
        Mockito.when(tradingPositionRepository.findByIdAndUserIdForUpdate(POSITION_ID, USER_ID))
                .thenReturn(Optional.of(crossPosition));
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(true);

        // 非 ISOLATED → 先 30085，pause 闸门（在 30085 之后）不触发。
        assertThatThrownBy(() -> service().addIsolatedMargin(
                new AddIsolatedMarginCommand(USER_ID, POSITION_ID, new BigDecimal("100"))))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.POSITION_NOT_ISOLATED);

        Mockito.verifyNoInteractions(tradingRiskControlActionRepository);
        assertThatCode(() -> {
        }).doesNotThrowAnyException();
    }
}
