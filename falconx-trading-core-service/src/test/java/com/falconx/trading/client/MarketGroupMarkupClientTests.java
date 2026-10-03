package com.falconx.trading.client;

import com.falconx.market.contract.event.MarketGroupMarkupItem;
import com.falconx.market.contract.event.MarketGroupMarkupListResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.ParameterizedTypeReference;

/**
 * STAGE-12-GROUP-MARKUP TC-GM-013 (UT 替代版): MarketGroupMarkupClient HTTP path 构造 + 响应解析。
 *
 * <p>真 HTTP RPC 端到端 IT 需要同时启动 market-service，工程量大；
 * 本测试用 Mockito 替换底层 TradingExternalRpcClient，专注验证：
 * <ul>
 *   <li>fetchAllEnabled 的 path 是 /internal/v1/market/symbols/group-markup/all-enabled</li>
 *   <li>fetchChangesSince 的 path 含 ?since=epochMillis 查询参数</li>
 *   <li>response 为 null 时返回空 list 不抛异常（兼容上游 503）</li>
 *   <li>response 含 items + serverTime 时正常透传</li>
 * </ul>
 */
class MarketGroupMarkupClientTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-05-21T00:00:00Z");

    @Test
    void TC_GM_013_fetchAllEnabled_calls_correct_path_and_returns_items() {
        TradingExternalRpcClient rpcClient = Mockito.mock(TradingExternalRpcClient.class);
        MarketGroupMarkupListResponse mockResponse = new MarketGroupMarkupListResponse(List.of(
                new MarketGroupMarkupItem("vip", "BTCUSDT",
                        new BigDecimal("0.5"), new BigDecimal("1.0"), true, T0)
        ), T0);
        Mockito.when(rpcClient.<MarketGroupMarkupListResponse>get(
                Mockito.eq("/internal/v1/market/symbols/group-markup/all-enabled"),
                Mockito.any(ParameterizedTypeReference.class)
        )).thenReturn(mockResponse);

        MarketGroupMarkupClient client = new MarketGroupMarkupClient(rpcClient);
        MarketGroupMarkupListResponse result = client.fetchAllEnabled();

        Assertions.assertEquals(1, result.items().size());
        Assertions.assertEquals("vip", result.items().get(0).groupCode());
        Assertions.assertEquals("BTCUSDT", result.items().get(0).platformSymbol());
        Mockito.verify(rpcClient).get(Mockito.anyString(), Mockito.any(ParameterizedTypeReference.class));
    }

    @Test
    void TC_GM_013b_fetchAllEnabled_null_response_returns_empty_safely() {
        TradingExternalRpcClient rpcClient = Mockito.mock(TradingExternalRpcClient.class);
        Mockito.when(rpcClient.<MarketGroupMarkupListResponse>get(
                Mockito.anyString(),
                Mockito.any(ParameterizedTypeReference.class)
        )).thenReturn(null);

        MarketGroupMarkupClient client = new MarketGroupMarkupClient(rpcClient);
        MarketGroupMarkupListResponse result = client.fetchAllEnabled();

        Assertions.assertNotNull(result, "null response 应被包成空列表，避免上游 503 时 NPE");
        Assertions.assertTrue(result.items().isEmpty());
        Assertions.assertNotNull(result.serverTime());
    }

    @Test
    void TC_GM_013c_fetchChangesSince_appends_since_epoch_millis_query_param() {
        TradingExternalRpcClient rpcClient = Mockito.mock(TradingExternalRpcClient.class);
        Mockito.when(rpcClient.<MarketGroupMarkupListResponse>get(
                Mockito.anyString(),
                Mockito.any(ParameterizedTypeReference.class)
        )).thenReturn(new MarketGroupMarkupListResponse(List.of(), T0));

        MarketGroupMarkupClient client = new MarketGroupMarkupClient(rpcClient);
        client.fetchChangesSince(T0);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(rpcClient).get(pathCaptor.capture(), Mockito.any(ParameterizedTypeReference.class));
        String path = pathCaptor.getValue();
        long expectedMillis = T0.toInstant().toEpochMilli();
        Assertions.assertTrue(path.startsWith("/internal/v1/market/symbols/group-markup/changes?since="),
                "path 应以 /changes?since= 开头，实际：" + path);
        Assertions.assertTrue(path.endsWith("since=" + expectedMillis),
                "since 应为 epoch millis " + expectedMillis + "，实际 path: " + path);
    }

    @Test
    void TC_GM_013d_fetchChangesSince_null_uses_zero() {
        TradingExternalRpcClient rpcClient = Mockito.mock(TradingExternalRpcClient.class);
        Mockito.when(rpcClient.<MarketGroupMarkupListResponse>get(
                Mockito.anyString(),
                Mockito.any(ParameterizedTypeReference.class)
        )).thenReturn(new MarketGroupMarkupListResponse(List.of(), T0));

        MarketGroupMarkupClient client = new MarketGroupMarkupClient(rpcClient);
        client.fetchChangesSince(null);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(rpcClient).get(pathCaptor.capture(), Mockito.any(ParameterizedTypeReference.class));
        Assertions.assertTrue(pathCaptor.getValue().endsWith("since=0"),
                "null since 应回退 0，实际 path: " + pathCaptor.getValue());
    }
}
