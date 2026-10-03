package com.falconx.market.application;

import com.falconx.market.MarketServiceApplication;
import com.falconx.market.repository.mapper.test.MarketSymbolTestSupportMapper;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.provider.MarketQuoteProvider;
import com.falconx.market.repository.MarketSymbolQuoteMappingRepository;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * 市场数据启动器集成测试。
 *
 * <p>该测试不再手写一组演示用 `MarketSymbol` 列表，而是直接依赖 market owner 的真实
 * `MyBatis + XML + Flyway` 初始化结果，验证启动器拿到的本地放行白名单与数据库中的启用品种完全一致。
 * 这样后续扩展 `t_symbol` 种子或生产库配置时，测试仍然围绕真实 owner 数据运行，不会再出现
 * “测试用产品清单”和“数据库实际产品清单”分叉的问题。
 */
@ActiveProfiles("stage5")
@ContextConfiguration(initializers = MarketTestDatabaseInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {
                MarketServiceApplication.class,
                MarketMybatisTestSupportConfiguration.class,
                MarketFeedBootstrapRunnerTests.RecordingMarketQuoteProviderConfiguration.class
        },
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_market_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "falconx.market.analytics.jdbc-url=jdbc:clickhouse://localhost:8123/falconx_market_analytics",
                "falconx.market.analytics.username=default",
                "falconx.market.analytics.password=falconx"
        }
)
class MarketFeedBootstrapRunnerTests {

    @Autowired
    private MarketSymbolQuoteMappingRepository marketSymbolQuoteMappingRepository;

    @Autowired
    private RecordingMarketQuoteProvider recordingMarketQuoteProvider;

    @Autowired
    private MarketFeedBootstrapRunner marketFeedBootstrapRunner;

    @Autowired
    private MarketSymbolTestSupportMapper marketSymbolTestSupportMapper;

    @org.junit.jupiter.api.BeforeEach
    void resetSeedSymbols() {
        marketSymbolTestSupportMapper.updateSymbolStatus("XAUUSD", 1);
        marketSymbolTestSupportMapper.updateLpSubscribeEnabled("XAUUSD", 1);
    }

    @Test
    void shouldUseAllEnabledSymbolsFromMarketOwnerRepository() {
        List<String> expectedSymbols = marketSymbolQuoteMappingRepository.findAllLpSubscribedMappings().stream()
                .map(com.falconx.market.entity.MarketSymbolQuoteMapping::sourceSymbol)
                .distinct()
                .sorted()
                .toList();

        Assertions.assertEquals(expectedSymbols, recordingMarketQuoteProvider.startedWithSymbols());
        Assertions.assertTrue(recordingMarketQuoteProvider.startedWithSymbols().stream()
                .noneMatch(MarketFeedBootstrapRunnerTests::hasBlockedLpSuffix));
    }

    @Test
    void shouldRefreshWhitelistWhenOwnerSymbolStatusChanges() {
        marketSymbolTestSupportMapper.updateSymbolStatus("XAUUSD", 2);

        marketFeedBootstrapRunner.refreshQuoteSymbolWhitelist();

        List<String> expectedSymbols = marketSymbolQuoteMappingRepository.findAllLpSubscribedMappings().stream()
                .map(com.falconx.market.entity.MarketSymbolQuoteMapping::sourceSymbol)
                .distinct()
                .sorted()
                .toList();

        Assertions.assertEquals(expectedSymbols, recordingMarketQuoteProvider.lastRefreshedSymbols());
        Assertions.assertFalse(recordingMarketQuoteProvider.lastRefreshedSymbols().contains("XAUUSD"));
    }

    @Test
    void shouldRefreshWhitelistWhenLpSubscriptionSwitchChanges() {
        marketSymbolTestSupportMapper.updateLpSubscribeEnabled("XAUUSD", 0);

        marketFeedBootstrapRunner.refreshQuoteSymbolWhitelist();

        List<String> expectedSymbols = marketSymbolQuoteMappingRepository.findAllLpSubscribedMappings().stream()
                .map(com.falconx.market.entity.MarketSymbolQuoteMapping::sourceSymbol)
                .distinct()
                .sorted()
                .toList();

        Assertions.assertEquals(expectedSymbols, recordingMarketQuoteProvider.lastRefreshedSymbols());
        Assertions.assertFalse(recordingMarketQuoteProvider.lastRefreshedSymbols().contains("XAUUSD"));
    }

    @Test
    void shouldUseCleanedOwnerTablesWithoutBlockedLpSuffixSymbols() {
        Assertions.assertEquals(0L, marketSymbolTestSupportMapper.countBlockedSuffixSymbols());
        Assertions.assertEquals(0L, marketSymbolTestSupportMapper.countBlockedSuffixGroupVisibility());
        Assertions.assertEquals(0L, marketSymbolTestSupportMapper.countBlockedSuffixQuoteMappings());

        marketFeedBootstrapRunner.refreshQuoteSymbolWhitelist();

        Assertions.assertTrue(recordingMarketQuoteProvider.lastRefreshedSymbols().stream()
                .noneMatch(MarketFeedBootstrapRunnerTests::hasBlockedLpSuffix));
    }

    private static boolean hasBlockedLpSuffix(String symbol) {
        String normalized = symbol.toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith(".p") || normalized.endsWith(".c") || normalized.endsWith(".f");
    }

    /**
     * 测试专用行情 Provider 配置。
     *
     * <p>这里使用 `@Primary` 覆盖运行时 Provider，让应用启动阶段仍然完整执行
     * `MarketFeedBootstrapRunner -> resolveQuoteSymbols -> MarketQuoteProvider.start(...)`，
     * 但不会真的向外部 WebSocket 建连。测试只关心启动器最终传给 Provider 的 symbol 白名单。
     */
    @TestConfiguration
    static class RecordingMarketQuoteProviderConfiguration {

        @Bean
        @Primary
        RecordingMarketQuoteProvider recordingMarketQuoteProvider() {
            return new RecordingMarketQuoteProvider();
        }
    }

    /**
     * 录制型行情 Provider。
     *
     * <p>该实现只记录启动器传入的 symbol 列表，不执行任何网络连接。
     * 这样可以在不引入内存仓储或硬编码产品清单的前提下，验证启动器是否正确依赖了 owner 数据源。
     */
    static final class RecordingMarketQuoteProvider implements MarketQuoteProvider {

        private volatile List<String> startedWithSymbols = List.of();
        private volatile List<String> lastRefreshedSymbols = List.of();

        @Override
        public void start(List<String> symbols, Consumer<ExternalRawQuote> quoteConsumer) {
            this.startedWithSymbols = List.copyOf(symbols);
        }

        @Override
        public void refreshSymbols(List<String> symbols) {
            this.lastRefreshedSymbols = List.copyOf(symbols);
        }

        List<String> startedWithSymbols() {
            return startedWithSymbols;
        }

        List<String> lastRefreshedSymbols() {
            return lastRefreshedSymbols;
        }
    }
}
