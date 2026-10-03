package com.falconx.trading.service.impl;

import com.falconx.market.contract.event.MarketGroupMarkupItem;
import com.falconx.market.contract.event.MarketGroupMarkupListResponse;
import com.falconx.trading.client.MarketGroupMarkupClient;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * STAGE-12-GROUP-MARKUP TC-GM-UT-020 ~ TC-GM-UT-021：
 * DefaultTradingGroupMarkupService 通过 RPC client 拉取 → 内存快照 → find lookup。
 */
class DefaultTradingGroupMarkupServiceTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-05-21T00:00:00Z");

    @Test
    void TC_GM_UT_020_find_hit_after_refresh() {
        MarketGroupMarkupClient client = stubClient(List.of(
                new MarketGroupMarkupItem("vip", "BTCUSDT",
                        new BigDecimal("0.50"), new BigDecimal("1.00"), true, T0)
        ));
        DefaultTradingGroupMarkupService service = new DefaultTradingGroupMarkupService(client);
        service.refresh();

        Optional<MarketGroupMarkupItem> hit = service.find("vip", "BTCUSDT");

        Assertions.assertTrue(hit.isPresent());
        Assertions.assertEquals(0, hit.get().bidExtra().compareTo(new BigDecimal("0.50")));
        Assertions.assertEquals(0, hit.get().askExtra().compareTo(new BigDecimal("1.00")));
    }

    @Test
    void TC_GM_UT_020b_find_returns_empty_for_disabled_record_in_snapshot() {
        MarketGroupMarkupClient client = stubClient(List.of(
                new MarketGroupMarkupItem("vip", "BTCUSDT",
                        new BigDecimal("0.50"), BigDecimal.ZERO, false, T0)
        ));
        DefaultTradingGroupMarkupService service = new DefaultTradingGroupMarkupService(client);
        service.refresh();

        Assertions.assertTrue(service.find("vip", "BTCUSDT").isEmpty(),
                "enabled=false 配置应被 refresh 阶段过滤掉，find 返回空");
    }

    @Test
    void TC_GM_UT_020c_find_normalizes_group_code_default_and_symbol_case() {
        MarketGroupMarkupClient client = stubClient(List.of(
                new MarketGroupMarkupItem("default", "BTCUSDT",
                        new BigDecimal("0.10"), BigDecimal.ZERO, true, T0)
        ));
        DefaultTradingGroupMarkupService service = new DefaultTradingGroupMarkupService(client);
        service.refresh();

        Assertions.assertTrue(service.find(null, "btcusdt").isPresent(),
                "null groupCode 走 default，symbol 大小写归一化");
        Assertions.assertTrue(service.find("", "BTCUSDT").isPresent(),
                "空 groupCode 走 default");
    }

    @Test
    void TC_GM_UT_021_refresh_from_market_rpc_replaces_snapshot() {
        MarketGroupMarkupClient client = Mockito.mock(MarketGroupMarkupClient.class);
        Mockito.when(client.fetchAllEnabled())
                .thenReturn(new MarketGroupMarkupListResponse(List.of(
                        new MarketGroupMarkupItem("vip", "BTCUSDT",
                                new BigDecimal("0.50"), BigDecimal.ZERO, true, T0)
                ), T0))
                .thenReturn(new MarketGroupMarkupListResponse(List.of(
                        new MarketGroupMarkupItem("vip", "ETHUSDT",
                                new BigDecimal("0.20"), BigDecimal.ZERO, true, T0.plusMinutes(5))
                ), T0.plusMinutes(5)));
        Mockito.when(client.fetchChangesSince(Mockito.any()))
                .thenReturn(new MarketGroupMarkupListResponse(List.of(), OffsetDateTime.now()));

        DefaultTradingGroupMarkupService service = new DefaultTradingGroupMarkupService(client);
        service.refresh();
        Assertions.assertTrue(service.find("vip", "BTCUSDT").isPresent());

        service.refresh();
        Assertions.assertTrue(service.find("vip", "BTCUSDT").isEmpty(),
                "全量 refresh 后老配置应被新快照覆盖");
        Assertions.assertTrue(service.find("vip", "ETHUSDT").isPresent());
    }

    @Test
    void TC_GM_019_init_on_ready_degrades_instead_of_failing_fast() {
        // 2026-05-27 P0 行为变更：init 失败不再 fail-fast 拖垮服务启动（原 throw 会让
        // ApplicationReadyEvent listener 抛异常 → Spring Application run failed → 全 REST 504）。
        // 改为降级：不抛异常、snapshot 保持空，由 scheduledRefresh 后台每 30s 重试。
        MarketGroupMarkupClient client = Mockito.mock(MarketGroupMarkupClient.class);
        Mockito.when(client.fetchAllEnabled())
                .thenThrow(new IllegalStateException("market unavailable"));
        DefaultTradingGroupMarkupService service = new DefaultTradingGroupMarkupService(client);

        // 不应抛异常
        Assertions.assertDoesNotThrow(service::initOnReady,
                "init 失败应降级而非 fail-fast，避免 gateway 慢启动拖垮 trading-core");

        // 降级后按空快照运行（无加点）
        Assertions.assertTrue(service.find("vip", "BTCUSDT").isEmpty(),
                "init 失败后 snapshot 应为空，下单用原始 LP 价");
    }

    @Test
    void TC_GM_020_scheduled_refresh_retries_after_failed_init() {
        // init 失败后，scheduledRefresh 检测到 !initialized 会重试 refresh()，gateway 恢复后补齐
        MarketGroupMarkupClient client = Mockito.mock(MarketGroupMarkupClient.class);
        Mockito.when(client.fetchAllEnabled())
                .thenThrow(new IllegalStateException("market unavailable"))  // init 时失败
                .thenReturn(new MarketGroupMarkupListResponse(
                        new ArrayList<>(List.of(new MarketGroupMarkupItem("vip", "BTCUSDT",
                                new BigDecimal("0.50"), new BigDecimal("1.00"), true, T0))),
                        OffsetDateTime.now()));  // 重试成功
        DefaultTradingGroupMarkupService service = new DefaultTradingGroupMarkupService(client);

        service.initOnReady();   // 失败降级
        Assertions.assertTrue(service.find("vip", "BTCUSDT").isEmpty());

        service.scheduledRefresh();  // 后台重试补齐
        Assertions.assertTrue(service.find("vip", "BTCUSDT").isPresent(),
                "scheduledRefresh 应在 init 失败后重试并补齐快照");
    }

    private static MarketGroupMarkupClient stubClient(List<MarketGroupMarkupItem> all) {
        MarketGroupMarkupClient client = Mockito.mock(MarketGroupMarkupClient.class);
        Mockito.when(client.fetchAllEnabled())
                .thenReturn(new MarketGroupMarkupListResponse(new ArrayList<>(all), OffsetDateTime.now()));
        Mockito.when(client.fetchChangesSince(Mockito.any()))
                .thenReturn(new MarketGroupMarkupListResponse(List.of(), OffsetDateTime.now()));
        return client;
    }
}
