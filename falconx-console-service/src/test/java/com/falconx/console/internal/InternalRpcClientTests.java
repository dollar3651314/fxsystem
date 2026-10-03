package com.falconx.console.internal;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.config.ConsoleServiceProperties;
import com.falconx.console.security.AdminPrincipal;
import com.falconx.console.security.AdminSecurityContextHolder;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

/**
 * console internal RPC 客户端的 HTTP 方法回归测试。
 */
class InternalRpcClientTests {

    @AfterEach
    void tearDown() {
        AdminSecurityContextHolder.clear();
    }

    @Test
    void shouldSendDeleteWithoutBody() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();

        HttpServer server = startServer(method, path, body);
        try {
            ConsoleServiceProperties properties = new ConsoleServiceProperties();
            properties.getInternalRpc().setGatewayBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            InternalRpcClient client = new InternalRpcClient(properties);
            AdminSecurityContextHolder.set(new AdminPrincipal(
                    1001L,
                    "superadmin",
                    List.of("SUPER_ADMIN"),
                    "test-jti",
                    OffsetDateTime.now().plusMinutes(5),
                    Duration.ofMinutes(5),
                    false
            ));

            Assertions.assertDoesNotThrow(() -> client.delete(
                    "/internal/v1/market/symbols/market-holidays/1",
                    new ParameterizedTypeReference<ApiResponse<Void>>() {
                    }
            ));

            Assertions.assertEquals("DELETE", method.get());
            Assertions.assertEquals("/internal/v1/market/symbols/market-holidays/1", path.get());
            Assertions.assertEquals("", body.get());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(AtomicReference<String> method,
                                          AtomicReference<String> path,
                                          AtomicReference<String> body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"code":"0","message":"success","data":null,"timestamp":"2026-05-25T00:00:00Z","traceId":null}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }
}
