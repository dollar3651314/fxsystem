package com.falconx.console;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.falconx.console.api.AdminWalletProvisionDlqItem;
import com.falconx.console.api.AdminWalletProvisionDlqListResponse;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.security.AdminAuthenticationFilter;
import com.falconx.console.security.AdminTokenBlacklistService;
import com.falconx.console.security.AdminTokenSupport;
import com.falconx.console.wallet.AdminWalletProvisionApplicationService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
 * STAGE-5-WALLET-PROVISION Phase 2 R6：管理端地址预分配 DLQ controller 集成测试。
 *
 * <p>覆盖 TC-WP-040 ~ TC-WP-043：
 * <ul>
 *   <li>TC-WP-040 GET list 透传 status / userId / page / size → wallet RPC URI</li>
 *   <li>TC-WP-041 POST retry 空 reason → 99004（Bean Validation 早于 service，与
 *       STAGE-7-WITHDRAW 90503 同源问题）+ 不发 RPC</li>
 *   <li>TC-WP-041b ApplicationService.retry 直接调用空 reason → 90862（service 层兜底校验）</li>
 *   <li>TC-WP-042 POST retry 透传成功 → wallet RPC body 含 reason + 200 OK</li>
 *   <li>TC-WP-043a wallet 90860 → 翻译为 ADMIN_WALLET_PROVISION_DLQ_NOT_FOUND（HTTP 404）</li>
 *   <li>TC-WP-043b wallet 90861 → 翻译为 ADMIN_WALLET_PROVISION_DLQ_ALREADY_RESOLVED（HTTP 409）</li>
 * </ul>
 *
 * <p>RBAC TC-WP-044 + 审计 TC-WP-045 由 STAGE-1-CONSOLE PermissionGuardAspect /
 * OperationAuditAspect baseline 单测覆盖；本测试以 superadmin 登录，所有端点默认放行。
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
class AdminWalletProvisionEndpointIntegrationTests {

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
    private AdminWalletProvisionApplicationService applicationService;

    @MockitoBean
    private InternalRpcClient internalRpcClient;

    private MockMvc mockMvc;
    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        AdminAuthenticationFilter authFilter = new AdminAuthenticationFilter(
                adminTokenSupport, adminTokenBlacklistService, objectMapper);
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(authFilter)
                .build();
        accessToken = loginAsSuperAdmin();
    }

    /** TC-WP-040 GET list 透传 status / userId / page / size 到 wallet /internal/v1/wallet/console/provision-dlq。 */
    @Test
    void shouldForwardListQueryParamsToWallet() throws Exception {
        AdminWalletProvisionDlqListResponse listResponse =
                new AdminWalletProvisionDlqListResponse(1, 20, 0L, List.of());
        doReturn(listResponse).when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get("/admin/wallet/provision-dlq")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("status", "0")
                        .param("userId", "80060101")
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(internalRpcClient).get(uriCaptor.capture(), any(ParameterizedTypeReference.class));
        String uri = uriCaptor.getValue();
        Assertions.assertTrue(uri.startsWith("/internal/v1/wallet/console/provision-dlq?"),
                "forwarded URI 应以 /internal/v1/wallet/console/provision-dlq? 开头，实际：" + uri);
        Assertions.assertTrue(uri.contains("page=2"), uri);
        Assertions.assertTrue(uri.contains("size=50"), uri);
        Assertions.assertTrue(uri.contains("status=0"), uri);
        Assertions.assertTrue(uri.contains("userId=80060101"), uri);
    }

    /**
     * TC-WP-041 POST retry 空 reason → 99004（Bean Validation 早于 service，对应 STAGE-7-WITHDRAW
     * Phase 4 commit A §4.A 已知行为；不发 wallet RPC）。
     */
    @Test
    void shouldReject400OnEmptyReasonBeforeReachingService() throws Exception {
        mockMvc.perform(post("/admin/wallet/provision-dlq/555000001/retry")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("99004"));

        // Bean Validation 在 controller 入参时拦截，不应到达 internal RPC
        verify(internalRpcClient, org.mockito.Mockito.never())
                .post(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    /**
     * TC-WP-041b 直接调 ApplicationService.retry 空 reason → 抛 ADMIN_WALLET_PROVISION_REASON_REQUIRED
     * (90862)；service 层兜底校验路径覆盖（commit A R2 二轮 §4.B 错误码归一）。
     */
    @Test
    void shouldThrow90862WhenServiceReceivesBlankReasonDirectly() {
        AdminBusinessException ex = Assertions.assertThrows(AdminBusinessException.class,
                () -> applicationService.retry(555000002L, ""));
        Assertions.assertEquals(AdminErrorCode.ADMIN_WALLET_PROVISION_REASON_REQUIRED, ex.getErrorCode(),
                "service 层 verifyReason 必须抛 ADMIN_WALLET_PROVISION_REASON_REQUIRED");

        AdminBusinessException ex2 = Assertions.assertThrows(AdminBusinessException.class,
                () -> applicationService.retry(555000002L, "   "));
        Assertions.assertEquals(AdminErrorCode.ADMIN_WALLET_PROVISION_REASON_REQUIRED, ex2.getErrorCode());

        AdminBusinessException ex3 = Assertions.assertThrows(AdminBusinessException.class,
                () -> applicationService.retry(555000002L, null));
        Assertions.assertEquals(AdminErrorCode.ADMIN_WALLET_PROVISION_REASON_REQUIRED, ex3.getErrorCode());
    }

    /** TC-WP-042 POST retry 透传成功 → wallet RPC body 含 reason + 200 OK + status RESOLVED。 */
    @Test
    void shouldForwardRetryWithReasonAndReturnResolved() throws Exception {
        AdminWalletProvisionDlqItem resolved = new AdminWalletProvisionDlqItem(
                "555000001", "user-registered-80060101", "80060101",
                "u-042", "wp-042@example.com",
                1, "RESOLVED", "20007", "xpub for chain ETH is missing",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now().minusHours(1));
        doReturn(resolved).when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/wallet/provision-dlq/555000001/retry")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"xpub 已补齐\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.id").value("555000001"));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(internalRpcClient).post(eq("/internal/v1/wallet/console/provision-dlq/555000001/retry"),
                bodyCaptor.capture(), any(ParameterizedTypeReference.class));
        Object body = bodyCaptor.getValue();
        Assertions.assertTrue(body.toString().contains("reason=xpub 已补齐"),
                "wallet RPC body 必须含 reason 字段，实际：" + body);
    }

    /** TC-WP-043a wallet 90860 → 翻译为 ADMIN_WALLET_PROVISION_DLQ_NOT_FOUND（HTTP 404）。 */
    @Test
    void shouldTranslateWalletDlqNotFoundError() throws Exception {
        doThrow(new InternalRpcException(404, "90860", "Wallet Address Provision DLQ Not Found"))
                .when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/wallet/provision-dlq/99999999/retry")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"探测错误码翻译\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(
                        AdminErrorCode.ADMIN_WALLET_PROVISION_DLQ_NOT_FOUND.code()));
    }

    /** TC-WP-043b wallet 90861 → 翻译为 ADMIN_WALLET_PROVISION_DLQ_ALREADY_RESOLVED（HTTP 409）。 */
    @Test
    void shouldTranslateWalletDlqAlreadyResolvedError() throws Exception {
        doThrow(new InternalRpcException(409, "90861", "Wallet Address Provision DLQ Already Resolved"))
                .when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/wallet/provision-dlq/555000001/retry")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"探测错误码翻译\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        AdminErrorCode.ADMIN_WALLET_PROVISION_DLQ_ALREADY_RESOLVED.code()));
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
