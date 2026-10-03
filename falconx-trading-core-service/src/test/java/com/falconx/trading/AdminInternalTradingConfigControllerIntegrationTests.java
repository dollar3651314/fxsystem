package com.falconx.trading;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.falconx.trading.security.TradingInternalApiTokenFilter;
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
 * STAGE-14D3a Task 4：平台风控配置 internal RPC 集成测试（真 DB falconx_trading_it + 真 filter）。
 *
 * <p>经 {@code TradingInternalApiTokenFilter}（X-Internal-Token + X-Admin-User-Id）→
 * {@code AdminInternalTradingConfigController} → {@code TradingPlatformConfigApplicationService} → 真 MySQL
 * 平台行（symbol IS NULL）。验证：PUT 写 → GET 反映；Bean Validation 越界 → 400 INVALID_REQUEST_PAYLOAD(99004)。
 *
 * <p>本测试改的是共享平台行，{@code @BeforeEach} 快照原值、{@code @AfterEach} 复位，避免污染其余 IT。
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
class AdminInternalTradingConfigControllerIntegrationTests {

    private static final String TOKEN = "falconx-internal-dev-token";
    private static final String ADMIN_ID = "9001";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TradingInternalApiTokenFilter tradingInternalApiTokenFilter;

    @Autowired
    private javax.sql.DataSource dataSource;

    private MockMvc mockMvc;

    private int origCooling;
    private java.math.BigDecimal origStopOut;
    private java.math.BigDecimal origMarginCall;

    private boolean origFx2Open;
    private boolean origFx2Close;
    private boolean origFx2Liq;

    @BeforeEach
    void setUp() throws Exception {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(tradingInternalApiTokenFilter)
                .build();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT cooling_period_seconds, stop_out_level, margin_call_level "
                             + "FROM t_risk_config WHERE symbol IS NULL");
             var rs = ps.executeQuery()) {
            rs.next();
            this.origCooling = rs.getInt("cooling_period_seconds");
            this.origStopOut = rs.getBigDecimal("stop_out_level");
            this.origMarginCall = rs.getBigDecimal("margin_call_level");
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT allow_open, allow_close, allow_liquidation "
                             + "FROM t_fx_pause_behavior WHERE category = 2");
             var rs = ps.executeQuery()) {
            rs.next();
            this.origFx2Open = rs.getBoolean("allow_open");
            this.origFx2Close = rs.getBoolean("allow_close");
            this.origFx2Liq = rs.getBoolean("allow_liquidation");
        }
    }

    @AfterEach
    void restore() throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE t_risk_config SET cooling_period_seconds = ?, stop_out_level = ?, "
                             + "margin_call_level = ? WHERE symbol IS NULL")) {
            ps.setInt(1, origCooling);
            ps.setBigDecimal(2, origStopOut);
            ps.setBigDecimal(3, origMarginCall);
            ps.executeUpdate();
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE t_fx_pause_behavior SET allow_open = ?, allow_close = ?, "
                             + "allow_liquidation = ? WHERE category = 2")) {
            ps.setBoolean(1, origFx2Open);
            ps.setBoolean(2, origFx2Close);
            ps.setBoolean(3, origFx2Liq);
            ps.executeUpdate();
        }
    }

    @Test
    void putCoolingPeriod_thenGetReflects() throws Exception {
        mockMvc.perform(put("/internal/v1/trading/console/config/cooling-period")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coolingPeriodSeconds\":600}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(get("/internal/v1/trading/console/config/platform-risk")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.coolingPeriodSeconds").value(600));
    }

    @Test
    void putCoolingPeriod_belowMin_400() throws Exception {
        mockMvc.perform(put("/internal/v1/trading/console/config/cooling-period")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coolingPeriodSeconds\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("99004"));
    }

    @Test
    void putCoolingPeriod_aboveMax_400() throws Exception {
        mockMvc.perform(put("/internal/v1/trading/console/config/cooling-period")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coolingPeriodSeconds\":700000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("99004"));
    }

    @Test
    void putRiskThresholds_thenGetReflects() throws Exception {
        mockMvc.perform(put("/internal/v1/trading/console/config/risk-thresholds")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stopOutLevel\":0.25,\"marginCallLevel\":1.20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(get("/internal/v1/trading/console/config/platform-risk")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.stopOutLevel").value(0.25))
                .andExpect(jsonPath("$.data.marginCallLevel").value(1.20));
    }

    @Test
    void putRiskThresholds_stopOutBelowMin_400() throws Exception {
        mockMvc.perform(put("/internal/v1/trading/console/config/risk-thresholds")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stopOutLevel\":0.01,\"marginCallLevel\":1.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("99004"));
    }

    @Test
    void getFxPauseBehavior_returns8Rows() throws Exception {
        mockMvc.perform(get("/internal/v1/trading/console/config/fx-pause-behavior")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[0].category").value(1))
                .andExpect(jsonPath("$.data[7].category").value(8));
    }

    @Test
    void putFxPauseBehavior_thenGetReflects() throws Exception {
        mockMvc.perform(put("/internal/v1/trading/console/config/fx-pause-behavior/2")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowOpen\":true,\"allowClose\":true,\"allowLiquidation\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(get("/internal/v1/trading/console/config/fx-pause-behavior")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[?(@.category == 2)].allowOpen").value(true));
    }

    @Test
    void putFxPauseBehavior_categoryOutOfRange_400() throws Exception {
        mockMvc.perform(put("/internal/v1/trading/console/config/fx-pause-behavior/9")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowOpen\":true,\"allowClose\":true,\"allowLiquidation\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("99004"));
    }

    @Test
    void putFxPauseBehavior_missingField_400() throws Exception {
        mockMvc.perform(put("/internal/v1/trading/console/config/fx-pause-behavior/1")
                        .header("X-Internal-Token", TOKEN)
                        .header("X-Admin-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowClose\":true,\"allowLiquidation\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("99004"));
    }
}
