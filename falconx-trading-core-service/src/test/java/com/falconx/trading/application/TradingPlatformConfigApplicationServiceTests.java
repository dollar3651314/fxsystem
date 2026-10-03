package com.falconx.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.falconx.trading.application.TradingPlatformConfigApplicationService.PlatformConfigView;
import com.falconx.trading.repository.FxPauseBehaviorRepository;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.service.model.MarginThresholds;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * STAGE-14D3a Task 4：{@link TradingPlatformConfigApplicationService} 单元测试（mock repository）。
 *
 * <p>覆盖：updateCoolingPeriod / updateRiskThresholds 各委托 repository 一次；
 * getPlatformConfig 在平台行缺失（repo empty）时回退默认 300 / 0.30 / 1.00。
 */
@ExtendWith(MockitoExtension.class)
class TradingPlatformConfigApplicationServiceTests {

    @Mock
    private TradingRiskConfigRepository riskConfigRepository;

    @Mock
    private FxPauseBehaviorRepository fxPauseBehaviorRepository;

    private TradingPlatformConfigApplicationService service() {
        return new TradingPlatformConfigApplicationService(riskConfigRepository, fxPauseBehaviorRepository);
    }

    @Test
    void updateCoolingPeriod_委托repository一次() {
        service().updateCoolingPeriod(600);

        Mockito.verify(riskConfigRepository, Mockito.times(1))
                .updatePlatformCoolingPeriodSeconds(600);
        Mockito.verifyNoMoreInteractions(riskConfigRepository);
    }

    @Test
    void updateRiskThresholds_委托repository一次() {
        BigDecimal stopOut = new BigDecimal("0.25");
        BigDecimal marginCall = new BigDecimal("1.20");

        service().updateRiskThresholds(stopOut, marginCall);

        Mockito.verify(riskConfigRepository, Mockito.times(1))
                .updatePlatformMarginThresholds(stopOut, marginCall);
        Mockito.verifyNoMoreInteractions(riskConfigRepository);
    }

    @Test
    void getPlatformConfig_平台行缺失时回退默认300_030_100() {
        Mockito.when(riskConfigRepository.findPlatformCoolingPeriodSeconds())
                .thenReturn(Optional.empty());
        Mockito.when(riskConfigRepository.findPlatformMarginThresholds())
                .thenReturn(Optional.empty());

        PlatformConfigView view = service().getPlatformConfig();

        assertThat(view.coolingPeriodSeconds()).isEqualTo(300);
        assertThat(view.stopOutLevel()).isEqualByComparingTo("0.30");
        assertThat(view.marginCallLevel()).isEqualByComparingTo("1.00");
    }

    @Test
    void getPlatformConfig_平台行存在时返回DB值() {
        Mockito.when(riskConfigRepository.findPlatformCoolingPeriodSeconds())
                .thenReturn(Optional.of(600));
        Mockito.when(riskConfigRepository.findPlatformMarginThresholds())
                .thenReturn(Optional.of(new MarginThresholds(
                        new BigDecimal("0.25"), new BigDecimal("1.20"))));

        PlatformConfigView view = service().getPlatformConfig();

        assertThat(view.coolingPeriodSeconds()).isEqualTo(600);
        assertThat(view.stopOutLevel()).isEqualByComparingTo("0.25");
        assertThat(view.marginCallLevel()).isEqualByComparingTo("1.20");
    }
}
