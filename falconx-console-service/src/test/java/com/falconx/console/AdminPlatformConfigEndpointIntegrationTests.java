package com.falconx.console;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.repository.AdminPermissionRepository;
import com.falconx.console.security.AdminAuthenticationFilter;
import com.falconx.console.security.AdminTokenBlacklistService;
import com.falconx.console.security.AdminTokenSupport;
import com.falconx.console.security.AdminTokenSupport.IssuedToken;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * STAGE-14D3b Task2：管理端平台风控配置（冷静期 / StopOut & MarginCall 阈值）透传 controller 集成测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>GET /admin/trading/margin-mode-config（margin-mode-config:view）→ 取 coolingPeriodSeconds</li>
 *   <li>PUT /admin/trading/margin-mode-config（margin-mode-config:edit）→ 透传 cooling-period + verifyReason</li>
 *   <li>GET /admin/trading/risk-thresholds（risk-threshold:view）→ 取 stopOutLevel/marginCallLevel</li>
 *   <li>PUT /admin/trading/risk-thresholds（risk-threshold:edit）→ 透传 risk-thresholds</li>
 *   <li>错误翻译：trading 99004 → ADMIN_RISK_THRESHOLD_INVALID（90952）</li>
 *   <li>RBAC：无对应权限 → 403（90004）</li>
 * </ul>
 *
 * <p>策略与 {@code AdminTierEndpointIntegrationTests} 一致：{@link MockitoBean} 替换
 * {@link InternalRpcClient}；superadmin 走正路，普通角色 token + mock 权限集驱动真实 PermissionGuardAspect。
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
class AdminPlatformConfigEndpointIntegrationTests {

    private static final String SUPER_ADMIN = "superadmin";
    private static final String SUPER_PASSWORD = "TestAdmin@1234";

    private static final String MARGIN_MODE_CONFIG_PATH = "/admin/trading/margin-mode-config";
    private static final String RISK_THRESHOLDS_PATH = "/admin/trading/risk-thresholds";

    private static final String RPC_COOLING_PERIOD = "/internal/v1/trading/console/config/cooling-period";
    private static final String RPC_RISK_THRESHOLDS = "/internal/v1/trading/console/config/risk-thresholds";

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

    /** GET margin-mode-config superadmin → 取 platform-risk 的 coolingPeriodSeconds=300。 */
    @Test
    void shouldReturnCoolingPeriodFromPlatformRisk() throws Exception {
        Map<String, Object> platformRisk = new LinkedHashMap<>();
        platformRisk.put("coolingPeriodSeconds", 300);
        platformRisk.put("stopOutLevel", new BigDecimal("0.10"));
        platformRisk.put("marginCallLevel", new BigDecimal("1.00"));
        doReturn(platformRisk).when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get(MARGIN_MODE_CONFIG_PATH)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.coolingPeriodSeconds").value(300));

        verify(internalRpcClient).get(
                eq("/internal/v1/trading/console/config/platform-risk"),
                any(ParameterizedTypeReference.class));
    }

