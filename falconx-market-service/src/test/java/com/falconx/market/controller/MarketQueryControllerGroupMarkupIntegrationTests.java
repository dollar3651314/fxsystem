package com.falconx.market.controller;

import com.falconx.market.MarketServiceApplication;
import com.falconx.market.analytics.mapper.MarketQuoteTickMapper;
import com.falconx.market.analytics.mapper.record.MarketQuoteTickRecord;
import com.falconx.market.analytics.mapper.test.MarketAnalyticsTestSupportMapper;
import com.falconx.market.application.MarketDataIngestionApplicationService;
import com.falconx.market.config.MarketTraceContextFilter;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.security.MarketInternalApiTokenFilter;
import com.falconx.market.service.MarketGroupMarkupService;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * STAGE-12-GROUP-MARKUP TC-GM-008~010：market REST 出参按 X-User-Group-Code 加点验证。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-GM-008 GET /api/v1/market/quotes/{symbol}：X-User-Group-Code=vip 时 bid/ask 含加点</li>
 *   <li>TC-GM-009 GET /api/v1/market/quotes/{symbol}/history：所有 tick 都加点</li>
 *   <li>TC-GM-010 GET /api/v1/market/symbols：quote 字段含组加点</li>
 *   <li>默认组无配置：基准价透传，不加点</li>
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
                "falconx.market.internal-api.token=test-internal-token",
                "falconx.market.stale.max-age=30s"
        }
)
class MarketQueryControllerGroupMarkupIntegrationTests {

    private static final String TOKEN = "test-internal-token";
    private static final String ADMIN_USER_ID = "999";
    private static final String SYMBOL = "EURUSD";
    // 复用 V14 已 seed 的 default 组（每个 symbol 都有 0/0 加点 + 在 group-visibility 中可见），
    // 通过 update 把 default 组的 EURUSD 加点改成非零，验证 markup 应用链路。
    // 不新建组避免还需先建可见性记录。
    private static final String VIP_GROUP = "default";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MarketTraceContextFilter marketTraceContextFilter;

    @Autowired
    private MarketInternalApiTokenFilter marketInternalApiTokenFilter;

    @Autowired
    private MarketDataIngestionApplicationService marketDataIngestionApplicationService;

    @Autowired
    private MarketGroupMarkupService marketGroupMarkupService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MarketAnalyticsTestSupportMapper marketAnalyticsTestSupportMapper;

    @Autowired
    private MarketQuoteTickMapper marketQuoteTickMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        stringRedisTemplate.delete("falconx:market:price:" + SYMBOL);
        stringRedisTemplate.delete("falconx:market:last-valid-price:" + SYMBOL);
        marketAnalyticsTestSupportMapper.clearAnalyticsTables();

        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(marketTraceContextFilter, marketInternalApiTokenFilter)
                .build();

