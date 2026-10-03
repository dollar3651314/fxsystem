package com.falconx.trading.client;

import com.falconx.common.api.ApiResponse;
import com.falconx.market.contract.event.MarketGroupMarkupListResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * STAGE-12-GROUP-MARKUP：market-service 用户组加点配置查询客户端。
 *
 * <p>调用链：trading-core → gateway:18080 → market-service
 * {@code /internal/v1/market/symbols/group-markup/*}。
 *
 * <p>提供两个端点：
 * <ul>
 *   <li>{@link #fetchAllEnabled()}：启动期全量拉取</li>
 *   <li>{@link #fetchChangesSince(OffsetDateTime)}：30s 定时增量刷新</li>
 * </ul>
 */
@Component
public class MarketGroupMarkupClient {

    private static final Logger log = LoggerFactory.getLogger(MarketGroupMarkupClient.class);

    private static final ParameterizedTypeReference<ApiResponse<MarketGroupMarkupListResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final TradingExternalRpcClient rpcClient;

    public MarketGroupMarkupClient(TradingExternalRpcClient rpcClient) {
        this.rpcClient = rpcClient;
    }

    /**
     * 启动期全量加载。
     */
    public MarketGroupMarkupListResponse fetchAllEnabled() {
        String path = "/internal/v1/market/symbols/group-markup/all-enabled";
        MarketGroupMarkupListResponse response = rpcClient.get(path, RESPONSE_TYPE);
        if (response == null) {
            log.warn("trading.group-markup.fetch.all-enabled.empty");
            return new MarketGroupMarkupListResponse(java.util.List.of(), OffsetDateTime.now());
        }
        log.info("trading.group-markup.fetch.all-enabled.received itemCount={} serverTime={}",
                response.items() == null ? 0 : response.items().size(), response.serverTime());
        return response;
    }

    /**
     * 增量拉取自指定时间起变更过的配置。
     */
    public MarketGroupMarkupListResponse fetchChangesSince(OffsetDateTime since) {
        long sinceMillis = since == null
                ? 0L
                : since.toInstant().toEpochMilli();
        String path = UriComponentsBuilder.fromUriString("/internal/v1/market/symbols/group-markup/changes")
                .queryParam("since", sinceMillis)
                .build()
                .toUriString();
        MarketGroupMarkupListResponse response = rpcClient.get(path, RESPONSE_TYPE);
        if (response == null) {
            log.debug("trading.group-markup.fetch.changes.empty since={}", since);
            return new MarketGroupMarkupListResponse(java.util.List.of(),
                    OffsetDateTime.now(ZoneOffset.UTC));
        }
        return response;
    }
}
