package com.falconx.market.integration;

import com.falconx.market.MarketServiceApplication;
import com.falconx.market.config.MarketTraceContextFilter;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.security.MarketInternalApiTokenFilter;
import com.falconx.market.service.FxRateService;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * STAGE-14A Task 10 — MarketFxRateInternalController RPC 集成测试（TC-006 / TC-007 / TC-008）。
 *
 * <p>使用真实 MySQL + Redis，{@code WebEnvironment.MOCK} + MockMvc 发起 HTTP 请求，
 * 与既有 {@code MarketGroupMarkupInternalControllerIntegrationTests} 保持相同策略。
 *
 * <ul>
 *   <li>TC-FX-IT-006: GET /internal/v1/market/fx/rates → 200 + 非空列表</li>
 *   <li>TC-FX-IT-007: GET /internal/v1/market/fx/rates/EUR/USD → 200 含正确 base/quote/rate</li>
 *   <li>TC-FX-IT-008: GET /internal/v1/market/fx/rates/XXX/YYY → 200 + code 60010</li>
 * </ul>
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
                "falconx.market.fx.redis-ttl-seconds=5",
                "falconx.market.lp.enabled=false",
                /* internal RPC token：测试用固定值 */
                "falconx.market.internal-api.token=test-internal-token"
        }
)
class FxRateInternalRpcIntegrationTests {

    private static final String TOKEN = "test-internal-token";
    private static final String ADMIN_USER_ID = "1";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MarketTraceContextFilter marketTraceContextFilter;

    @Autowired
    private MarketInternalApiTokenFilter marketInternalApiTokenFilter;

    @Autowired
    private FxRateService fxRateService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(marketTraceContextFilter, marketInternalApiTokenFilter)
                .build();

        /* 预置一条 EUR/USD 快照到内存，供 TC-007 查询 */
        fxRateService.acceptTick(new FxRateSnapshotPayload(
                "EUR", "USD",
                new BigDecimal("1.09000"),
                System.currentTimeMillis(),
                "GODSA", "EURUSD"
        ));
    }

    /**
     * TC-FX-IT-006: GET /internal/v1/market/fx/rates → HTTP 200 + code=0 + 非空列表。
     *
     * <p>至少存在 setUp 预置的 EUR/USD，所以列表不应为空。
     */
    @Test
    void tc006_snapshotAllReturns200AndNonEmptyList() throws Exception {
        MockHttpServletResponse response = get("/internal/v1/market/fx/rates");
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus(), "HTTP 状态码应为 200");
        Assertions.assertTrue(body.contains("\"code\":\"0\""), "业务码应为 0（成功）");
        Assertions.assertTrue(body.contains("\"data\":["), "data 字段应为数组");
        Assertions.assertFalse(body.contains("\"data\":[]"), "快照列表不应为空（至少含 EUR/USD）");
        Assertions.assertTrue(body.contains("\"baseCurrency\""), "列表项应含 baseCurrency 字段");
        Assertions.assertTrue(body.contains("\"quoteCurrency\""), "列表项应含 quoteCurrency 字段");
        Assertions.assertTrue(body.contains("\"rate\""), "列表项应含 rate 字段");
    }

    /**
     * TC-FX-IT-007: GET /internal/v1/market/fx/rates/EUR/USD → HTTP 200 + 含正确 base/quote/rate。
     *
     * <p>setUp 预置了 EUR/USD rate=1.09000，查询应返回完全吻合的 payload。
     */
    @Test
    void tc007_singleRateEurUsdReturns200WithCorrectFields() throws Exception {
        MockHttpServletResponse response = get("/internal/v1/market/fx/rates/EUR/USD");
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus(), "HTTP 状态码应为 200");
        Assertions.assertTrue(body.contains("\"code\":\"0\""), "业务码应为 0（成功）");
        Assertions.assertTrue(body.contains("\"baseCurrency\":\"EUR\""),
                "baseCurrency 应为 EUR");
        Assertions.assertTrue(body.contains("\"quoteCurrency\":\"USD\""),
                "quoteCurrency 应为 USD");
        Assertions.assertTrue(body.contains("1.09000") || body.contains("1.0900"),
                "rate 应含 1.09000，实际 body=" + body);
        Assertions.assertTrue(body.contains("\"sourceLpCode\":\"GODSA\""),
                "sourceLpCode 应为 GODSA");
        Assertions.assertTrue(body.contains("\"sourceSymbol\":\"EURUSD\""),
                "sourceSymbol 应为 EURUSD");
    }

    /**
     * TC-FX-IT-008: GET /internal/v1/market/fx/rates/XXX/YYY → HTTP 200 + ApiResponse code=60010。
     *
     * <p>未配置的货币对查询应返回业务错误码 60010（FX symbol 未配置），
     * HTTP 状态码仍为 200（对齐 FalconX API 统一约定）。
     */
    @Test
    void tc008_singleRateUnknownPairReturns60010() throws Exception {
        MockHttpServletResponse response = get("/internal/v1/market/fx/rates/XXX/YYY");
        String body = response.getContentAsString();

        Assertions.assertEquals(200, response.getStatus(), "HTTP 状态码应为 200（业务错误通过 code 字段区分）");
        Assertions.assertTrue(body.contains("\"code\":\"60010\""),
                "未知货币对应返回业务码 60010，实际 body=" + body);
        Assertions.assertFalse(body.contains("\"code\":\"0\""),
                "不应返回成功码 0");
    }

    /* ===== 辅助方法 ===== */

    private MockHttpServletResponse get(String path) throws Exception {
        MvcResult mvcResult = mockMvc.perform(
                MockMvcRequestBuilders.get(path)
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_USER_ID)
        ).andReturn();
        return mvcResult.getResponse();
    }
}
