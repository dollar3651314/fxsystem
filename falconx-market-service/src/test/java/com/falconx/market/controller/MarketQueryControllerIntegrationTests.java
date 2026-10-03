package com.falconx.market.controller;

import com.falconx.market.MarketServiceApplication;
import com.falconx.market.application.MarketDataIngestionApplicationService;
import com.falconx.market.analytics.mapper.MarketKlineMapper;
import com.falconx.market.analytics.mapper.record.MarketKlineRecord;
import com.falconx.market.analytics.mapper.test.MarketAnalyticsTestSupportMapper;
import com.falconx.market.config.MarketTraceContextFilter;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.service.KlineAggregationService;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 市场查询控制器集成测试。
 *
 * <p>该测试覆盖 Stage 5 真实基础设施下的最小市场北向查询能力，
 * 验证服务可以从 Redis 读取真实写入的最新报价快照，
 * 同时验证 traceId 响应头和统一错误响应结构。
 */
@ActiveProfiles("stage5")
@ContextConfiguration(initializers = MarketTestDatabaseInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {
                MarketServiceApplication.class,
                MarketMybatisTestSupportConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_market_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "falconx.market.analytics.jdbc-url=jdbc:clickhouse://localhost:8123/falconx_market_analytics",
                "falconx.market.analytics.username=default",
                "falconx.market.analytics.password=falconx",
                "falconx.market.stale.max-age=30s"
        }
)
class MarketQueryControllerIntegrationTests {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MarketTraceContextFilter marketTraceContextFilter;

    @Autowired
    private MarketDataIngestionApplicationService marketDataIngestionApplicationService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MarketAnalyticsTestSupportMapper marketAnalyticsTestSupportMapper;

    @Autowired
    private MarketKlineMapper marketKlineMapper;

    @Autowired
    private KlineAggregationService klineAggregationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        stringRedisTemplate.delete("falconx:market:price:EURUSD");
        stringRedisTemplate.delete("falconx:market:last-valid-price:EURUSD");
        stringRedisTemplate.delete("falconx:market:price:EURUSD.f");
        stringRedisTemplate.delete("falconx:market:last-valid-price:EURUSD.f");
        marketAnalyticsTestSupportMapper.clearAnalyticsTables();
        clearKlineAggregationState();
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(marketTraceContextFilter)
                .build();
    }

    @Test
    void shouldReturnLatestQuoteWhenSymbolExists() throws Exception {
        marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08100000"),
                new BigDecimal("1.08120000"),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));
        MockHttpServletResponse response = get("/api/v1/market/quotes/EURUSD");
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertNotNull(response.getHeader("X-Trace-Id"));
        Assertions.assertTrue(body.contains("\"code\":\"0\""));
        Assertions.assertTrue(body.contains("\"symbol\":\"EURUSD\""));
        Assertions.assertTrue(body.contains("\"source\":\"TM_QUOTE\""));
        Assertions.assertTrue(body.contains("\"quoteStatus\":\"FRESH\""));
    }

    @Test
    void shouldReturnSymbolListWithLivePriceWhenLatestQuoteExists() throws Exception {
        marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08100000"),
                new BigDecimal("1.08120000"),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));

        MockHttpServletResponse response = get("/api/v1/market/symbols");
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"0\""));
        Assertions.assertTrue(body.contains("\"symbol\":\"EURUSD\""));
        Assertions.assertTrue(body.contains("\"bid\":1.08100000"));
        Assertions.assertTrue(body.contains("\"ask\":1.08120000"));
        Assertions.assertTrue(body.contains("\"priceStatus\":\"LIVE\""));
        Assertions.assertTrue(body.contains("\"tradable\":true"));
    }

    @Test
    void shouldReturnSymbolListWithReferencePriceWhenLatestQuoteExpired() throws Exception {
        marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                "EURUSD",
                new BigDecimal("1.08100000"),
                new BigDecimal("1.08120000"),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));
        stringRedisTemplate.delete("falconx:market:price:EURUSD");

        MockHttpServletResponse response = get("/api/v1/market/symbols");
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"0\""));
        Assertions.assertTrue(body.contains("\"symbol\":\"EURUSD\""));
        Assertions.assertTrue(body.contains("\"bid\":1.08100000"));
        Assertions.assertTrue(body.contains("\"ask\":1.08120000"));
        Assertions.assertTrue(body.contains("\"priceStatus\":\"REFERENCE\""));
        Assertions.assertTrue(body.contains("\"tradable\":false"));
    }

    @Test
    void shouldReturnBusinessCodeWhenQuoteDoesNotExist() throws Exception {
        MockHttpServletResponse response = get("/api/v1/market/quotes/EURUSD");
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"30003\""));
        Assertions.assertTrue(body.contains("\"message\":\"Quote Not Available\""));
        Assertions.assertTrue(body.contains("\"traceId\":\""));
    }

    @Test
    void shouldReturnRecentKlinesForSymbol() throws Exception {
        marketKlineMapper.insertKline(new MarketKlineRecord(
                "EURUSD",
                "1m",
                new BigDecimal("1.08000000"),
                new BigDecimal("1.08200000"),
                new BigDecimal("1.07900000"),
                new BigDecimal("1.08100000"),
                BigDecimal.ONE,
                LocalDateTime.parse("2026-04-30T10:00:00"),
                LocalDateTime.parse("2026-04-30T10:00:59"),
                "market-service"
        ));
        marketKlineMapper.insertKline(new MarketKlineRecord(
                "EURUSD",
                "1m",
                new BigDecimal("1.08100000"),
                new BigDecimal("1.08300000"),
                new BigDecimal("1.08050000"),
                new BigDecimal("1.08250000"),
                BigDecimal.ONE,
                LocalDateTime.parse("2026-04-30T10:01:00"),
                LocalDateTime.parse("2026-04-30T10:01:59"),
                "market-service"
        ));

        MockHttpServletResponse response = get("/api/v1/market/klines/EURUSD?interval=1m&limit=2");
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"0\""));
        Assertions.assertTrue(body.contains("\"klines\""));
        Assertions.assertTrue(body.contains("\"open\":1.08000000"));
        Assertions.assertTrue(body.contains("\"close\":1.08250000"));
        Assertions.assertTrue(body.contains("\"openTime\":\"2026-04-30T10:00:00Z\""));
        Assertions.assertTrue(body.contains("\"closeTime\":\"2026-04-30T10:01:59Z\""));
        Assertions.assertTrue(body.contains("\"isFinal\":true"));
    }

    @Test
    void shouldRejectDeletedLpSuffixSymbolKlineQuery() throws Exception {
        MockHttpServletResponse response = get("/api/v1/market/klines/EURUSD.f?interval=1m&limit=2");
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(body.contains("\"code\":\"30001\""));
        Assertions.assertTrue(body.contains("\"message\":\"Symbol Not Found\""));
    }

    private MockHttpServletResponse get(String path) throws Exception {
        MvcResult mvcResult = mockMvc.perform(MockMvcRequestBuilders.get(path)).andReturn();
        return mvcResult.getResponse();
    }

    @SuppressWarnings("unchecked")
    private void clearKlineAggregationState() {
        Object bucketsField = ReflectionTestUtils.getField(klineAggregationService, "buckets");
        if (bucketsField instanceof Map<?, ?> buckets) {
            buckets.clear();
        }
    }
}
