package com.falconx.trading.client;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.error.TradingExternalRpcException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * STAGE-7-WITHDRAW：trading-core 调外部 internal RPC 的统一 HTTP 客户端。
 *
 * <p>调用链：trading-core → gateway:18080 → identity/wallet。
 * 注入的 header：
 * <ul>
 *   <li>{@code X-Internal-Token}：来自 {@link TradingCoreServiceProperties.InternalApi#getToken()}</li>
 *   <li>{@code X-Admin-User-Id}：来自 {@link TradingCoreServiceProperties.ExternalRpc#getServiceCallerId()}（service-to-service 标识值）</li>
 *   <li>{@code X-Trace-Id}：来自 MDC（gateway 上游应已注入）</li>
 * </ul>
 *
 * <p>错误处理：
 * <ul>
 *   <li>非 2xx 响应：抛 {@link TradingExternalRpcException} 携带 status + body</li>
 *   <li>ApiResponse.code 非 "0"：抛 {@link TradingExternalRpcException} 携带下游 code + message</li>
 *   <li>反序列化异常 / 网络异常：抛 {@link TradingExternalRpcException}（httpStatus=0）</li>
 * </ul>
 *
 * <p>调用方负责把 {@link TradingExternalRpcException#getDownstreamCode()} 翻译为本服务的业务错误码。
 */
@Component
public class TradingExternalRpcClient {

    private static final Logger log = LoggerFactory.getLogger(TradingExternalRpcClient.class);
    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";
    private static final String HEADER_ADMIN_USER_ID = "X-Admin-User-Id";
    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    private final RestClient restClient;
    private final TradingCoreServiceProperties properties;

    public TradingExternalRpcClient(TradingCoreServiceProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getExternalRpc().getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getExternalRpc().getReadTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getExternalRpc().getGatewayBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 发起 GET internal RPC。
     *
     * @param path 请求路径（含 leading slash），如 {@code /internal/v1/identity/users/123/kyc-status}
     * @param responseType ApiResponse&lt;T&gt; 的类型引用
     * @return ApiResponse.data（code=0 时返回）
     * @throws TradingExternalRpcException 下游业务错误 / 网络异常
     */
    public <T> T get(String path, ParameterizedTypeReference<ApiResponse<T>> responseType) {
        long callerId = properties.getExternalRpc().getServiceCallerId();
        String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        log.info("trading.external-rpc.get.received path={} callerId={} traceId={}", path, callerId, traceId);
        try {
            ApiResponse<T> response = restClient.get()
                    .uri(path)
                    .headers(headers -> applyHeaders(headers, traceId))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                        String body = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        log.warn("trading.external-rpc.get.failure path={} status={} body={}",
                                path, resp.getStatusCode(), truncate(body, 500));
                        throw new TradingExternalRpcException(resp.getStatusCode().value(), null, body);
                    })
                    .body(responseType);
            return unwrap(response, path, "get");
        } catch (TradingExternalRpcException e) {
            throw e;
        } catch (Exception e) {
            log.error("trading.external-rpc.get.exception path={} message={}", path, e.getMessage(), e);
            throw new TradingExternalRpcException(0, null,
                    "external-rpc-exception:" + e.getClass().getSimpleName());
        }
    }

    /** STAGE-7-WITHDRAW Phase 2：发起 POST internal RPC。 */
    public <T> T post(String path, Object body, ParameterizedTypeReference<ApiResponse<T>> responseType) {
        long callerId = properties.getExternalRpc().getServiceCallerId();
        String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        log.info("trading.external-rpc.post.received path={} callerId={} traceId={}", path, callerId, traceId);
        try {
            ApiResponse<T> response = restClient.post()
                    .uri(path)
                    .headers(headers -> applyHeaders(headers, traceId))
                    .body(body == null ? java.util.Map.of() : body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                        String respBody = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        log.warn("trading.external-rpc.post.failure path={} status={} body={}",
                                path, resp.getStatusCode(), truncate(respBody, 500));
                        throw new TradingExternalRpcException(resp.getStatusCode().value(), null, respBody);
                    })
                    .body(responseType);
            return unwrap(response, path, "post");
        } catch (TradingExternalRpcException e) {
            throw e;
        } catch (Exception e) {
            log.error("trading.external-rpc.post.exception path={} message={}", path, e.getMessage(), e);
            throw new TradingExternalRpcException(0, null,
                    "external-rpc-exception:" + e.getClass().getSimpleName());
        }
    }

    /** STAGE-7-WITHDRAW Phase 2：发起 DELETE internal RPC。 */
    public <T> T delete(String path, ParameterizedTypeReference<ApiResponse<T>> responseType) {
        long callerId = properties.getExternalRpc().getServiceCallerId();
        String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        log.info("trading.external-rpc.delete.received path={} callerId={} traceId={}", path, callerId, traceId);
        try {
            ApiResponse<T> response = restClient.delete()
                    .uri(path)
                    .headers(headers -> applyHeaders(headers, traceId))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                        String body = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        log.warn("trading.external-rpc.delete.failure path={} status={} body={}",
                                path, resp.getStatusCode(), truncate(body, 500));
                        throw new TradingExternalRpcException(resp.getStatusCode().value(), null, body);
                    })
                    .body(responseType);
            return unwrap(response, path, "delete");
        } catch (TradingExternalRpcException e) {
            throw e;
        } catch (Exception e) {
            log.error("trading.external-rpc.delete.exception path={} message={}", path, e.getMessage(), e);
            throw new TradingExternalRpcException(0, null,
                    "external-rpc-exception:" + e.getClass().getSimpleName());
        }
    }

    private void applyHeaders(org.springframework.http.HttpHeaders headers, String traceId) {
        String token = properties.getInternalApi().getToken();
        long callerId = properties.getExternalRpc().getServiceCallerId();
        headers.set(HEADER_INTERNAL_TOKEN, token == null ? "" : token);
        headers.set(HEADER_ADMIN_USER_ID, String.valueOf(callerId));
        headers.set(HEADER_TRACE_ID, traceId == null ? "" : traceId);
    }

    private static <T> T unwrap(ApiResponse<T> response, String path, String method) {
        if (response == null) {
            throw new TradingExternalRpcException(500, null, "empty response");
        }
        if (!"0".equals(response.code())) {
            throw new TradingExternalRpcException(200, response.code(), response.message());
        }
        return response.data();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max) + "...(truncated)";
    }
}
