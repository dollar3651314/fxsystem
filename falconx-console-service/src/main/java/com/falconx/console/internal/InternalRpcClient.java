package com.falconx.console.internal;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.config.ConsoleServiceProperties;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.security.AdminPrincipal;
import com.falconx.console.security.AdminSecurityContextHolder;
import com.falconx.infrastructure.trace.TraceIdConstants;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * STAGE-2-CUSTOMER：console-service 调用 business-service internal RPC 的统一客户端。
 *
 * <p>调用链路：console → gateway:18080 → business-service。gateway 自动注入
 * {@code X-Internal-Token}（按 [`管理端架构`](docs/architecture/管理端架构.md) §4.1）。
 * 本客户端注入的 header：
 *
 * <ul>
 *   <li>{@code X-Admin-User-Id}：来自 {@link AdminSecurityContextHolder#current()}（必须已登录）</li>
 *   <li>{@code X-Trace-Id}：来自 MDC（gateway 上游应已注入；本地 dev 兜底取 MDC）</li>
 * </ul>
 *
 * <p>错误处理：
 * <ul>
 *   <li>非 2xx 响应：解析 ApiResponse.code，抛 {@link InternalRpcException}（保留下游 code + message）</li>
 *   <li>网络异常 / 超时：抛 {@link InternalRpcException} 含通用错误标识</li>
 * </ul>
 */
@Component
public class InternalRpcClient {

    private static final Logger log = LoggerFactory.getLogger(InternalRpcClient.class);

    /** internal RPC 调用耗时 Timer，tag outcome=success|error，target=后端服务（低基数）。 */
    private static final String METRIC_DURATION = "falconx.console.internal.rpc.duration";

    /** internal RPC 调用失败计数 Counter，tag outcome=error，target=后端服务（低基数）。 */
    private static final String METRIC_FAILURES = "falconx.console.internal.rpc.failures.total";

    private final RestClient restClient;

    /**
     * PROD-OPS-EVIDENCE-01 C4：内部 RPC 热路径指标。可能为 null（无 actuator/registry 时），
     * 埋点处统一守卫判空，照搬 trading-core MarketPriceTickEventConsumer 风格。
     */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Autowired
    public InternalRpcClient(ConsoleServiceProperties properties) {
        this(properties, null);
    }

    InternalRpcClient(ConsoleServiceProperties properties, MeterRegistry meterRegistry) {
        // 用 JdkClientHttpRequestFactory（基于 java.net.http.HttpClient），SimpleClient 走的旧
        // HttpURLConnection 不支持 PATCH（ProtocolException: Invalid HTTP method: PATCH）
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getInternalRpc().getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getInternalRpc().getReadTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getInternalRpc().getGatewayBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.meterRegistry = meterRegistry;
    }

    /**
     * 发起 POST internal RPC。
     *
     * @param path 请求路径，如 {@code /internal/v1/identity/users/100023/freeze}
     * @param requestBody 请求体（用 ObjectMapper 序列化）
     * @param responseType 响应 ApiResponse&lt;T&gt; 的 T 类型
     * @return 已成功的 ApiResponse.data（code=0 时返回，其他抛 {@link InternalRpcException}）
     * @throws AdminBusinessException 当前未登录（90003）
     * @throws InternalRpcException 下游业务错误 / 网络异常
     */
    public <T> T post(String path, Object requestBody, ParameterizedTypeReference<ApiResponse<T>> responseType) {
        AdminPrincipal principal = AdminSecurityContextHolder.current();
        if (principal == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        log.info("admin.internal-rpc.post.received path={} adminUserId={} traceId={}",
                path, principal.adminUserId(), traceId);
        long startNanos = System.nanoTime();
        try {
            ApiResponse<T> response = restClient.post()
                    .uri(path)
                    .header("X-Admin-User-Id", String.valueOf(principal.adminUserId()))
                    .header("X-Trace-Id", traceId == null ? "" : traceId)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(httpStatus -> !httpStatus.is2xxSuccessful(), (req, resp) -> {
                        String body = new String(resp.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        log.warn("admin.internal-rpc.post.failure path={} status={} body={}",
                                path, resp.getStatusCode(), truncate(body, 500));
                        throw new InternalRpcException(resp.getStatusCode().value(), body);
                    })
                    .body(responseType);
            if (response == null) {
                throw new InternalRpcException(500, "empty response");
            }
            if (!"0".equals(response.code())) {
                throw new InternalRpcException(200, response.code(), response.message());
            }
            recordSuccess(path, startNanos);
            return response.data();
        } catch (InternalRpcException e) {
            recordFailure(path, startNanos);
            throw e;
        } catch (Exception e) {
            recordFailure(path, startNanos);
            log.error("admin.internal-rpc.post.exception path={} message={}", path, e.getMessage(), e);
            throw new InternalRpcException(0, "internal-rpc-exception:" + e.getClass().getSimpleName());
        }
    }

    /** STAGE-2-SYMBOL：发起 GET internal RPC。 */
    public <T> T get(String path, ParameterizedTypeReference<ApiResponse<T>> responseType) {
        AdminPrincipal principal = AdminSecurityContextHolder.current();
        if (principal == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        log.info("admin.internal-rpc.get.received path={} adminUserId={} traceId={}",
                path, principal.adminUserId(), traceId);
        long startNanos = System.nanoTime();
        try {
            ApiResponse<T> response = restClient.get()
                    .uri(path)
                    .header("X-Admin-User-Id", String.valueOf(principal.adminUserId()))
                    .header("X-Trace-Id", traceId == null ? "" : traceId)
                    .retrieve()
                    .onStatus(httpStatus -> !httpStatus.is2xxSuccessful(), (req, resp) -> {
                        String body = new String(resp.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        log.warn("admin.internal-rpc.get.failure path={} status={} body={}",
                                path, resp.getStatusCode(), truncate(body, 500));
                        throw new InternalRpcException(resp.getStatusCode().value(), body);
                    })
                    .body(responseType);
            if (response == null) {
                throw new InternalRpcException(500, "empty response");
            }
            if (!"0".equals(response.code())) {
                throw new InternalRpcException(200, response.code(), response.message());
            }
            recordSuccess(path, startNanos);
            return response.data();
        } catch (InternalRpcException e) {
            recordFailure(path, startNanos);
            throw e;
        } catch (Exception e) {
            recordFailure(path, startNanos);
            log.error("admin.internal-rpc.get.exception path={} message={}", path, e.getMessage(), e);
            throw new InternalRpcException(0, "internal-rpc-exception:" + e.getClass().getSimpleName());
        }
    }

    /** STAGE-2-SYMBOL：发起 PUT internal RPC。 */
    public <T> T put(String path, Object requestBody, ParameterizedTypeReference<ApiResponse<T>> responseType) {
        AdminPrincipal principal = AdminSecurityContextHolder.current();
        if (principal == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        log.info("admin.internal-rpc.put.received path={} adminUserId={} traceId={}",
                path, principal.adminUserId(), traceId);
        long startNanos = System.nanoTime();
        try {
            ApiResponse<T> response = restClient.put()
                    .uri(path)
                    .header("X-Admin-User-Id", String.valueOf(principal.adminUserId()))
                    .header("X-Trace-Id", traceId == null ? "" : traceId)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(httpStatus -> !httpStatus.is2xxSuccessful(), (req, resp) -> {
                        String body = new String(resp.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        log.warn("admin.internal-rpc.put.failure path={} status={} body={}",
                                path, resp.getStatusCode(), truncate(body, 500));
                        throw new InternalRpcException(resp.getStatusCode().value(), body);
                    })
                    .body(responseType);
            if (response == null) {
                throw new InternalRpcException(500, "empty response");
            }
            if (!"0".equals(response.code())) {
                throw new InternalRpcException(200, response.code(), response.message());
            }
            recordSuccess(path, startNanos);
            return response.data();
        } catch (InternalRpcException e) {
            recordFailure(path, startNanos);
            throw e;
        } catch (Exception e) {
            recordFailure(path, startNanos);
            log.error("admin.internal-rpc.put.exception path={} message={}", path, e.getMessage(), e);
            throw new InternalRpcException(0, "internal-rpc-exception:" + e.getClass().getSimpleName());
        }
    }

    /** 发起 PATCH internal RPC（与 put 同口径，仅 HTTP 方法不同）。 */
    public <T> T patch(String path, Object requestBody, ParameterizedTypeReference<ApiResponse<T>> responseType) {
        AdminPrincipal principal = AdminSecurityContextHolder.current();
        if (principal == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        log.info("admin.internal-rpc.patch.received path={} adminUserId={} traceId={}",
                path, principal.adminUserId(), traceId);
        long startNanos = System.nanoTime();
        try {
            ApiResponse<T> response = restClient.patch()
                    .uri(path)
                    .header("X-Admin-User-Id", String.valueOf(principal.adminUserId()))
                    .header("X-Trace-Id", traceId == null ? "" : traceId)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(httpStatus -> !httpStatus.is2xxSuccessful(), (req, resp) -> {
                        String body = new String(resp.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        log.warn("admin.internal-rpc.patch.failure path={} status={} body={}",
                                path, resp.getStatusCode(), truncate(body, 500));
                        throw new InternalRpcException(resp.getStatusCode().value(), body);
                    })
                    .body(responseType);
            if (response == null) {
                throw new InternalRpcException(500, "empty response");
            }
            if (!"0".equals(response.code())) {
                throw new InternalRpcException(200, response.code(), response.message());
            }
            recordSuccess(path, startNanos);
            return response.data();
        } catch (InternalRpcException e) {
            recordFailure(path, startNanos);
            throw e;
        } catch (Exception e) {
            recordFailure(path, startNanos);
            log.error("admin.internal-rpc.patch.exception path={} message={}", path, e.getMessage(), e);
            throw new InternalRpcException(0, "internal-rpc-exception:" + e.getClass().getSimpleName());
        }
    }

    /** STAGE-8-NOTIFICATION：发起 DELETE internal RPC（无 body）。 */
    public <T> T delete(String path, ParameterizedTypeReference<ApiResponse<T>> responseType) {
        return deleteWithBody(path, null, responseType);
    }

    /** STAGE-2-RISK-ADMIN：发起 DELETE internal RPC（带 body）。 */
    public <T> T deleteWithBody(String path, Object requestBody,
                                ParameterizedTypeReference<ApiResponse<T>> responseType) {
        AdminPrincipal principal = AdminSecurityContextHolder.current();
        if (principal == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        log.info("admin.internal-rpc.delete.received path={} adminUserId={} traceId={}",
                path, principal.adminUserId(), traceId);
        long startNanos = System.nanoTime();
        try {
            RestClient.RequestBodySpec requestSpec = restClient.method(org.springframework.http.HttpMethod.DELETE)
                    .uri(path)
                    .header("X-Admin-User-Id", String.valueOf(principal.adminUserId()))
                    .header("X-Trace-Id", traceId == null ? "" : traceId);
            RestClient.ResponseSpec responseSpec = (requestBody == null ? requestSpec : requestSpec.body(requestBody))
                    .retrieve()
                    .onStatus(httpStatus -> !httpStatus.is2xxSuccessful(), (req, resp) -> {
                        String body = new String(resp.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        log.warn("admin.internal-rpc.delete.failure path={} status={} body={}",
                                path, resp.getStatusCode(), truncate(body, 500));
                        throw new InternalRpcException(resp.getStatusCode().value(), body);
                    });
            ApiResponse<T> response = responseSpec.body(responseType);
            if (response == null) {
                throw new InternalRpcException(500, "empty response");
            }
            if (!"0".equals(response.code())) {
                throw new InternalRpcException(200, response.code(), response.message());
            }
            recordSuccess(path, startNanos);
            return response.data();
        } catch (InternalRpcException e) {
            recordFailure(path, startNanos);
            throw e;
        } catch (Exception e) {
            recordFailure(path, startNanos);
            log.error("admin.internal-rpc.delete.exception path={} message={}", path, e.getMessage(), e);
            throw new InternalRpcException(0, "internal-rpc-exception:" + e.getClass().getSimpleName());
        }
    }

    /** 探测超时（仅用于测试 / 健康检查；本类内部 timeout 来自 properties）。 */
    public Duration getReadTimeout() {
        return Duration.ZERO;
    }

    /**
     * PROD-OPS-EVIDENCE-01 C4：记录一次成功的 internal RPC（duration timer，outcome=success）。
     * registry 为 null（无 actuator）时静默跳过。
     */
    private void recordSuccess(String path, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.timer(
                METRIC_DURATION,
                "outcome", "success",
                "target", classifyTarget(path)
        ).record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * PROD-OPS-EVIDENCE-01 C4：记录一次失败的 internal RPC（duration timer + failures counter，
     * outcome=error）。非 2xx / 下游 code != 0 / 网络异常均经此计数。registry 为 null 时静默跳过。
     */
    private void recordFailure(String path, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        String target = classifyTarget(path);
        meterRegistry.timer(
                METRIC_DURATION,
                "outcome", "error",
                "target", target
        ).record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        meterRegistry.counter(
                METRIC_FAILURES,
                "outcome", "error",
                "target", target
        ).increment();
    }

    /**
     * 按 {@code /internal/v1/<target>/...} 的第三段归类后端服务，保证低基数（trading/market/
     * identity/wallet/system-config，未知归 other）。绝不把完整 path/query 作为 tag（§3.13.5）。
     */
    private static String classifyTarget(String path) {
        if (path == null) {
            return "other";
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        String[] segments = trimmed.split("/");
        // 期望形如 internal / v1 / <target> / ...
        if (segments.length >= 3 && "internal".equals(segments[0])) {
            String target = segments[2].toLowerCase(Locale.ROOT);
            return switch (target) {
                case "trading", "market", "identity", "wallet", "system-config" -> target;
                default -> "other";
            };
        }
        return "other";
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max) + "...(truncated)";
    }
}
