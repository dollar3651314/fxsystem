package com.falconx.trading.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.falconx.trading.calculator.LiquidationPriceCalculator;
import com.falconx.trading.command.AddIsolatedMarginCommand;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
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
 * STAGE-14D1 Task 5：{@link TradingPositionMarginApplicationService} 错误码对齐单元测试。
 *
 * <p>覆盖 supplement-margin 收口的错误码对齐（master §7.4）：
 * <ul>
 *   <li>amount≤0 → 30086 SUPPLEMENT_AMOUNT_INVALID（替换原 IllegalArgumentException）</li>
 *   <li>持仓非 ISOLATED（CROSS 仓）→ 30085 POSITION_NOT_ISOLATED</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingPositionMarginApplicationServiceTests {

    private static final long USER_ID = 1001L;
    private static final long POSITION_ID = 5001L;
    private static final String SETTLEMENT = "USDT";

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

    private TradingCoreServiceProperties properties() {
        return new TradingCoreServiceProperties();
    }

    private TradingPositionMarginApplicationService service() {
        return new TradingPositionMarginApplicationService(
                tradingPositionRepository,
                tradingAccountService,
                liquidationPriceCalculator,
                properties(),
                openPositionSnapshotStore,
                tradingUserRealtimePushService,
                tradingRiskControlActionRepository,
                marketSymbolSpecRepository,
                fxPauseBehaviorRepository);
    }

    @Test
    void addIsolatedMargin_金额非正_拒30086() {
        assertThatThrownBy(() -> service().addIsolatedMargin(
                new AddIsolatedMarginCommand(USER_ID, POSITION_ID, BigDecimal.ZERO)))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.SUPPLEMENT_AMOUNT_INVALID);
    }

    @Test
    void addIsolatedMargin_CROSS仓_拒30085() {
        TradingPosition crossPosition = Mockito.mock(TradingPosition.class);
        Mockito.when(crossPosition.isTerminal()).thenReturn(false);
        Mockito.when(crossPosition.marginMode()).thenReturn(TradingMarginMode.CROSS);
        Mockito.when(crossPosition.symbol()).thenReturn("BTCUSDT");
        Mockito.when(tradingPositionRepository.findByIdAndUserIdForUpdate(POSITION_ID, USER_ID))
                .thenReturn(Optional.of(crossPosition));

        assertThatThrownBy(() -> service().addIsolatedMargin(
                new AddIsolatedMarginCommand(USER_ID, POSITION_ID, new BigDecimal("100"))))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.POSITION_NOT_ISOLATED);
    }
}
