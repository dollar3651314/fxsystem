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
 * stage5 集成测试不启动 gateway / market-service，避免 trading-core 上下文启动依赖外部 RPC。
 *
 * <p>生产环境仍使用正式 {@link MarketGroupMarkupClient}，启动期 group markup 加载失败继续 fail-fast。
 */
@Configuration
@Profile("stage5")
public class TradingStage5GroupMarkupClientTestConfiguration {

    @Bean
    @Primary
    MarketGroupMarkupClient stage5MarketGroupMarkupClient() {
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
