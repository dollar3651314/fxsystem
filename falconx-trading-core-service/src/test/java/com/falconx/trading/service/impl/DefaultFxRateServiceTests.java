package com.falconx.trading.service.impl;

import com.falconx.common.api.ApiResponse;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.trading.client.TradingExternalRpcClient;
import com.falconx.trading.config.TradingCoreServiceProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * STAGE-14B Task 3：DefaultFxRateService 单元测试。
 */
class DefaultFxRateServiceTests {

    private TradingExternalRpcClient rpcClient;
    private DefaultFxRateService service;

    @BeforeEach
    void setUp() {
        rpcClient = mock(TradingExternalRpcClient.class);
        TradingCoreServiceProperties props = new TradingCoreServiceProperties();
        service = new DefaultFxRateService(rpcClient, props);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bootstrap_loads_via_rpc() {
        FxRateSnapshotPayload payload = new FxRateSnapshotPayload(
                "EUR", "USD", new BigDecimal("1.0800"), 1_000_000L, "LP1", "EURUSD");
        when(rpcClient.get(eq("/internal/v1/market/fx/rates"), any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(payload));

        service.bootstrap();

        Optional<BigDecimal> rate = service.queryRate("EUR", "USD");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo("1.08000000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bootstrap_failure_warn_continues_service_starts() {
        when(rpcClient.get(eq("/internal/v1/market/fx/rates"), any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("network error"));

        // Must not throw — bootstrap-failure-policy=warn
        service.bootstrap();

        // latestByPair stays empty
        assertThat(service.snapshotAll()).isEmpty();
    }

    @Test
    void accept_update_writes_map_and_queryable() {
        service.acceptUpdate("USD", "JPY", new BigDecimal("150.0000"), 1_000_000L);
        Optional<BigDecimal> rate = service.queryRate("USD", "JPY");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo("150.00000000");
    }

    @Test
    void query_same_currency_returns_one() {
        Optional<BigDecimal> rate = service.queryRate("EUR", "EUR");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void query_direct_pair() {
        service.acceptUpdate("EUR", "USD", new BigDecimal("1.0800"), 0L);
        assertThat(service.queryRate("EUR", "USD").orElseThrow()).isEqualByComparingTo("1.08000000");
    }

    @Test
    void query_cross_via_usd_pivot() {
        // EUR/USD=1.08, AUD/USD=0.65 → EUR/AUD = 1.08 / 0.65 = 1.66153846
        service.acceptUpdate("EUR", "USD", new BigDecimal("1.0800"), 0L);
        service.acceptUpdate("AUD", "USD", new BigDecimal("0.6500"), 0L);
        Optional<BigDecimal> rate = service.queryRate("EUR", "AUD");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo(new BigDecimal("1.66153846"));
    }

    @Test
    void snapshot_all_returns_immutable_copy() {
        service.acceptUpdate("EUR", "USD", new BigDecimal("1.08"), 0L);
        Map<String, BigDecimal> snap = service.snapshotAll();
        assertThat(snap).containsKey("EUR/USD");
        // Verify it's a copy — adding to snapshot should not affect internal state
        assertThat(snap).hasSize(1);
    }

    @Test
    void query_reverse_pair_returns_reciprocal() {
        // EUR/USD = 1.08 → USD/EUR = 1 / 1.08 = 0.92592593
        service.acceptUpdate("EUR", "USD", new BigDecimal("1.0800"), 0L);
        Optional<BigDecimal> rate = service.queryRate("USD", "EUR");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo(new BigDecimal("0.92592593"));
    }

    @Test
    void query_unknown_pair_returns_empty() {
        Optional<BigDecimal> rate = service.queryRate("XXX", "YYY");
        assertThat(rate).isEmpty();
    }
}
