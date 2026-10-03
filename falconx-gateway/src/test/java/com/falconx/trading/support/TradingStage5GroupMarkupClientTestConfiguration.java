package com.falconx.trading.support;

import com.falconx.market.contract.event.MarketGroupMarkupListResponse;
import com.falconx.trading.client.MarketGroupMarkupClient;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * gateway E2E 在同一 JVM 内启动 trading-core stage5 上下文，不通过默认网关端口拉取 group markup。
 *
 * <p>该配置只位于 gateway 测试源码；生产环境仍使用正式 {@link MarketGroupMarkupClient}，
 * trading-core 启动期 group markup 加载失败继续 fail-fast。
 */
@Configuration
@Profile("stage5")
public class TradingStage5GroupMarkupClientTestConfiguration {

    @Bean
    @Primary
    MarketGroupMarkupClient gatewayE2eMarketGroupMarkupClient() {
        return new MarketGroupMarkupClient(null) {
            @Override
            public MarketGroupMarkupListResponse fetchAllEnabled() {
                return emptyResponse();
            }

            @Override
            public MarketGroupMarkupListResponse fetchChangesSince(OffsetDateTime since) {
                return emptyResponse();
            }
        };
    }

    private static MarketGroupMarkupListResponse emptyResponse() {
        return new MarketGroupMarkupListResponse(List.of(), OffsetDateTime.now(ZoneOffset.UTC));
    }
}
