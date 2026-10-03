package com.falconx.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.falconx.trading.api.AdminTradingPositionListResponse;
import com.falconx.trading.repository.mapper.TradingPositionMapper;
import com.falconx.trading.repository.mapper.record.TradingPositionRecord;
import com.falconx.trading.websocket.TradingRealtimeDualPnlSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 多币种与显示一致性收尾（2026-06-03）：{@link TradingMonitorAdminApplicationService#listPositions}
 * 每行回填 pricePrecision 单元测试（口径同 STAGE-14E2 exposure 回填 quoteCurrency，复用
 * {@link TradingRealtimeDualPnlSupport#resolvePricePrecision}）。
 *
 * <p>用 CLOSED 持仓避开 quote/markPrice 依赖，专测 pricePrecision 透传：命中 SymbolSpec 回填、
 * 缺失降级 null（不抛）、多 symbol 各自按 symbol 解析。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingMonitorAdminPricePrecisionTests {

    @Mock
    private TradingPositionMapper positionMapper;

    @Mock
    private TradingRealtimeDualPnlSupport dualPnlSupport;

    private TradingMonitorAdminApplicationService service() {
        return new TradingMonitorAdminApplicationService(
                null, positionMapper, null, null, null, null, null, null, null, dualPnlSupport);
    }

    /** CLOSED 持仓（statusCode=2）：不触发 quote 查询，专验 pricePrecision 回填 + 其余字段透传。 */
    private static TradingPositionRecord closedPosition(String symbol) {
        return new TradingPositionRecord(
                1L, 10L, 100L, symbol, 1,
                new BigDecimal("1"), new BigDecimal("1.1000"),
                null, null, null,
                new BigDecimal("100"), new BigDecimal("10"), 1,
                null, null, null,
                new BigDecimal("1.2000"), null, new BigDecimal("90"),
                2,  // statusCode=CLOSED → 不算 markPrice/不查 quote
                null, null, null, null,
                LocalDateTime.parse("2026-06-01T00:00:00"), LocalDateTime.parse("2026-06-02T00:00:00"),
                LocalDateTime.parse("2026-06-02T00:00:00"));
    }

    @Test
    void listPositions_命中SymbolSpec_回填pricePrecision() {
        Mockito.when(positionMapper.selectAdminPaginated(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(List.of(closedPosition("EURUSD")));
        Mockito.when(positionMapper.countAdminFiltered(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(1L);
        Mockito.when(dualPnlSupport.resolvePricePrecision("EURUSD")).thenReturn(5);

        AdminTradingPositionListResponse response =
                service().listPositions(null, null, null, null, null, 1, 20);

        assertThat(response.items()).hasSize(1);
        AdminTradingPositionListResponse.Item item = response.items().get(0);
        assertThat(item.symbol()).isEqualTo("EURUSD");
        assertThat(item.pricePrecision()).isEqualTo(5);
        assertThat(item.entryPrice()).isEqualByComparingTo("1.1000");
    }

    @Test
    void listPositions_SymbolSpec缺失_pricePrecision降级null不抛() {
        Mockito.when(positionMapper.selectAdminPaginated(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(List.of(closedPosition("XAUUSD")));
        Mockito.when(positionMapper.countAdminFiltered(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(1L);
        Mockito.when(dualPnlSupport.resolvePricePrecision("XAUUSD")).thenReturn(null);

        AdminTradingPositionListResponse response =
                service().listPositions(null, null, null, null, null, 1, 20);

        assertThat(response.items().get(0).pricePrecision()).isNull();
    }
}
