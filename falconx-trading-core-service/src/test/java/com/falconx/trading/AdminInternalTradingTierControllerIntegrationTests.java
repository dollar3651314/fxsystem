package com.falconx.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.falconx.trading.repository.SymbolLeverageTierRepository;
import com.falconx.trading.security.TradingInternalApiTokenFilter;
import com.falconx.trading.service.LeverageTierResolver;
import com.falconx.trading.service.model.LeverageTier;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * STAGE-14C2 Task 4：tier CRUD internal RPC 集成测试（真 DB falconx_trading_it + 真 filter + 真 resolver）。
 *
 * <p>经 {@code TradingInternalApiTokenFilter}（X-Internal-Token + X-Admin-User-Id）→
 * {@code AdminInternalTradingTierController} → {@code TradingTierAdminApplicationService} → 真 MySQL。
 * 验证：POST 建 → GET 列表含；PUT 改 → 反映；DELETE 软删 → enabled=0；重叠 POST 拒 90932；
 * 改 tier 后 {@code LeverageTierResolver.resolve} 立即反映新值（invalidate 生效）。
 * 用独立测试 symbol，@AfterEach 物理清理。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380"
        }
)
class AdminInternalTradingTierControllerIntegrationTests {

    private static final String TEST_SYMBOL = "ZZZTIERRPC";
    private static final String GROUP = "default";
    private static final String TOKEN = "falconx-internal-dev-token";
    private static final String ADMIN_ID = "9001";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TradingInternalApiTokenFilter tradingInternalApiTokenFilter;

    @Autowired
    private SymbolLeverageTierRepository tierRepository;

    @Autowired
    private LeverageTierResolver leverageTierResolver;

    @Autowired
    private javax.sql.DataSource dataSource;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(tradingInternalApiTokenFilter)
                .build();
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM t_symbol_leverage_tier WHERE symbol = ?")) {
            ps.setString(1, TEST_SYMBOL);
            ps.executeUpdate();
        }
    }

    private String createBody(int tierNo, String lower, String upper, int maxLev, String mm) {
        String upperJson = upper == null ? "null" : "\"" + upper + "\"";
        return """
                {"symbol":"%s","groupCode":"%s","tierNo":%d,"notionalLower":"%s","notionalUpper":%s,"maxLeverage":%d,"mmRate":"%s"}
                """.formatted(TEST_SYMBOL, GROUP, tierNo, lower, upperJson, maxLev, mm);
    }

    @Test
    void post建tier_get列表含_put改反映_delete软删enabled0() throws Exception {
        // POST 建 tier1
        mockMvc.perform(post("/internal/v1/trading/console/tier")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(1, "0", "100000", 100, "0.005000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.tierNo").value(1));

        // GET 列表含
        mockMvc.perform(get("/internal/v1/trading/console/tier")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .param("symbol", TEST_SYMBOL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].symbol").value(TEST_SYMBOL))
                .andExpect(jsonPath("$.data.items[0].maxLeverage").value(100));

        long id = tierRepository.findTiers(TEST_SYMBOL, GROUP).get(0).id();

        // PUT 改 maxLeverage 50 / mmRate 0.01 / upper 200000
        mockMvc.perform(put("/internal/v1/trading/console/tier/" + id)
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tierNo":1,"notionalLower":"0","notionalUpper":"200000","maxLeverage":50,"mmRate":"0.010000"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.maxLeverage").value(50));
        assertThat(tierRepository.findById(id).orElseThrow().maxLeverage()).isEqualTo(50);

        // DELETE 软删 → enabled=0
        mockMvc.perform(delete("/internal/v1/trading/console/tier/" + id)
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        assertThat(tierRepository.findById(id).orElseThrow().enabled()).isFalse();
        assertThat(tierRepository.findTiers(TEST_SYMBOL, GROUP)).isEmpty();
    }

    @Test
    void post重叠区间_拒90932() throws Exception {
        mockMvc.perform(post("/internal/v1/trading/console/tier")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(1, "0", "100000", 100, "0.005000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        // 新建 [50000, 200000) 与 [0,100000) 相交 → 90932
        mockMvc.perform(post("/internal/v1/trading/console/tier")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(2, "50000", "200000", 50, "0.010000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("90932"));
    }

    @Test
    void put改tier后resolver立即反映新值_invalidate生效() throws Exception {
        // 建 tier1 [0, null) maxLev 100
        mockMvc.perform(post("/internal/v1/trading/console/tier")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(1, "0", null, 100, "0.005000")))
                .andExpect(status().isOk());

        // resolve 预热缓存：notional=50000 落 tier1，maxLev=100
        Optional<LeverageTier> before = leverageTierResolver.resolve(
                TEST_SYMBOL, new BigDecimal("50000"), GROUP);
        assertThat(before).isPresent();
        assertThat(before.get().maxLeverage()).isEqualTo(100);

        long id = tierRepository.findTiers(TEST_SYMBOL, GROUP).get(0).id();

        // PUT 改 maxLev 100 → 20（mm 同步到 0.02 满足 CHECK 20×0.02=0.4）
        mockMvc.perform(put("/internal/v1/trading/console/tier/" + id)
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tierNo":1,"notionalLower":"0","notionalUpper":null,"maxLeverage":20,"mmRate":"0.020000"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        // invalidate 生效：resolve 立即拿到新 maxLev=20（无需等 30s TTL）
        Optional<LeverageTier> after = leverageTierResolver.resolve(
                TEST_SYMBOL, new BigDecimal("50000"), GROUP);
        assertThat(after).isPresent();
        assertThat(after.get().maxLeverage()).isEqualTo(20);
    }
}
