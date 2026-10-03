package com.falconx.console;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.market.FxRateView;
import com.falconx.console.repository.AdminPermissionRepository;
import com.falconx.console.security.AdminAuthenticationFilter;
import com.falconx.console.security.AdminTokenBlacklistService;
import com.falconx.console.security.AdminTokenSupport;
import com.falconx.console.security.AdminTokenSupport.IssuedToken;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * STAGE-14E2 Task3：管理端 FX 实时汇率监控透传 controller 集成测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>GET /admin/market/fx/rates（fx:view / superadmin）→ 透传 market 全量 8 FX rate 快照（200）</li>
 *   <li>下游错误（market 60010 / 异常）→ InternalRpcException → 翻 ADMIN_FX_RATE_NOT_FOUND（90940，404）</li>
 *   <li>RBAC：缺 fx:view → 403（90004）</li>
 * </ul>
 *
 * <p>策略与 {@code AdminPlatformConfigEndpointIntegrationTests} 一致：{@link MockitoBean} 替换
 * {@link InternalRpcClient}（即 console → gateway → market 的 RPC 出口）；superadmin 走正路，
 * 普通角色 token + mock 权限集驱动真实 PermissionGuardAspect。
 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = ConsoleServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_console_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380"
        }
)
class AdminMarketFxEndpointIntegrationTests {

    private static final String SUPER_ADMIN = "superadmin";
    private static final String SUPER_PASSWORD = "TestAdmin@1234";

    private static final String FX_RATES_PATH = "/admin/market/fx/rates";
    private static final String RPC_FX_RATES = "/internal/v1/market/fx/rates";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminTokenSupport adminTokenSupport;

    @Autowired
    private AdminTokenBlacklistService adminTokenBlacklistService;

    @MockitoBean
    private InternalRpcClient internalRpcClient;

    @MockitoBean
    private AdminPermissionRepository adminPermissionRepository;

    private MockMvc mockMvc;
    private String superAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        AdminAuthenticationFilter authFilter = new AdminAuthenticationFilter(
                adminTokenSupport, adminTokenBlacklistService, objectMapper);
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(authFilter)
                .build();
        superAdminToken = loginAsSuperAdmin();
    }

    /** GET fx/rates superadmin → 透传 market 全量 8 FX rate 快照（200，8 行）。 */
    @Test
    void shouldReturnEightFxRatesFromMarket() throws Exception {
        doReturn(eightFxRates()).when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get(FX_RATES_PATH)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[0].baseCurrency").value("EUR"))
                .andExpect(jsonPath("$.data[0].quoteCurrency").value("USD"))
                .andExpect(jsonPath("$.data[0].rate").value(1.08));

        org.mockito.Mockito.verify(internalRpcClient)
                .get(eq(RPC_FX_RATES), any(ParameterizedTypeReference.class));
    }

    /** GET fx/rates 普通角色持 fx:view → 同样透传（200）。 */
    @Test
    void shouldAllowFxViewPermission() throws Exception {
        doReturn(eightFxRates()).when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));
        String token = issueLimitedToken(800101L, "ops_fx_viewer", List.of("fx:view"));

        mockMvc.perform(get(FX_RATES_PATH)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.length()").value(8));
    }

    /** GET fx/rates：market 下游错误（InternalRpcException）→ 翻 ADMIN_FX_RATE_NOT_FOUND（90940，404）。 */
    @Test
    void shouldTranslateDownstreamErrorTo90940() throws Exception {
        doThrow(new InternalRpcException(200, "60010", "FX symbol 未配置"))
                .when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get(FX_RATES_PATH)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_FX_RATE_NOT_FOUND.code()));
    }

    /** GET fx/rates 普通角色缺 fx:view → 403（90004）。 */
    @Test
    void shouldDenyWhenMissingFxViewPermission() throws Exception {
        String token = issueLimitedToken(800102L, "ops_no_fx", List.of("OTHER_PERMISSION"));

        mockMvc.perform(get(FX_RATES_PATH)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_PERMISSION_DENIED.code()));

        org.mockito.Mockito.verify(internalRpcClient, org.mockito.Mockito.times(0))
                .get(anyString(), any(ParameterizedTypeReference.class));
    }

    // ---- helpers ----

    private List<FxRateView> eightFxRates() {
        List<FxRateView> rates = new ArrayList<>();
        rates.add(new FxRateView("EUR", "USD", new BigDecimal("1.08"), 1_700_000_000_000L, "GODSA", "EURUSD"));
        rates.add(new FxRateView("GBP", "USD", new BigDecimal("1.27"), 1_700_000_000_000L, "GODSA", "GBPUSD"));
        rates.add(new FxRateView("USD", "JPY", new BigDecimal("150.0"), 1_700_000_000_000L, "GODSA", "USDJPY"));
        rates.add(new FxRateView("USD", "CHF", new BigDecimal("0.88"), 1_700_000_000_000L, "GODSA", "USDCHF"));
        rates.add(new FxRateView("AUD", "USD", new BigDecimal("0.66"), 1_700_000_000_000L, "GODSA", "AUDUSD"));
        rates.add(new FxRateView("USD", "CAD", new BigDecimal("1.36"), 1_700_000_000_000L, "GODSA", "USDCAD"));
        rates.add(new FxRateView("NZD", "USD", new BigDecimal("0.61"), 1_700_000_000_000L, "GODSA", "NZDUSD"));
        rates.add(new FxRateView("USD", "CNH", new BigDecimal("7.20"), 1_700_000_000_000L, "GODSA", "USDCNH"));
        return rates;
    }

    /** 现签发普通角色 access token，并 mock 其权限集合（驱动真实 PermissionGuardAspect）。 */
    private String issueLimitedToken(long userId, String username, List<String> permissions) {
        IssuedToken issued = adminTokenSupport.issueAccessToken(userId, username, List.of("OPS"), false);
        doReturn(permissions).when(adminPermissionRepository).findPermissionCodesByUserId(userId);
        return issued.token();
    }

    private String loginAsSuperAdmin() throws Exception {
        MvcResult login = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}",
                                SUPER_ADMIN, SUPER_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String body = login.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(body);
        String code = root.path("code").asText();
        if (!"0".equals(code)) {
            throw new AssertionError("login failed code=" + code + " body=" + body);
        }
        return root.path("data").path("accessToken").asText();
    }
}
