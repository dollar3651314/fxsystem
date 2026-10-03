package com.falconx.console.trading;

import com.falconx.console.api.AdminTradingExposureListResponse;
import com.falconx.console.config.ConsoleServiceProperties;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.security.AdminPrincipal;
import com.falconx.console.security.AdminSecurityContextHolder;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * STAGE-14E2 Task 2：console exposure 透传 quoteCurrency 集成测试。
 *
 * <p>console 不写业务表，exposure 走既有 trading internal RPC（{@code /internal/v1/trading/console/exposures}）透传。
 * 本测试用 in-process HttpServer 模拟 trading-core 返回含 quoteCurrency 的 exposure JSON，
 * 验证 {@link AdminTradingMonitorApplicationService#listExposures} 反序列化后保留 quoteCurrency（命中 + null 降级）。
 */
class AdminTradingExposureQuoteCurrencyPassThroughTests {

    @AfterEach
    void tearDown() {
        AdminSecurityContextHolder.clear();
    }

    @Test
    void listExposures_透传保留quoteCurrency_含null降级() throws Exception {
        String exposureJson = """
                {"code":"0","message":"success","data":{"items":[
                  {"symbol":"EURUSD","quoteCurrency":"USD","totalLongQty":100,"totalShortQty":40,"netExposure":60,"netExposureUsd":66000,"updatedAt":"2026-06-02T00:00:00Z"},
                  {"symbol":"XAUUSD","quoteCurrency":null,"totalLongQty":10,"totalShortQty":2,"netExposure":8,"netExposureUsd":16000,"updatedAt":"2026-06-02T00:00:00Z"}
                ]},"timestamp":"2026-06-02T00:00:00Z","traceId":null}
                """;
        HttpServer server = startServer(exposureJson);
        try {
            ConsoleServiceProperties properties = new ConsoleServiceProperties();
            properties.getInternalRpc().setGatewayBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            InternalRpcClient client = new InternalRpcClient(properties);
            AdminTradingMonitorApplicationService service = new AdminTradingMonitorApplicationService(
                    client,
                    new com.falconx.console.internal.AdminUserInfoEnricher(userIds -> java.util.Map.of()));
            AdminSecurityContextHolder.set(new AdminPrincipal(
                    1001L, "superadmin", List.of("SUPER_ADMIN"), "test-jti",
                    OffsetDateTime.now().plusMinutes(5), Duration.ofMinutes(5), false));

            AdminTradingExposureListResponse response = service.listExposures(null);

            Assertions.assertThat(response.items()).hasSize(2);
            AdminTradingExposureListResponse.Item eurusd = response.items().get(0);
            Assertions.assertThat(eurusd.symbol()).isEqualTo("EURUSD");
            Assertions.assertThat(eurusd.quoteCurrency()).isEqualTo("USD");
            Assertions.assertThat(eurusd.netExposureUsd()).isEqualByComparingTo("66000");

            AdminTradingExposureListResponse.Item xauusd = response.items().get(1);
            Assertions.assertThat(xauusd.symbol()).isEqualTo("XAUUSD");
            Assertions.assertThat(xauusd.quoteCurrency()).isNull();
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }
}
