package com.falconx.console;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.falconx.console.api.AdminTierListResponse;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.repository.AdminOperationLogRepository;
import com.falconx.console.repository.AdminPermissionRepository;
import com.falconx.console.security.AdminAuthenticationFilter;
import com.falconx.console.security.AdminTokenBlacklistService;
import com.falconx.console.security.AdminTokenSupport;
import com.falconx.console.security.AdminTokenSupport.IssuedToken;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * STAGE-14C2 Task 8：管理端 tier CRUD 透传 controller 集成测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>GET /admin/trading/tiers（tier:view）→ 透传 query 到 trading /internal/v1/trading/console/tier</li>
 *   <li>POST/PUT/DELETE（tier:edit）→ 透传 + 审计写 t_admin_operation_log</li>
 *   <li>RBAC：无 tier:view → 403；无 tier:edit 调 POST → 403</li>
 *   <li>错误翻译：trading 90932 → ADMIN_TIER_OVERLAP；90931 → ADMIN_TIER_VALIDATION_FAILED；90930 → ADMIN_TIER_NOT_FOUND</li>
 * </ul>
 *
 * <p>策略：{@link MockitoBean} 替换 {@link InternalRpcClient}（不依赖 gateway + trading-core 联通）；
 * superadmin 登录走透传与审计正路；RBAC 负路用 {@link AdminTokenSupport#issueAccessToken} 现签发普通角色
 * token + {@link MockitoBean} {@link AdminPermissionRepository} 控制其权限集合，驱动真实 PermissionGuardAspect。
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
class AdminTierEndpointIntegrationTests {

    private static final String SUPER_ADMIN = "superadmin";
    private static final String SUPER_PASSWORD = "TestAdmin@1234";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminTokenSupport adminTokenSupport;

    @Autowired
    private AdminTokenBlacklistService adminTokenBlacklistService;

    @Autowired
    private AdminOperationLogRepository adminOperationLogRepository;

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

    /** GET 列表透传 symbol/groupCode/page/size 到 trading /internal/v1/trading/console/tier。 */
    @Test
    void shouldForwardListQueryParamsToTradingCore() throws Exception {
        AdminTierListResponse listResponse = new AdminTierListResponse(List.of(), 0L, 1, 20);
        doReturn(listResponse).when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get("/admin/trading/tiers")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("symbol", "BTCUSDT")
                        .param("groupCode", "default")
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(internalRpcClient).get(uriCaptor.capture(), any(ParameterizedTypeReference.class));
        String uri = uriCaptor.getValue();
        Assertions.assertTrue(uri.startsWith("/internal/v1/trading/console/tier?"),
                "forwarded URI 应以 /internal/v1/trading/console/tier? 开头，实际：" + uri);
        Assertions.assertTrue(uri.contains("page=2"), uri);
        Assertions.assertTrue(uri.contains("size=50"), uri);
        Assertions.assertTrue(uri.contains("symbol=BTCUSDT"), uri);
        Assertions.assertTrue(uri.contains("groupCode=default"), uri);
    }

    /** GET 列表默认分页 page=1 size=20，不传 symbol/groupCode 时不拼接。 */
    @Test
    void shouldUseDefaultPagingWhenNotSpecified() throws Exception {
        doReturn(new AdminTierListResponse(List.of(), 0L, 1, 20))
                .when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get("/admin/trading/tiers")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(internalRpcClient).get(uriCaptor.capture(), any(ParameterizedTypeReference.class));
        String uri = uriCaptor.getValue();
        Assertions.assertTrue(uri.contains("page=1"), uri);
        Assertions.assertTrue(uri.contains("size=20"), uri);
        Assertions.assertFalse(uri.contains("symbol="), uri);
        Assertions.assertFalse(uri.contains("groupCode="), uri);
    }

    /** POST 新建透传字段到 trading（不含 reason），并写审计行（tier:edit）。 */
    @Test
    void shouldForwardCreateAndWriteAudit() throws Exception {
        AdminTierListResponse.Item created = new AdminTierListResponse.Item(
                10001L, "BTCUSDT", "default", 1,
                new BigDecimal("0"), new BigDecimal("50000"), 100, new BigDecimal("0.005"), true);
        doReturn(created).when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        long before = countTierEditAudit();

        mockMvc.perform(post("/admin/trading/tiers")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"BTCUSDT\",\"groupCode\":\"default\",\"tierNo\":1,"
                                + "\"notionalLower\":0,\"notionalUpper\":50000,\"maxLeverage\":100,"
                                + "\"mmRate\":0.005,\"reason\":\"新增 BTC 首档\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(10001))
                .andExpect(jsonPath("$.data.symbol").value("BTCUSDT"));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(internalRpcClient).post(eq("/internal/v1/trading/console/tier"),
                bodyCaptor.capture(), any(ParameterizedTypeReference.class));
        String forwarded = bodyCaptor.getValue().toString();
        Assertions.assertTrue(forwarded.contains("BTCUSDT"), forwarded);
        Assertions.assertFalse(forwarded.contains("reason"),
                "trading RPC body 不应含 reason（reason 仅本地审计），实际：" + forwarded);

        Assertions.assertEquals(before + 1, countTierEditAudit(), "POST 后应新增一条 tier:edit 审计行");
    }

    /** PUT 编辑透传到 trading /tier/{id}，并写审计行。 */
    @Test
    void shouldForwardUpdateAndWriteAudit() throws Exception {
        AdminTierListResponse.Item updated = new AdminTierListResponse.Item(
                10001L, "BTCUSDT", "default", 1,
                new BigDecimal("0"), new BigDecimal("60000"), 80, new BigDecimal("0.0075"), true);
        doReturn(updated).when(internalRpcClient).put(anyString(), any(), any(ParameterizedTypeReference.class));

        long before = countTierEditAudit();

        mockMvc.perform(put("/admin/trading/tiers/10001")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierNo\":1,\"notionalLower\":0,\"notionalUpper\":60000,"
                                + "\"maxLeverage\":80,\"mmRate\":0.0075,\"reason\":\"调高 MM 率\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxLeverage").value(80));

        verify(internalRpcClient).put(eq("/internal/v1/trading/console/tier/10001"),
                any(), any(ParameterizedTypeReference.class));
        Assertions.assertEquals(before + 1, countTierEditAudit(), "PUT 后应新增一条 tier:edit 审计行");
    }

    /** DELETE 软删透传到 trading /tier/{id}，并写审计行。 */
    @Test
    void shouldForwardDeleteAndWriteAudit() throws Exception {
        doReturn(null).when(internalRpcClient).delete(anyString(), any(ParameterizedTypeReference.class));

        long before = countTierEditAudit();

        mockMvc.perform(delete("/admin/trading/tiers/10001")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        verify(internalRpcClient).delete(eq("/internal/v1/trading/console/tier/10001"),
                any(ParameterizedTypeReference.class));
        Assertions.assertEquals(before + 1, countTierEditAudit(), "DELETE 后应新增一条 tier:edit 审计行");
    }

    /** 错误翻译：trading 90932 → ADMIN_TIER_OVERLAP（409）。 */
    @Test
    void shouldTranslateOverlapError() throws Exception {
        doThrow(new InternalRpcException(409, "90932", "tier overlap"))
                .when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/trading/tiers")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"BTCUSDT\",\"groupCode\":\"default\",\"tierNo\":1,"
                                + "\"notionalLower\":0,\"notionalUpper\":50000,\"maxLeverage\":100,"
                                + "\"mmRate\":0.005,\"reason\":\"重叠区间\"}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_TIER_OVERLAP.code()));
    }

    /** 错误翻译：trading 90931 → ADMIN_TIER_VALIDATION_FAILED（400）。 */
    @Test
    void shouldTranslateValidationError() throws Exception {
        doThrow(new InternalRpcException(400, "90931", "check failed"))
                .when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/trading/tiers")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"BTCUSDT\",\"groupCode\":\"default\",\"tierNo\":1,"
                                + "\"notionalLower\":0,\"notionalUpper\":50000,\"maxLeverage\":100,"
                                + "\"mmRate\":0.5,\"reason\":\"max_lev×mm>1\"}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_TIER_VALIDATION_FAILED.code()));
    }

    /** 错误翻译：trading 90930 → ADMIN_TIER_NOT_FOUND（404）。 */
    @Test
    void shouldTranslateNotFoundErrorOnUpdate() throws Exception {
        doThrow(new InternalRpcException(404, "90930", "tier not found"))
                .when(internalRpcClient).put(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(put("/admin/trading/tiers/99999")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierNo\":1,\"notionalLower\":0,\"notionalUpper\":60000,"
                                + "\"maxLeverage\":80,\"mmRate\":0.0075,\"reason\":\"改不存在档位\"}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_TIER_NOT_FOUND.code()));
    }

    /** RBAC：普通管理员无 tier:view 调 GET → 403（90004）。 */
    @Test
    void shouldDenyListWhenMissingViewPermission() throws Exception {
        String token = issueLimitedToken(700001L, "ops_no_tier", List.of("OTHER_PERMISSION"));

        mockMvc.perform(get("/admin/trading/tiers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_PERMISSION_DENIED.code()));

        verify(internalRpcClient, times(0)).get(anyString(), any(ParameterizedTypeReference.class));
    }

    /** RBAC：普通管理员有 tier:view 但无 tier:edit 调 POST → 403（90004）。 */
    @Test
    void shouldDenyCreateWhenMissingEditPermission() throws Exception {
        String token = issueLimitedToken(700002L, "ops_view_only", List.of("tier:view"));

        mockMvc.perform(post("/admin/trading/tiers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"BTCUSDT\",\"groupCode\":\"default\",\"tierNo\":1,"
                                + "\"notionalLower\":0,\"notionalUpper\":50000,\"maxLeverage\":100,"
                                + "\"mmRate\":0.005,\"reason\":\"无 edit 权限\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_PERMISSION_DENIED.code()));

        verify(internalRpcClient, times(0)).post(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    /** RBAC：普通管理员有 tier:view 调 GET → 透传放行。 */
    @Test
    void shouldAllowListWhenHasViewPermission() throws Exception {
        String token = issueLimitedToken(700003L, "ops_viewer", List.of("tier:view"));
        doReturn(new AdminTierListResponse(List.of(), 0L, 1, 20))
                .when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get("/admin/trading/tiers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        verify(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));
    }

    // ---- helpers ----

    private long countTierEditAudit() {
        return adminOperationLogRepository.countByFilters(
                null, "tier:edit", null, null, null, null, null);
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
