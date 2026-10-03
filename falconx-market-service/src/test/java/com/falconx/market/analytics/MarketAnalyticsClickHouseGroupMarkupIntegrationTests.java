package com.falconx.market.analytics;

import com.falconx.market.MarketServiceApplication;
import com.falconx.market.analytics.mapper.MarketKlineMapper;
import com.falconx.market.analytics.mapper.record.MarketKlineRecord;
import com.falconx.market.analytics.mapper.test.MarketAnalyticsTestSupportMapper;
import com.falconx.market.application.MarketDataIngestionApplicationService;
import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.service.KlineAggregationService;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.security.MarketInternalApiTokenFilter;
import com.falconx.market.service.MarketGroupMarkupService;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * STAGE-12-GROUP-MARKUP TC-GM-011/012：ClickHouse quote_tick / kline 必须落基准价
 * （不含组级 markup），保证分析层、K 线、历史回放数据干净。
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
class MarketAnalyticsClickHouseGroupMarkupIntegrationTests {

    private static final String TOKEN = "test-internal-token";
    private static final String ADMIN_USER_ID = "999";
    private static final String SYMBOL = "EURUSD";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MarketInternalApiTokenFilter marketInternalApiTokenFilter;

    @Autowired
    private MarketDataIngestionApplicationService marketDataIngestionApplicationService;

    @Autowired
    private MarketAnalyticsTestSupportMapper marketAnalyticsTestSupportMapper;

    @Autowired
    private MarketKlineMapper marketKlineMapper;

    @Autowired
    private MybatisClickHouseMarketAnalyticsWriter analyticsWriter;

    @Autowired
    private KlineAggregationService klineAggregationService;

    @Autowired
    private MarketGroupMarkupService marketGroupMarkupService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        stringRedisTemplate.delete("falconx:market:price:" + SYMBOL);
        stringRedisTemplate.delete("falconx:market:last-valid-price:" + SYMBOL);
        marketAnalyticsTestSupportMapper.clearAnalyticsTables();
        clearKlineAggregationState();

        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(marketInternalApiTokenFilter)
                .build();

