package com.falconx.market;

import com.falconx.market.application.MarketDataIngestionApplicationService;
import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.provider.ExternalRawQuote;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 单一生产行情源架构约束测试。
 */
class MarketSingleQuoteSourceArchitectureTests {

    @Test
    void shouldExposeOnlyLpQuoteSourceConfiguration() {
        Assertions.assertEquals(
                1L,
                Arrays.stream(MarketServiceProperties.class.getDeclaredFields())
                        .map(Field::getName)
                        .filter("lp"::equals)
                        .count(),
                "market-service 必须只暴露 LP 行情源配置"
        );
        Assertions.assertTrue(
                Arrays.stream(MarketServiceProperties.class.getDeclaredClasses())
                        .anyMatch(type -> "Lp".equals(type.getSimpleName())),
                "market-service 配置必须保留 LP 嵌套配置类"
        );
    }

    @Test
    void shouldAllowLocalLpEnvAliasFallbacks() throws IOException {
        String applicationYml = new String(
                getClass().getClassLoader()
                        .getResourceAsStream("application.yml")
                        .readAllBytes(),
                StandardCharsets.UTF_8
        );

        Assertions.assertTrue(applicationYml.contains("domain: ${FALCONX_MARKET_LP_DOMAIN:${domain:}}"));
        Assertions.assertTrue(applicationYml.contains("app-id: ${FALCONX_MARKET_LP_APP_ID:${APP-ID:}}"));
        Assertions.assertTrue(applicationYml.contains("token: ${FALCONX_MARKET_LP_TOKEN:${token:}}"));
        Assertions.assertTrue(applicationYml.contains("secret-key: ${FALCONX_MARKET_LP_SECRET_KEY:${secretKey:}}"));
        Assertions.assertTrue(applicationYml.contains("server-id: ${FALCONX_MARKET_LP_SERVER_ID:17}"));
        Assertions.assertTrue(applicationYml.contains("meta-trade-version: ${FALCONX_MARKET_LP_META_TRADE_VERSION:${metaTradeVersion:4}}"));
        Assertions.assertTrue(applicationYml.contains("meta-trader-id: ${FALCONX_MARKET_LP_META_TRADER_ID:${metaTraderId:${serverId:0}}}"));
    }

    @Test
    void shouldIngestOnlyGenericExternalRawQuote() {
        Assertions.assertTrue(
                Arrays.stream(MarketDataIngestionApplicationService.class.getDeclaredMethods())
                        .anyMatch(method -> "ingest".equals(method.getName())
                                && Arrays.equals(method.getParameterTypes(), new Class<?>[]{ExternalRawQuote.class})),
                "行情接入应用服务必须保留通用 ExternalRawQuote 入口"
        );
        Assertions.assertTrue(
                Arrays.stream(MarketDataIngestionApplicationService.class.getDeclaredMethods())
                        .filter(method -> "ingest".equals(method.getName()))
                        .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                        .allMatch(ExternalRawQuote.class::equals),
                "行情接入应用服务不应继续保留特定报价源专用重载"
        );
    }
}
