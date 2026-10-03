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

import com.falconx.console.api.FxPauseBehaviorView;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.repository.AdminPermissionRepository;
import com.falconx.console.security.AdminAuthenticationFilter;
import com.falconx.console.security.AdminTokenBlacklistService;
import com.falconx.console.security.AdminTokenSupport;
import com.falconx.console.security.AdminTokenSupport.IssuedToken;
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
 * STAGE-14D3b Task3：管理端 FX_PAUSED 行为（按商品类目 1-8）透传 controller 集成测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>GET /admin/trading/fx-pause-behavior（fx:pause-behavior:view）→ 8 行类目</li>
 *   <li>PUT /admin/trading/fx-pause-behavior/{category}（fx:pause-behavior:edit）→ 透传 + verifyReason</li>
 *   <li>PUT 无 reason → 拒（90656 ADMIN_TRADING_REASON_REQUIRED），不调 RPC</li>
 *   <li>错误翻译：trading 99004 → ADMIN_FX_PAUSE_BEHAVIOR_INVALID（90951）</li>
 *   <li>RBAC：无对应权限 → 403（90004）</li>
 * </ul>
 *
 * <p>策略与 Task2 {@code AdminPlatformConfigEndpointIntegrationTests} 一致：{@link MockitoBean} 替换
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
class AdminFxPauseBehaviorEndpointIntegrationTests {

    private static final String SUPER_ADMIN = "superadmin";
    private static final String SUPER_PASSWORD = "TestAdmin@1234";

    private static final String FX_PAUSE_BEHAVIOR_PATH = "/admin/trading/fx-pause-behavior";

    private static final String RPC_FX_PAUSE_BEHAVIOR = "/internal/v1/trading/console/fx-pause-behavior";

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

    /** GET fx-pause-behavior superadmin → 8 行类目（category 1-8）。 */
    @Test
    void shouldReturnEightCategoryBehaviors() throws Exception {
        List<FxPauseBehaviorView> rows = new ArrayList<>();
        for (int category = 1; category <= 8; category++) {
            rows.add(new FxPauseBehaviorView(category, "category-" + category, true, true, false));
        }
        doReturn(rows).when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get(FX_PAUSE_BEHAVIOR_PATH)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[0].category").value(1))
                .andExpect(jsonPath("$.data[7].category").value(8))
                .andExpect(jsonPath("$.data[0].allowOpen").value(true))
                .andExpect(jsonPath("$.data[0].allowLiquidation").value(false));

        verify(internalRpcClient).get(eq(RPC_FX_PAUSE_BEHAVIOR), any(ParameterizedTypeReference.class));
    }

    /** PUT fx-pause-behavior/2（带 reason）→ 透传 .../fx-pause-behavior/2 RPC。 */
    @Test
    void shouldForwardBehaviorUpdate() throws Exception {
        doReturn(null).when(internalRpcClient).put(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(put(FX_PAUSE_BEHAVIOR_PATH + "/2")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowOpen\":false,\"allowClose\":true,\"allowLiquidation\":true,\"reason\":\"暂停外汇类目开仓\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        verify(internalRpcClient).put(eq(RPC_FX_PAUSE_BEHAVIOR + "/2"), any(), any(ParameterizedTypeReference.class));
    }

    /**
     * PUT fx-pause-behavior/2 allowOpen=null → @Valid @NotNull 在 service 前拒 400
     * （MethodArgumentNotValidException），防 Map.of 审计快照 NPE→500，不调 RPC。
     */
    @Test
    void shouldRejectBehaviorUpdateWhenFieldNull() throws Exception {
        mockMvc.perform(put(FX_PAUSE_BEHAVIOR_PATH + "/2")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowOpen\":null,\"allowClose\":true,\"allowLiquidation\":true,\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());

        verify(internalRpcClient, times(0)).put(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    /** PUT fx-pause-behavior/2 无 reason → 400（verifyReason，90656），不调 RPC。 */
    @Test
    void shouldRejectBehaviorUpdateWithoutReason() throws Exception {
        mockMvc.perform(put(FX_PAUSE_BEHAVIOR_PATH + "/2")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowOpen\":false,\"allowClose\":true,\"allowLiquidation\":true}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_TRADING_REASON_REQUIRED.code()));

        verify(internalRpcClient, times(0)).put(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    /** PUT fx-pause-behavior：trading 99004 → 翻 ADMIN_FX_PAUSE_BEHAVIOR_INVALID（90951）。 */
    @Test
    void shouldTranslateBehaviorValidationError() throws Exception {
        doThrow(new InternalRpcException(400, "99004", "fx pause behavior category out of range"))
                .when(internalRpcClient).put(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(put(FX_PAUSE_BEHAVIOR_PATH + "/2")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowOpen\":false,\"allowClose\":true,\"allowLiquidation\":true,\"reason\":\"越界\"}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_FX_PAUSE_BEHAVIOR_INVALID.code()));

        verify(internalRpcClient).put(eq(RPC_FX_PAUSE_BEHAVIOR + "/2"), any(), any(ParameterizedTypeReference.class));
    }

    /** PUT fx-pause-behavior 普通角色无 fx:pause-behavior:edit → 403。 */
    @Test
    void shouldDenyBehaviorUpdateWhenMissingEditPermission() throws Exception {
        String token = issueLimitedToken(710101L, "ops_no_fx_edit", List.of("fx:pause-behavior:view"));

        mockMvc.perform(put(FX_PAUSE_BEHAVIOR_PATH + "/2")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowOpen\":false,\"allowClose\":true,\"allowLiquidation\":true,\"reason\":\"无权限\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_PERMISSION_DENIED.code()));

        verify(internalRpcClient, times(0)).put(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    /** GET fx-pause-behavior 普通角色无 fx:pause-behavior:view → 403。 */
    @Test
    void shouldDenyBehaviorListWhenMissingViewPermission() throws Exception {
        String token = issueLimitedToken(710102L, "ops_no_fx_view", List.of("OTHER_PERMISSION"));

        mockMvc.perform(get(FX_PAUSE_BEHAVIOR_PATH)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_PERMISSION_DENIED.code()));

        verify(internalRpcClient, times(0)).get(anyString(), any(ParameterizedTypeReference.class));
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