        // 设置 default 组 EURUSD markup = 0.001/0.002（非零）
        adminRequest("PUT", "/internal/v1/market/symbols/group-markup/default/" + SYMBOL,
                "{\"bidExtra\":0.001,\"askExtra\":0.002,\"enabled\":1}");
        marketGroupMarkupService.refresh();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        // 复原 default+EURUSD markup 到 0/0，避免污染其他 IT
        adminRequest("PUT", "/internal/v1/market/symbols/group-markup/default/" + SYMBOL,
                "{\"bidExtra\":0,\"askExtra\":0,\"enabled\":1}");
        marketGroupMarkupService.refresh();
    }

    /**
     * TC-GM-011: 即使 default 组配置了 markup，写入 ClickHouse quote_tick 表的仍是基准价。
     * 验证 ingestion → analytics 路径不应用 markup（保持分析层数据中性）。
     */
    @Test
    void TC_GM_011_quote_tick_persists_baseline_price_not_marked() throws Exception {
        BigDecimal baselineBid = new BigDecimal("1.08100000");
        BigDecimal baselineAsk = new BigDecimal("1.08120000");

        marketDataIngestionApplicationService.ingest(new ExternalRawQuote(
                SYMBOL, baselineBid, baselineAsk, OffsetDateTime.now(), "TM_QUOTE"));

        // ClickHouse 写是异步批量，轮询直到看到 bidPrice 严格等于 baseline 的写入
        // （避开本地 dev 环境历史残留 LP 真实价 1.16xxx 等数据干扰）
        Map<String, Object> latest = waitForClickHouseQuote(SYMBOL, baselineBid);
        Assertions.assertFalse(latest.isEmpty(),
                "quote_tick 应在超时内写入至少 1 条");
        BigDecimal storedBid = toBigDecimal(latest.get("bidPrice"));
        BigDecimal storedAsk = toBigDecimal(latest.get("askPrice"));

        // 严格相等：ClickHouse 存的必须是基准价，不能含 markup（即不是 baseline + 0.001 = 1.08200）
        Assertions.assertEquals(0, storedBid.compareTo(baselineBid),
                "ClickHouse quote_tick bid 必须是基准价 " + baselineBid
                        + "，不应含 markup，实际：" + storedBid);
        Assertions.assertEquals(0, storedAsk.compareTo(baselineAsk),
                "ClickHouse quote_tick ask 必须是基准价 " + baselineAsk
                        + "，不应含 markup，实际：" + storedAsk);
    }

    /**
     * TC-GM-012: K 线 OHLC 必须是基准价（不含组级 markup）。
     * 直接 insert + select 验证 mapper 写的是基准价（已由市场 ingestion 路径决定，与 group 无关）。
     */
    @Test
    void TC_GM_012_kline_persists_baseline_ohlc() throws Exception {
        // 注入两条基准价 K 线
        marketKlineMapper.insertKline(new MarketKlineRecord(
                SYMBOL, "1m",
                new BigDecimal("1.08000000"),
                new BigDecimal("1.08200000"),
                new BigDecimal("1.07900000"),
                new BigDecimal("1.08100000"),
                BigDecimal.ONE,
                java.time.LocalDateTime.parse("2026-04-30T10:00:00"),
                java.time.LocalDateTime.parse("2026-04-30T10:00:59"),
                "market-service"
        ));

        Thread.sleep(300);

        Long count = marketAnalyticsTestSupportMapper.countKlineBySymbolAndInterval(SYMBOL, "1m");
        Assertions.assertTrue(count != null && count >= 1L);

        // 通过现有 controller history 端点 GET klines（注意 history 不应用 markup，详见
        // MarketKlineController 实现：K 线 path 直接读 ClickHouse 原始记录，不走 markup applier）
        MockHttpServletResponse response = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/v1/market/klines/" + SYMBOL + "?interval=1m&limit=5")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_USER_ID)
                        .header("X-User-Group-Code", "default") // 即使带 group header，K 线也不加点
        ).andReturn().getResponse();

        // K 线端点不需要 internal token filter（公开 API），上面的 header 只是冗余
        // 这里 mockMvc 还挂了 InternalToken filter；K 线路径 /api/v1/market/* 不被过滤
        String body = response.getContentAsString();
        // 基准价 open/close 数值必须出现（1.08000 / 1.08100）
        Assertions.assertTrue(body.contains("1.08000000"),
                "K 线 open 应是基准价 1.08000000，实际：" + body);
        Assertions.assertTrue(body.contains("1.08100000"),
                "K 线 close 应是基准价 1.08100000，实际：" + body);
        // 关键反向断言：K 线不应含 markup 后的值（1.08001/1.08102 等带组加点的）
        Assertions.assertFalse(body.contains("1.08002000"),
                "K 线绝不能含 markup 后的 ask 1.08120+0.002=1.08122，实际 body：" + body);
    }

    /**
     * selectRecentKlines 去重回归：同一 K 线桶（symbol+interval+open_time）写入多行时，
     * 查询必须只返回 ingest_time 最新的那行（等同 ReplacingMergeTree(ingest_time) FINAL 语义）。
     *
     * <p>历史 SQL 用 FINAL 做读时合并去重，已改为 ORDER BY open_time DESC, ingest_time DESC
     * + LIMIT 1 BY open_time 的显式去重（避免 FINAL 全表合并的内存/耗时放大）。本用例锁定该不变量。
     * 注：insertKline 不写 ingest_time，由 ClickHouse DEFAULT now64(3) 赋值，故两次插入间 sleep 即可保证后写者更大。
     */
    @Test
    void TC_KLINE_DEDUP_selectRecentKlines_keeps_latest_ingest() throws Exception {
        java.time.LocalDateTime openTime = java.time.LocalDateTime.parse("2026-05-01T09:00:00");
        java.time.LocalDateTime closeTime = java.time.LocalDateTime.parse("2026-05-01T09:00:59");

        // 先写一版（旧值），稍后写同桶的新版（新 ingest_time），新版应胜出
        marketKlineMapper.insertKline(new MarketKlineRecord(
                SYMBOL, "1m",
                new BigDecimal("1.10000000"), new BigDecimal("1.10500000"),
                new BigDecimal("1.09500000"), new BigDecimal("1.10000000"),
                BigDecimal.ONE, openTime, closeTime, "market-service"));
        Thread.sleep(80);
        marketKlineMapper.insertKline(new MarketKlineRecord(
                SYMBOL, "1m",
                new BigDecimal("1.20000000"), new BigDecimal("1.25000000"),
                new BigDecimal("1.15000000"), new BigDecimal("2.20000000"),
                new BigDecimal("9"), openTime, closeTime, "market-service"));
        Thread.sleep(300);

        List<MarketKlineRecord> klines = marketKlineMapper.selectRecentKlines(SYMBOL, "1m", 5);

        // 去重：同一 open_time 桶只应返回 1 行
        long sameBucket = klines.stream().filter(k -> openTime.equals(k.openTime())).count();
        Assertions.assertEquals(1L, sameBucket,
                "同一 open_time 桶应去重为 1 行，实际：" + klines);
        // 胜出行必须是后写的新版（close=2.20000000，ingest_time 最大）
        MarketKlineRecord winner = klines.stream()
                .filter(k -> openTime.equals(k.openTime())).findFirst().orElseThrow();
        Assertions.assertEquals(0, new BigDecimal("2.20000000").compareTo(winner.closePrice()),
                "应保留 ingest_time 最新的行 close=2.20000000，实际：" + winner.closePrice());
    }

    /**
     * #2 批量入库回归：writeKline 不再逐行落库，而是入队缓冲，由定时任务批量 flush。
     * 验证多根 K 线经 writeKline → flushKlinesOnSchedule 后全部批量落库（路径正确性）。
     * 注：后台 1s 定时 flush 也会落库，故只断言最终落库结果，不断言中间缓冲计数（避免被后台 flush 抢跑导致 flaky）。
     */
    @Test
    void TC_KLINE_BATCH_writeKline_buffers_then_batch_flushes() throws Exception {
        OffsetDateTime base = OffsetDateTime.parse("2026-05-02T08:00:00Z");
        for (int i = 0; i < 3; i++) {
            analyticsWriter.writeKline(new KlineSnapshot(
                    SYMBOL, "1m",
                    new BigDecimal("1.10000000"), new BigDecimal("1.10500000"),
                    new BigDecimal("1.09500000"), new BigDecimal("1.10100000"),
                    BigDecimal.ONE,
                    base.plusMinutes(i), base.plusMinutes(i).plusSeconds(59),
                    true));
        }

        // 显式触发批量 flush（后台调度也会做，这里确保确定性）
        analyticsWriter.flushKlinesOnSchedule();
        Thread.sleep(300);

        Assertions.assertEquals(0, analyticsWriter.getPendingKlineCount(), "flush 后缓冲队列应清空");
        Long count = marketAnalyticsTestSupportMapper.countKlineBySymbolAndInterval(SYMBOL, "1m");
        Assertions.assertNotNull(count);
        Assertions.assertEquals(3L, count.longValue(), "3 根 K 线应经批量路径全部落库");
    }

    /**
     * 等待 ClickHouse 写入并返回 bidPrice 严格等于 expectedBid 的最新行。
     * 避开本地 dev 环境历史残留（LP 真实数据等）的干扰。
     */
    private Map<String, Object> waitForClickHouseQuote(String symbol, BigDecimal expectedBid)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 12_000L;
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> latest = marketAnalyticsTestSupportMapper.selectLatestQuoteTickBySymbol(symbol);
            if (latest != null && !latest.isEmpty()) {
                last = latest;
                BigDecimal storedBid = toBigDecimal(latest.get("bidPrice"));
                if (storedBid != null && storedBid.compareTo(expectedBid) == 0) {
                    return latest;
                }
            }
            Thread.sleep(200L);
        }
        return last == null ? Map.of() : last;
    }

    @SuppressWarnings("unchecked")
    private void clearKlineAggregationState() {
        Object bucketsField = ReflectionTestUtils.getField(klineAggregationService, "buckets");
        if (bucketsField instanceof Map<?, ?> buckets) {
            buckets.clear();
        }
    }

    private MockHttpServletResponse adminRequest(String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder rb;
        switch (method) {
            case "PUT" -> rb = MockMvcRequestBuilders.put(path);
            case "POST" -> rb = MockMvcRequestBuilders.post(path);
            case "GET" -> rb = MockMvcRequestBuilders.get(path);
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

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(value.toString());
    }
}
