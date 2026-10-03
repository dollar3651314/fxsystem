package com.falconx.console.internal;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.config.ConsoleServiceProperties;
import com.falconx.console.security.AdminPrincipal;
import com.falconx.console.security.AdminSecurityContextHolder;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

/**
 * PROD-OPS-EVIDENCE-01 C4：验证 {@link InternalRpcClient} 在内部 RPC 热路径上的 Micrometer 埋点。
 *
 * <ul>
 *   <li>成功调用后 {@code falconx.console.internal.rpc.duration{outcome=success}} 有 timer 记录。</li>
 *   <li>失败调用后 {@code falconx.console.internal.rpc.failures.total{outcome=error}} 计数 +1，
 *       且 duration timer 也记到 {@code outcome=error}。</li>
 *   <li>tag {@code target} 按 path 第三段低基数归类（trading/market/...）。</li>
 * </ul>
 */
class InternalRpcClientMetricsTests {

    private static final String METRIC_DURATION = "falconx.console.internal.rpc.duration";
    private static final String METRIC_FAILURES = "falconx.console.internal.rpc.failures.total";

    @AfterEach
    void tearDown() {
        AdminSecurityContextHolder.clear();
    }

    @Test
    void shouldRecordSuccessTimerWhenRpcSucceeds() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HttpServer server = startServer(200, """
                {"code":"0","message":"success","data":null,"timestamp":"2026-06-02T00:00:00Z","traceId":null}
                """);
        try {
            InternalRpcClient client = newClient(server, registry);

            Assertions.assertDoesNotThrow(() -> client.get(
                    "/internal/v1/trading/positions/summary",
                    new ParameterizedTypeReference<ApiResponse<Void>>() {
                    }
            ));

            Timer successTimer = registry.find(METRIC_DURATION)
                    .tag("outcome", "success")
                    .tag("target", "trading")
                    .timer();
            Assertions.assertNotNull(successTimer, "成功 RPC 应记录 duration{outcome=success}");
            Assertions.assertEquals(1L, successTimer.count());

            // 成功路径不应产生失败计数。
            Counter failures = registry.find(METRIC_FAILURES).counter();
            Assertions.assertNull(failures, "成功 RPC 不应增加 failures counter");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRecordFailureCounterWhenRpcReturnsNon2xx() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HttpServer server = startServer(500, """
                {"code":"50000","message":"downstream error","data":null,"timestamp":"2026-06-02T00:00:00Z","traceId":null}
                """);
        try {
            InternalRpcClient client = newClient(server, registry);

            Assertions.assertThrows(InternalRpcException.class, () -> client.get(
                    "/internal/v1/market/symbols/list",
                    new ParameterizedTypeReference<ApiResponse<Void>>() {
                    }
            ));

            Counter failures = registry.find(METRIC_FAILURES)
                    .tag("outcome", "error")
                    .tag("target", "market")
                    .counter();
            Assertions.assertNotNull(failures, "失败 RPC 应记录 failures{outcome=error}");
            Assertions.assertEquals(1.0d, failures.count());

            Timer errorTimer = registry.find(METRIC_DURATION)
                    .tag("outcome", "error")
                    .tag("target", "market")
                    .timer();
            Assertions.assertNotNull(errorTimer, "失败 RPC 应记录 duration{outcome=error}");
            Assertions.assertEquals(1L, errorTimer.count());

            // 失败路径不应记到 success timer。
            Timer successTimer = registry.find(METRIC_DURATION).tag("outcome", "success").timer();
            Assertions.assertNull(successTimer, "失败 RPC 不应记录 duration{outcome=success}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRecordFailureCounterWhenDownstreamBusinessCodeNonZero() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HttpServer server = startServer(200, """
                {"code":"40001","message":"biz error","data":null,"timestamp":"2026-06-02T00:00:00Z","traceId":null}
                """);
        try {
            InternalRpcClient client = newClient(server, registry);

            Assertions.assertThrows(InternalRpcException.class, () -> client.post(
                    "/internal/v1/identity/users/100023/freeze",
                    null,
                    new ParameterizedTypeReference<ApiResponse<Void>>() {
                    }
            ));

            Counter failures = registry.find(METRIC_FAILURES)
                    .tag("outcome", "error")
                    .tag("target", "identity")
                    .counter();
            Assertions.assertNotNull(failures, "下游 code!=0 应记 failures{outcome=error}");
            Assertions.assertEquals(1.0d, failures.count());
        } finally {
            server.stop(0);
        }
    }

    private static InternalRpcClient newClient(HttpServer server, SimpleMeterRegistry registry) {
        ConsoleServiceProperties properties = new ConsoleServiceProperties();
        properties.getInternalRpc().setGatewayBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        InternalRpcClient client = new InternalRpcClient(properties, registry);
        AdminSecurityContextHolder.set(new AdminPrincipal(
                1001L,
                "superadmin",
                List.of("SUPER_ADMIN"),
                "test-jti",
                OffsetDateTime.now().plusMinutes(5),
                Duration.ofMinutes(5),
                false
        ));
        return client;
    }

    private static HttpServer startServer(int status, String jsonBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = jsonBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }
}
