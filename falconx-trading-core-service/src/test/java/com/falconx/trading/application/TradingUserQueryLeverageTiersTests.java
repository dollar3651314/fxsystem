package com.falconx.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.TradingLeverageTierListResponse;
import com.falconx.trading.entity.SymbolLeverageTier;
import com.falconx.trading.repository.SymbolLeverageTierRepository;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.websocket.TradingRealtimeDualPnlSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * B 切片（2026-06-03）：{@link TradingUserQueryApplicationService#getLeverageTiers} 单测。
 *
 * <p>覆盖：档位透传（enabled 过滤 + tierNo 升序保持）；effectiveGroup 取仓储回退后实际组；
 * fxRate 三态（同币种短路 1 / 跨币种查 FxRateService / QC 缺失或 FX 不可用 null）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingUserQueryLeverageTiersTests {

    @Mock
    private SymbolLeverageTierRepository tierRepository;

    @Mock
    private FxRateService fxRateService;

    @Mock
    private TradingRealtimeDualPnlSupport dualPnlSupport;

    @Mock
    private TradingCoreServiceProperties properties;

    private TradingUserQueryApplicationService service() {
        Mockito.when(properties.getSettlementToken()).thenReturn("USDT");
        return new TradingUserQueryApplicationService(
                null, null, null, null, null, null, null,
                dualPnlSupport, tierRepository, fxRateService, properties);
    }

    private static SymbolLeverageTier tier(String group, int no, String lower, String upper, int lev, String mm, boolean enabled) {
        return new SymbolLeverageTier(30000000L + no, "AUDCAD", group, no,
                new BigDecimal(lower), upper == null ? null : new BigDecimal(upper),
                lev, new BigDecimal(mm), enabled);
    }

    @Test
    void 透传档位列表并过滤disabled_跨币种附实时fxRate() {
        Mockito.when(tierRepository.findTiers("AUDCAD", "default")).thenReturn(List.of(
                tier("default", 1, "0", "50000", 300, "0.001650", true),
                tier("default", 2, "50000", "250000", 100, "0.005000", true),
                tier("default", 3, "250000", null, 50, "0.010000", false)));  // disabled → 过滤
        Mockito.when(dualPnlSupport.resolveQuoteCurrency("AUDCAD")).thenReturn("CAD");
        Mockito.when(fxRateService.queryRate("CAD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.72250000")));

        TradingLeverageTierListResponse r = service().getLeverageTiers("AUDCAD", "default");

        assertThat(r.symbol()).isEqualTo("AUDCAD");
        assertThat(r.groupCode()).isEqualTo("default");
        assertThat(r.quoteCurrency()).isEqualTo("CAD");
        assertThat(r.fxRate()).isEqualByComparingTo("0.7225");
        assertThat(r.tiers()).hasSize(2);
        assertThat(r.tiers().get(0).tierNo()).isEqualTo(1);
        assertThat(r.tiers().get(0).maxLeverage()).isEqualTo(300);
        assertThat(r.tiers().get(1).notionalUpper()).isEqualByComparingTo("250000");
    }

    @Test
    void 请求组回退default时_effectiveGroup取实际命中组() {
        // 仓储层已实现回退：vip 无配置 → 返回 default 组的行（groupCode 字段为 default）
        Mockito.when(tierRepository.findTiers("AUDCAD", "vip")).thenReturn(List.of(
                tier("default", 1, "0", "50000", 300, "0.001650", true)));
        Mockito.when(dualPnlSupport.resolveQuoteCurrency("AUDCAD")).thenReturn("CAD");
        Mockito.when(fxRateService.queryRate("CAD", "USDT")).thenReturn(Optional.empty());

        TradingLeverageTierListResponse r = service().getLeverageTiers("AUDCAD", "vip");

        assertThat(r.groupCode()).isEqualTo("default");
        assertThat(r.fxRate()).isNull();  // FX 不可用 → null，客户端降级
    }

    @Test
    void 同币种短路fx等于1_QC缺失fx为null() {
        Mockito.when(tierRepository.findTiers("AUDCAD", "default")).thenReturn(List.of(
                tier("default", 1, "0", null, 300, "0.001650", true)));
        Mockito.when(dualPnlSupport.resolveQuoteCurrency("AUDCAD")).thenReturn("USDT");

        assertThat(service().getLeverageTiers("AUDCAD", "default").fxRate()).isEqualByComparingTo("1");

        Mockito.when(dualPnlSupport.resolveQuoteCurrency("AUDCAD")).thenReturn(null);
        TradingLeverageTierListResponse r = service().getLeverageTiers("AUDCAD", "default");
        assertThat(r.fxRate()).isNull();
        assertThat(r.quoteCurrency()).isNull();
    }
}