    /** PUT margin-mode-config（带 reason）→ 透传 cooling-period RPC。 */
    @Test
    void shouldForwardCoolingPeriodUpdate() throws Exception {
        doReturn(null).when(internalRpcClient).put(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(put(MARGIN_MODE_CONFIG_PATH)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coolingPeriodSeconds\":600,\"reason\":\"调整冷静期\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        verify(internalRpcClient).put(eq(RPC_COOLING_PERIOD), any(), any(ParameterizedTypeReference.class));
    }

    /**
     * PUT margin-mode-config 缺 coolingPeriodSeconds（仅 reason）→ @Valid @NotNull 在 service 前拒 400
     * （MethodArgumentNotValidException），防 Map.of 审计快照 NPE→500，不调 RPC。
     */
    @Test
    void shouldRejectCoolingPeriodUpdateWhenFieldNull() throws Exception {
        mockMvc.perform(put(MARGIN_MODE_CONFIG_PATH)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());

        verify(internalRpcClient, times(0)).put(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    /** PUT margin-mode-config 无 reason → 400（verifyReason，90656），不调 RPC。 */
    @Test
    void shouldRejectCoolingPeriodUpdateWithoutReason() throws Exception {
        mockMvc.perform(put(MARGIN_MODE_CONFIG_PATH)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coolingPeriodSeconds\":600}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_TRADING_REASON_REQUIRED.code()));

        verify(internalRpcClient, times(0)).put(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    /** PUT margin-mode-config 普通角色无 margin-mode-config:edit → 403。 */
    @Test
    void shouldDenyCoolingPeriodUpdateWhenMissingEditPermission() throws Exception {
        String token = issueLimitedToken(700101L, "ops_no_margin_edit", List.of("OTHER_PERMISSION"));

        mockMvc.perform(put(MARGIN_MODE_CONFIG_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coolingPeriodSeconds\":600,\"reason\":\"无权限\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_PERMISSION_DENIED.code()));

        verify(internalRpcClient, times(0)).put(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    /** GET risk-thresholds superadmin → 取 stopOutLevel/marginCallLevel。 */
    @Test
    void shouldReturnRiskThresholdsFromPlatformRisk() throws Exception {
        Map<String, Object> platformRisk = new LinkedHashMap<>();
        platformRisk.put("coolingPeriodSeconds", 300);
        platformRisk.put("stopOutLevel", new BigDecimal("0.10"));
        platformRisk.put("marginCallLevel", new BigDecimal("1.20"));
        doReturn(platformRisk).when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get(RISK_THRESHOLDS_PATH)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.stopOutLevel").value(0.10))
                .andExpect(jsonPath("$.data.marginCallLevel").value(1.20));
    }

    /** PUT risk-thresholds：trading 99004 → 翻 ADMIN_RISK_THRESHOLD_INVALID（90952）。 */
    @Test
    void shouldTranslateRiskThresholdValidationError() throws Exception {
        doThrow(new InternalRpcException(400, "99004", "risk threshold out of range"))
                .when(internalRpcClient).put(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(put(RISK_THRESHOLDS_PATH)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stopOutLevel\":0.01,\"marginCallLevel\":1.00,\"reason\":\"越界\"}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_RISK_THRESHOLD_INVALID.code()));

        verify(internalRpcClient).put(eq(RPC_RISK_THRESHOLDS), any(), any(ParameterizedTypeReference.class));
    }

    /** PUT risk-thresholds 普通角色无 risk-threshold:edit → 403。 */
    @Test
    void shouldDenyRiskThresholdUpdateWhenMissingEditPermission() throws Exception {
        String token = issueLimitedToken(700102L, "ops_no_risk_edit", List.of("risk-threshold:view"));

        mockMvc.perform(put(RISK_THRESHOLDS_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stopOutLevel\":0.10,\"marginCallLevel\":1.00,\"reason\":\"无权限\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_PERMISSION_DENIED.code()));

        verify(internalRpcClient, times(0)).put(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    /** PUT margin-mode-config：trading 99004 → 翻 ADMIN_MARGIN_MODE_CONFIG_INVALID（90950）。 */
    @Test
    void shouldTranslateCoolingPeriodValidationErrorTo90950() throws Exception {
        doThrow(new InternalRpcException(400, "99004", "cooling period out of range"))
                .when(internalRpcClient).put(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(put(MARGIN_MODE_CONFIG_PATH)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coolingPeriodSeconds\":99999,\"reason\":\"越界\"}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_MARGIN_MODE_CONFIG_INVALID.code()));

        verify(internalRpcClient).put(eq(RPC_COOLING_PERIOD), any(), any(ParameterizedTypeReference.class));
    }

    /** PUT risk-thresholds 无 reason → 400（verifyReason，90656），不调 RPC。 */
    @Test
    void shouldRejectRiskThresholdUpdateWithoutReason() throws Exception {
        mockMvc.perform(put(RISK_THRESHOLDS_PATH)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stopOutLevel\":0.10,\"marginCallLevel\":1.00}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_TRADING_REASON_REQUIRED.code()));

        verify(internalRpcClient, times(0)).put(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    // ---- helpers ----

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