        // 准备 default 组对 EURUSD 的加点配置（bid +0.001 / ask +0.002）
        // V14 已 seed default+EURUSD 为 0/0，所以 UPDATE 即可。
        // 调 service.refresh() 强制重建内存快照
        doRequest("PUT", "/internal/v1/market/symbols/group-markup/" + VIP_GROUP + "/" + SYMBOL,
                "{\"bidExtra\":0.001,\"askExtra\":0.002,\"enabled\":1}");
        marketGroupMarkupService.refresh();
    }

    /** 用例间清理：把 default+EURUSD 加点恢复到 0/0，避免污染其他 IT。 */
    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        doRequest("PUT", "/internal/v1/market/symbols/group-markup/" + VIP_GROUP + "/" + SYMBOL,
                "{\"bidExtra\":0,\"askExtra\":0,\"enabled\":1}");
        marketGroupMarkupService.refresh();
    }

    /** TC-GM-008 GET /quotes/{symbol}：X-User-Group-Code=vip 时 bid/ask 含加点。 */
    @Test
    void TC_GM_008_rest_quote_endpoint_applies_markup_for_vip_group() throws Exception {
        ingest("1.08100000", "1.08120000");

        MockHttpServletResponse response = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/market/quotes/" + SYMBOL)
                        .header("X-User-Group-Code", VIP_GROUP)
        ).andReturn().getResponse();

        String body = response.getContentAsString();
        Assertions.assertEquals(200, response.getStatus(), body);
        Assertions.assertTrue(body.contains("\"code\":\"0\""), body);
        // 1.08100 + 0.001 = 1.08200
        Assertions.assertTrue(body.contains("\"bid\":1.08200000"),
                "VIP bid 应为基准 1.08100 + 0.001 = 1.08200，实际：" + body);
        // 1.08120 + 0.002 = 1.08320
        Assertions.assertTrue(body.contains("\"ask\":1.08320000"),
                "VIP ask 应为基准 1.08120 + 0.002 = 1.08320，实际：" + body);
    }

    /** TC-GM-008b 无 X-User-Group-Code header → 走 default 组，但本测试用 default + 非零 markup → 应加点。 */
    @Test
    void TC_GM_008b_rest_quote_without_header_falls_back_to_default_group() throws Exception {
        ingest("1.08100000", "1.08120000");

        // 不传 X-User-Group-Code → service 内部归一化到 "default"，加点生效
        MockHttpServletResponse response = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/market/quotes/" + SYMBOL)
        ).andReturn().getResponse();

        String body = response.getContentAsString();
        Assertions.assertEquals(200, response.getStatus(), body);
        Assertions.assertTrue(body.contains("\"bid\":1.08200000"),
                "缺 header 时也走 default 组的加点，实际：" + body);
    }

    /**
     * TC-GM-009 GET /quotes/{symbol}/history：所有 tick 全部按组加点（差分验证）。
     *
     * <p>策略：先在 0 加点下取 history baseline，再切到 0.001/0.002 加点下取 history，
     * 同一条 tick 的 bid/ask 差应严格等于 0.001/0.002。
     * 这样跨过 ClickHouse 中既存的 LP 历史数据，验证 markup applier 在 history 路径上生效。
     */
    @Test
    void TC_GM_009_rest_history_endpoint_applies_markup_to_all_items() throws Exception {
        LocalDateTime eventTime = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5);
        marketQuoteTickMapper.insertQuoteTick(new MarketQuoteTickRecord(
                SYMBOL,
                "TM_QUOTE",
                new BigDecimal("1.08100000"),
                new BigDecimal("1.08120000"),
                new BigDecimal("1.08110000"),
                new BigDecimal("1.08110000"),
                eventTime
        ));

        // (1) 先把 default 组重置为 0/0，取 baseline history
        doRequest("PUT", "/internal/v1/market/symbols/group-markup/" + VIP_GROUP + "/" + SYMBOL,
                "{\"bidExtra\":0,\"askExtra\":0,\"enabled\":1}");
        marketGroupMarkupService.refresh();

        String baselineBody = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/market/quotes/" + SYMBOL + "/history?limit=3")
                        .header("X-User-Group-Code", VIP_GROUP)
        ).andReturn().getResponse().getContentAsString();

        // (2) 切到 0.001/0.002 加点，取加点后 history
        doRequest("PUT", "/internal/v1/market/symbols/group-markup/" + VIP_GROUP + "/" + SYMBOL,
                "{\"bidExtra\":0.001,\"askExtra\":0.002,\"enabled\":1}");
        marketGroupMarkupService.refresh();

        String markedBody = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/market/quotes/" + SYMBOL + "/history?limit=3")
                        .header("X-User-Group-Code", VIP_GROUP)
        ).andReturn().getResponse().getContentAsString();

        // 抽取第一条 bid/ask 数值（简易正则）
        BigDecimal baselineBid = extractFirst(baselineBody, "\"bid\":");
        BigDecimal markedBid = extractFirst(markedBody, "\"bid\":");
        BigDecimal baselineAsk = extractFirst(baselineBody, "\"ask\":");
        BigDecimal markedAsk = extractFirst(markedBody, "\"ask\":");
        Assertions.assertNotNull(baselineBid, "baseline body 应含 bid 字段：" + baselineBody);
        Assertions.assertNotNull(markedBid, "marked body 应含 bid 字段：" + markedBody);

        Assertions.assertEquals(0, markedBid.subtract(baselineBid).compareTo(new BigDecimal("0.00100000")),
                "history 端点首项 bid 加点差应严格等于 0.001，baseline=" + baselineBid + " marked=" + markedBid);
        Assertions.assertEquals(0, markedAsk.subtract(baselineAsk).compareTo(new BigDecimal("0.00200000")),
                "history 端点首项 ask 加点差应严格等于 0.002，baseline=" + baselineAsk + " marked=" + markedAsk);
    }

    private static BigDecimal extractFirst(String body, String key) {
        int idx = body.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '.')) {
            end++;
        }
        if (end == start) return null;
        return new BigDecimal(body.substring(start, end));
    }

    /** TC-GM-010 GET /symbols：symbol 列表 quote 字段含组加点。 */
    @Test
    void TC_GM_010_rest_symbols_list_applies_markup_to_quote_fields() throws Exception {
        ingest("1.08100000", "1.08120000");

        MockHttpServletResponse response = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/market/symbols")
                        .header("X-User-Group-Code", VIP_GROUP)
        ).andReturn().getResponse();

        String body = response.getContentAsString();
        Assertions.assertEquals(200, response.getStatus(), body);
        // EURUSD 出现 + 加点价（1.08200 / 1.08320）
        Assertions.assertTrue(body.contains("\"symbol\":\"" + SYMBOL + "\""), body);
        Assertions.assertTrue(body.contains("\"bid\":1.08200000")
                        && body.contains("\"ask\":1.08320000"),
                "VIP 组 symbol 列表中 quote 字段应含加点，实际：" + body);
    }

    private void ingest(String bid, String ask) {
        marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                SYMBOL,
                new BigDecimal(bid),
                new BigDecimal(ask),
                OffsetDateTime.now(),
                "TM_QUOTE"
        ));
    }

    private MockHttpServletResponse doRequest(String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder rb;
        switch (method) {
            case "GET" -> rb = MockMvcRequestBuilders.get(path);
            case "POST" -> rb = MockMvcRequestBuilders.post(path);
            case "PUT" -> rb = MockMvcRequestBuilders.put(path);
            case "DELETE" -> rb = MockMvcRequestBuilders.delete(path);
            default -> throw new IllegalArgumentException("unsupported method " + method);
        }
        rb.header("X-Internal-Token", TOKEN)
                .header("X-Admin-User-Id", ADMIN_USER_ID);
        if (body != null) {
            rb.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(rb).andReturn().getResponse();
    }
}
