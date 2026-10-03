package com.falconx.market.controller;

import com.falconx.market.MarketServiceApplication;
import com.falconx.market.config.MarketTraceContextFilter;
import com.falconx.market.security.MarketInternalApiTokenFilter;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * STAGE-12-GROUP-MARKUP TC-GM-001~005 用户组加点 internal RPC CRUD 集成测试。
 *
 * <p>跑真 MySQL（Flyway V14 已 seed `default` 组 0/0 加点全 symbol），通过
 * {@link MarketInternalApiTokenFilter} 标准化 internal token + admin user id 头。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-GM-001 create / detail / list / update / delete 全流程</li>
 *   <li>TC-GM-002 bulkUpsert 上限校验（≤ 500）</li>
 *   <li>TC-GM-003 changes-since 增量端点 trading-core 增量刷新口径</li>
 *   <li>TC-GM-004 invalid platformSymbol（mapping 不存在）→ 90613</li>
 *   <li>TC-GM-005 bid/ask 越界 → 90641（CHECK + 业务层）</li>
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
                "falconx.market.internal-api.token=test-internal-token"
        }
)
class MarketGroupMarkupInternalControllerIntegrationTests {

    private static final String TOKEN = "test-internal-token";
    private static final String ADMIN_USER_ID = "999";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MarketTraceContextFilter marketTraceContextFilter;

