package com.falconx.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.falconx.trading.api.AdminTradingExposureListResponse;
import com.falconx.trading.entity.TradingRiskExposure;
import com.falconx.trading.repository.TradingRiskExposureRepository;
import com.falconx.trading.websocket.TradingRealtimeDualPnlSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * STAGE-14E2 Task 2：{@link TradingMonitorAdminApplicationService#listExposures} 补 quoteCurrency 单元测试。
 *
 * <p>覆盖：每 symbol 通过 {@link TradingRealtimeDualPnlSupport#resolveQuoteCurrency} 复用 Task1 同源取法
 * 回填 quoteCurrency（命中 SymbolSpec）；过渡期 SymbolSpec 缺失时降级 null（不抛）；其余字段保持透传。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingMonitorAdminExposureQuoteCurrencyTests {

    @Mock
    private TradingRiskExposureRepository exposureRepository;

    @Mock
    private TradingRealtimeDualPnlSupport dualPnlSupport;

    private TradingMonitorAdminApplicationService service() {
        return new TradingMonitorAdminApplicationService(
                null, null, null, null,
                exposureRepository,
                null, null, null, null,
                dualPnlSupport);
    }

    private static TradingRiskExposure exposure(String symbol) {
        return new TradingRiskExposure(
                symbol,
                new BigDecimal("100"),
                new BigDecimal("40"),
                new BigDecimal("60"),
                new BigDecimal("66000"),
                OffsetDateTime.parse("2026-06-02T00:00:00Z"));
    }

    @Test
    void listExposures_命中SymbolSpec_回填quoteCurrency并保持其余字段透传() {
        Mockito.when(exposureRepository.selectAdminAll(null))
                .thenReturn(List.of(exposure("EURUSD")));
        Mockito.when(dualPnlSupport.resolveQuoteCurrency("EURUSD")).thenReturn("USD");

        AdminTradingExposureListResponse response = service().listExposures(null);

        assertThat(response.items()).hasSize(1);
        AdminTradingExposureListResponse.Item item = response.items().get(0);
        assertThat(item.symbol()).isEqualTo("EURUSD");
        assertThat(item.quoteCurrency()).isEqualTo("USD");
        assertThat(item.totalLongQty()).isEqualByComparingTo("100");
        assertThat(item.totalShortQty()).isEqualByComparingTo("40");
        assertThat(item.netExposure()).isEqualByComparingTo("60");
        assertThat(item.netExposureUsd()).isEqualByComparingTo("66000");
        assertThat(item.updatedAt()).isEqualTo(OffsetDateTime.parse("2026-06-02T00:00:00Z"));
    }

    @Test
    void listExposures_SymbolSpec缺失_quoteCurrency降级null不抛() {
        Mockito.when(exposureRepository.selectAdminAll(null))
                .thenReturn(List.of(exposure("XAUUSD")));
        Mockito.when(dualPnlSupport.resolveQuoteCurrency("XAUUSD")).thenReturn(null);

        AdminTradingExposureListResponse response = service().listExposures(null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).symbol()).isEqualTo("XAUUSD");
        assertThat(response.items().get(0).quoteCurrency()).isNull();
    }

    @Test
    void listExposures_多symbol_各自按symbol解析quoteCurrency() {
        Mockito.when(exposureRepository.selectAdminAll(null))
                .thenReturn(List.of(exposure("EURUSD"), exposure("USDJPY")));
        Mockito.when(dualPnlSupport.resolveQuoteCurrency("EURUSD")).thenReturn("USD");
        Mockito.when(dualPnlSupport.resolveQuoteCurrency("USDJPY")).thenReturn("JPY");

        AdminTradingExposureListResponse response = service().listExposures(null);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).quoteCurrency()).isEqualTo("USD");
        assertThat(response.items().get(1).quoteCurrency()).isEqualTo("JPY");
    }
}