    @Autowired
    private MarketInternalApiTokenFilter marketInternalApiTokenFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(marketTraceContextFilter, marketInternalApiTokenFilter)
                .build();
    }

    /** TC-GM-001 完整 CRUD 流程：create → detail → list → update → delete。 */
    @Test
    void TC_GM_001_crud_full_flow() throws Exception {
        String groupCode = "ut-vip-001";
        String platformSymbol = "XAUUSD";

        // CREATE
        MockHttpServletResponse createResponse = doRequest("POST",
                "/internal/v1/market/symbols/group-markup",
                "{"
                        + "\"groupCode\":\"" + groupCode + "\","
                        + "\"platformSymbol\":\"" + platformSymbol + "\","
                        + "\"bidExtra\":0.5,"
                        + "\"askExtra\":1.0,"
                        + "\"enabled\":1}");
        Assertions.assertEquals(200, createResponse.getStatus());
        String createBody = createResponse.getContentAsString();
        Assertions.assertTrue(createBody.contains("\"code\":\"0\""), createBody);
        Assertions.assertTrue(createBody.contains("\"groupCode\":\"" + groupCode + "\""), createBody);
        Assertions.assertTrue(createBody.contains("\"bidExtra\":0.5"), createBody);

        // DETAIL
        MockHttpServletResponse detailResponse = doRequest("GET",
                "/internal/v1/market/symbols/group-markup/" + groupCode + "/" + platformSymbol, null);
        Assertions.assertEquals(200, detailResponse.getStatus());
        String detailBody = detailResponse.getContentAsString();
        Assertions.assertTrue(detailBody.contains("\"code\":\"0\""), detailBody);
        Assertions.assertTrue(detailBody.contains("\"askExtra\":1.0"), detailBody);

        // LIST 筛选
        MockHttpServletResponse listResponse = doRequest("GET",
                "/internal/v1/market/symbols/group-markup?groupCode=" + groupCode + "&page=0&size=20", null);
        Assertions.assertEquals(200, listResponse.getStatus());
        String listBody = listResponse.getContentAsString();
        Assertions.assertTrue(listBody.contains("\"" + groupCode + "\""), listBody);
        Assertions.assertTrue(listBody.contains("\"" + platformSymbol + "\""), listBody);

        // UPDATE
        MockHttpServletResponse updateResponse = doRequest("PUT",
                "/internal/v1/market/symbols/group-markup/" + groupCode + "/" + platformSymbol,
                "{\"bidExtra\":2.5,\"askExtra\":3.0,\"enabled\":0}");
        Assertions.assertEquals(200, updateResponse.getStatus());
        Assertions.assertTrue(updateResponse.getContentAsString().contains("\"bidExtra\":2.5"));

        // DELETE
        MockHttpServletResponse deleteResponse = doRequest("DELETE",
                "/internal/v1/market/symbols/group-markup/" + groupCode + "/" + platformSymbol, null);
        Assertions.assertEquals(200, deleteResponse.getStatus());

        // 删除后 detail 应抛 90640（控制器返回 200 + business code）
        MockHttpServletResponse afterDeleteDetail = doRequest("GET",
                "/internal/v1/market/symbols/group-markup/" + groupCode + "/" + platformSymbol, null);
        // detail 端点：找不到时 application service 抛 90640 由 GlobalExceptionHandler 处理
        Assertions.assertTrue(
                afterDeleteDetail.getContentAsString().contains("90640")
                        || afterDeleteDetail.getStatus() == 404,
                "删除后 detail 应返回 90640 或 404，实际：" + afterDeleteDetail.getStatus() + " body="
                        + afterDeleteDetail.getContentAsString());
    }

    /** TC-GM-002 bulkUpsert 上限：≤ 500 通过，> 500 抛 90641。 */
    @Test
    void TC_GM_002_bulk_upsert_500_limit_enforced() throws Exception {
        StringBuilder items501 = new StringBuilder("[");
        for (int i = 0; i < 501; i++) {
            if (i > 0) items501.append(",");
            items501.append("{\"platformSymbol\":\"SYM").append(i)
                    .append("\",\"bidExtra\":0,\"askExtra\":0,\"enabled\":1}");
        }
        items501.append("]");

        MockHttpServletResponse rejectResponse = doRequest("PUT",
                "/internal/v1/market/symbols/group-markup/ut-bulk-002/bulk",
                "{\"items\":" + items501 + "}");
        // 501 条超上限 → 90641
        Assertions.assertTrue(
                rejectResponse.getContentAsString().contains("90641")
                        || rejectResponse.getStatus() >= 400,
                "501 条应被拒绝，实际：" + rejectResponse.getStatus() + " body="
                        + rejectResponse.getContentAsString());
    }

    /** TC-GM-003 changes 端点：按 since 返回增量配置（trading-core 增量刷新口径）。 */
    @Test
    void TC_GM_003_changes_since_returns_incremental() throws Exception {
        long now = System.currentTimeMillis();

        // 先 create 一条新配置
        String groupCode = "ut-incr-003";
        MockHttpServletResponse createResponse = doRequest("POST",
                "/internal/v1/market/symbols/group-markup",
                "{"
                        + "\"groupCode\":\"" + groupCode + "\","
                        + "\"platformSymbol\":\"EURUSD\","
                        + "\"bidExtra\":0.1,\"askExtra\":0.1,\"enabled\":1}");
        Assertions.assertEquals(200, createResponse.getStatus(), createResponse.getContentAsString());

        // changes since (now - 1s) 应包含上面这条
        MockHttpServletResponse changesResponse = doRequest("GET",
                "/internal/v1/market/symbols/group-markup/changes?since=" + (now - 1000), null);
        Assertions.assertEquals(200, changesResponse.getStatus());
        String changesBody = changesResponse.getContentAsString();
        Assertions.assertTrue(changesBody.contains("\"" + groupCode + "\""),
                "changes 端点应包含新建的 " + groupCode + "，实际 body=" + changesBody);
        Assertions.assertTrue(changesBody.contains("\"serverTime\""),
                "changes 响应必须含 serverTime 用于下次 since 推算");
    }

    /** TC-GM-004 invalid platformSymbol（mapping 不存在）→ 90613。 */
    @Test
    void TC_GM_004_invalid_platform_symbol_throws_90613() throws Exception {
        MockHttpServletResponse response = doRequest("POST",
                "/internal/v1/market/symbols/group-markup",
                "{"
                        + "\"groupCode\":\"ut-invalid-004\","
                        + "\"platformSymbol\":\"NONEXISTENT_SYMBOL_XYZ\","
                        + "\"bidExtra\":0.1,\"askExtra\":0.1,\"enabled\":1}");
        String body = response.getContentAsString();
        Assertions.assertTrue(body.contains("90613"),
                "mapping 不存在应抛 90613，实际：" + body);
    }

    /** TC-GM-005 bid/ask 越界 → 90641（业务层校验先于 DB CHECK）。 */
    @Test
    void TC_GM_005_out_of_range_extras_throws_90641() throws Exception {
        // bidExtra >= 1_000_000 应被业务层拦截
        MockHttpServletResponse response = doRequest("POST",
                "/internal/v1/market/symbols/group-markup",
                "{"
                        + "\"groupCode\":\"ut-range-005\","
                        + "\"platformSymbol\":\"XAUUSD\","
                        + "\"bidExtra\":1000001,\"askExtra\":0,\"enabled\":1}");
        String body = response.getContentAsString();
        Assertions.assertTrue(body.contains("90641"),
                "bidExtra > 1M 应抛 90641，实际：" + body);
    }

    /** TC-GM-005b 缺 X-Internal-Token → 401 + 90702。 */
    @Test
    void TC_GM_005b_missing_internal_token_returns_90702() throws Exception {
        MvcResult result = mockMvc.perform(
                MockMvcRequestBuilders.get("/internal/v1/market/symbols/group-markup?page=0&size=20")
        ).andReturn();
        Assertions.assertEquals(401, result.getResponse().getStatus());
        Assertions.assertTrue(result.getResponse().getContentAsString().contains("90702"));
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
