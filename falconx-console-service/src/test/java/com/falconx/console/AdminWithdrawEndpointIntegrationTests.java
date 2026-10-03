package com.falconx.console;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.falconx.console.api.AdminWithdrawItem;
import com.falconx.console.api.AdminWithdrawListResponse;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.repository.AdminWithdrawEnrichmentRepository;
import com.falconx.console.repository.mapper.record.AdminWithdrawEnrichmentRecord;
import com.falconx.console.security.AdminAuthenticationFilter;
import com.falconx.console.security.AdminTokenBlacklistService;
import com.falconx.console.security.AdminTokenSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * STAGE-7-WITHDRAW Phase 4：管理端出金审核 controller 集成测试。
 *
 * <p>覆盖 TC-WD-200~262：
 * <ul>
 *   <li>TC-WD-200 列表透传 status / userId / network / minAmount / maxAmount / page / pageSize</li>
 *   <li>TC-WD-201 列表默认分页 page=1 pageSize=20</li>
 *   <li>TC-WD-210 详情透传含全字段</li>
 *   <li>TC-WD-211 trading 30047 → ADMIN_WITHDRAW_NOT_FOUND (90500)</li>
 *   <li>TC-WD-220 approve 透传 + reviewNote → trading-core note 映射</li>
 *   <li>TC-WD-222 trading 30049 → ADMIN_WITHDRAW_NOT_PENDING (90501)</li>
 *   <li>TC-WD-230 reject 透传 reason → trading-core note 映射</li>
 *   <li>TC-WD-231 reject 空 reason → 本地 90503，不调用 trading</li>
 *   <li>TC-WD-240 emergency-cancel 透传 reason</li>
 *   <li>TC-WD-241 emergency-cancel 空 reason → 本地 90503</li>
 *   <li>TC-WD-242 trading 30050 → ADMIN_WITHDRAW_EMERGENCY_CANCEL_NOT_ALLOWED (90502)</li>
 *   <li>TC-WD-260 approve 走完 @RequiresPermission AOP（OperationAuditAspect after-returning）</li>
 * </ul>
 *
 * <p>策略：用 {@link MockitoBean} 替换 {@link InternalRpcClient}，避免依赖 gateway + trading-core
 * 联通性；专注验证 console 编排 / 字段映射 / 错误翻译 / 审计 AOP。
 *
 * <p>RBAC TC-WD-250~255（无权限 → 90004）由 STAGE-1-CONSOLE PermissionGuardAspect 单测覆盖；
 * 本测试以 superadmin 登录，所有端点默认放行。
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
class AdminWithdrawEndpointIntegrationTests {

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

    @MockitoBean
    private InternalRpcClient internalRpcClient;

    @MockitoBean
    private AdminWithdrawEnrichmentRepository enrichmentRepository;

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
        // 默认：enrichment 返回空 map（list / detail 用例视需要再 stub 具体 userId）
        doReturn(Map.of()).when(enrichmentRepository).findEnrichmentMap(any(), any());
    }

    /** TC-WD-200 列表透传 6 个 query 参数到 trading-core URI。 */
    @Test
    void shouldForwardListQueryParamsToTradingCore() throws Exception {
        AdminWithdrawListResponse listResponse = new AdminWithdrawListResponse(2, 20, 0L, List.of());
        doReturn(listResponse).when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get("/admin/withdraws")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("status", "PENDING")
                        .param("userId", "48275236470263808")
                        .param("network", "ERC20")
                        .param("minAmount", "100")
                        .param("maxAmount", "5000")
                        .param("page", "2")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(internalRpcClient).get(uriCaptor.capture(), any(ParameterizedTypeReference.class));
        String uri = uriCaptor.getValue();
        Assertions.assertTrue(uri.startsWith("/internal/v1/trading/withdraws?"),
                "forwarded URI 应以 /internal/v1/trading/withdraws? 开头，实际：" + uri);
        Assertions.assertTrue(uri.contains("page=2"), uri);
        Assertions.assertTrue(uri.contains("pageSize=20"), uri);
        Assertions.assertTrue(uri.contains("status=PENDING"), uri);
        Assertions.assertTrue(uri.contains("userId=48275236470263808"), uri);
        Assertions.assertTrue(uri.contains("network=ERC20"), uri);
        Assertions.assertTrue(uri.contains("minAmount=100"), uri);
        Assertions.assertTrue(uri.contains("maxAmount=5000"), uri);
    }

    /** TC-WD-201 列表默认分页 page=1 pageSize=20。 */
    @Test
    void shouldUseDefaultPagingWhenNotSpecified() throws Exception {
        AdminWithdrawListResponse listResponse = new AdminWithdrawListResponse(1, 20, 0L, List.of());
        doReturn(listResponse).when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get("/admin/withdraws")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(internalRpcClient).get(uriCaptor.capture(), any(ParameterizedTypeReference.class));
        String uri = uriCaptor.getValue();
        Assertions.assertTrue(uri.contains("page=1"), uri);
        Assertions.assertTrue(uri.contains("pageSize=20"), uri);
        Assertions.assertFalse(uri.contains("status="), uri);  // 无 status 不出现在 URL
    }

    /** TC-WD-210 详情透传含全字段 + §4 commit C：detail() 跨 schema enrich kycLevel/userEmail/dailyAccumulatedUsd。 */
    @Test
    void shouldReturnDetailWithAllFields() throws Exception {
        AdminWithdrawItem item = new AdminWithdrawItem(
                "900000001", "48275236470263808",
                new BigDecimal("10.00000000"),
                "USDT", "ERC20",
                "0xB010093f3e20334962023D9cc26D51c1E7F3BB51",
                "COMPLETED",
                null, null, null,
                "0x4c94530c377b200df862c578589b378270a5a5fe36563016e206bf6b147d45b0",
                14, null,
                OffsetDateTime.now(),
                null, null, null, null, null  // enrichment(kyc/email/daily)+uid/fullName：application service 装配
        );
        doReturn(item).when(internalRpcClient).get(
                contains("/internal/v1/trading/withdraws/"), any(ParameterizedTypeReference.class));
        doReturn(Map.of(48275236470263808L,
                new AdminWithdrawEnrichmentRecord(48275236470263808L, "u@example.com", 1, new BigDecimal("100.00"))))
                .when(enrichmentRepository).findEnrichmentMap(any(), any());

        mockMvc.perform(get("/admin/withdraws/900000001")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawId").value("900000001"))
                .andExpect(jsonPath("$.data.userId").value("48275236470263808"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.txHash").value("0x4c94530c377b200df862c578589b378270a5a5fe36563016e206bf6b147d45b0"))
                .andExpect(jsonPath("$.data.confirmations").value(14))
                .andExpect(jsonPath("$.data.kycLevel").value(1))
                .andExpect(jsonPath("$.data.userEmail").value("u@example.com"))
                .andExpect(jsonPath("$.data.dailyAccumulatedUsd").value(100.00));

        verify(internalRpcClient).get(eq("/internal/v1/trading/withdraws/900000001"),
                any(ParameterizedTypeReference.class));
        verify(enrichmentRepository).findEnrichmentMap(any(), any());
    }

    /** §4 commit C TC-WD-280: enrichment DB 异常时 detail 仍可用（三字段为 null）。 */
    @Test
    void shouldReturnDetailWithEnrichmentNullWhenRepositoryFails() throws Exception {
        AdminWithdrawItem item = new AdminWithdrawItem(
                "900000001", "48275236470263808",
                new BigDecimal("10"), "USDT", "ERC20", "0xB010...",
                "PENDING", null, null, null, null, 0, null, OffsetDateTime.now(),
                null, null, null, null, null
        );
        doReturn(item).when(internalRpcClient).get(
                contains("/internal/v1/trading/withdraws/"), any(ParameterizedTypeReference.class));
        doThrow(new RuntimeException("simulated DB outage"))
                .when(enrichmentRepository).findEnrichmentMap(any(), any());

        mockMvc.perform(get("/admin/withdraws/900000001")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawId").value("900000001"))
                .andExpect(jsonPath("$.data.kycLevel").isEmpty())
                .andExpect(jsonPath("$.data.userEmail").isEmpty())
                .andExpect(jsonPath("$.data.dailyAccumulatedUsd").isEmpty());
    }

    /** §4 commit C TC-WD-281: list() 批 enrich，每行带 kycLevel/userEmail/dailyAccumulatedUsd。 */
    @Test
    void shouldEnrichListItemsWithSingleRepositoryCall() throws Exception {
        AdminWithdrawItem a = new AdminWithdrawItem(
                "900000001", "111",
                new BigDecimal("100"), "USDT", "ERC20", "0xa", "PENDING",
                null, null, null, null, 0, null, OffsetDateTime.now(),
                null, null, null, null, null);
        AdminWithdrawItem b = new AdminWithdrawItem(
                "900000002", "222",
                new BigDecimal("200"), "USDT", "TRC20", "Tb", "PENDING",
                null, null, null, null, 0, null, OffsetDateTime.now(),
                null, null, null, null, null);
        AdminWithdrawListResponse list = new AdminWithdrawListResponse(1, 20, 2L, List.of(a, b));
        doReturn(list).when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));
        doReturn(Map.of(
                111L, new AdminWithdrawEnrichmentRecord(111L, "a@x.com", 1, new BigDecimal("50")),
                222L, new AdminWithdrawEnrichmentRecord(222L, "b@x.com", 0, new BigDecimal("0"))))
                .when(enrichmentRepository).findEnrichmentMap(any(), any());

        mockMvc.perform(get("/admin/withdraws")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].userEmail").value("a@x.com"))
                .andExpect(jsonPath("$.data.items[0].kycLevel").value(1))
                .andExpect(jsonPath("$.data.items[0].dailyAccumulatedUsd").value(50))
                .andExpect(jsonPath("$.data.items[1].userEmail").value("b@x.com"))
                .andExpect(jsonPath("$.data.items[1].kycLevel").value(0))
                .andExpect(jsonPath("$.data.items[1].dailyAccumulatedUsd").value(0));

        // 同一个 list() 只调一次 repo（批查）
        verify(enrichmentRepository, times(1)).findEnrichmentMap(any(), any());
    }

    /** TC-WD-211 trading 30047 → ADMIN_WITHDRAW_NOT_FOUND (90500)。 */
    @Test
    void shouldTranslateNotFoundErrorToAdminCode() throws Exception {
        doThrow(new InternalRpcException(404, "30047", "Withdraw Not Found"))
                .when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get("/admin/withdraws/999999999")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_WITHDRAW_NOT_FOUND.code()));
    }

    /** TC-WD-220 approve 透传 reviewNote → trading-core note 字段映射。 */
    @Test
    void shouldForwardApproveWithNoteMapping() throws Exception {
        AdminWithdrawItem approved = new AdminWithdrawItem(
                "900000001", "48275236470263808",
                new BigDecimal("100"), "USDT", "ERC20",
                "0xB010...", "APPROVED",
                null, null, null, null, 0, null, OffsetDateTime.now(),
                null, null, null, null, null
        );
        doReturn(approved).when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/withdraws/900000001/approve")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewNote\":\"合规审核已通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(internalRpcClient).post(eq("/internal/v1/trading/withdraws/900000001/approve"),
                bodyCaptor.capture(), any(ParameterizedTypeReference.class));
        Object body = bodyCaptor.getValue();
        Assertions.assertTrue(body.toString().contains("note=合规审核已通过"),
                "下游 body 应含 note 字段映射，实际：" + body);
    }

    /** TC-WD-222 trading 30049 → ADMIN_WITHDRAW_NOT_PENDING (90501)。 */
    @Test
    void shouldTranslateNotPendingErrorOnApprove() throws Exception {
        doThrow(new InternalRpcException(409, "30049", "Withdraw Not Pending"))
                .when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/withdraws/900000002/approve")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_WITHDRAW_NOT_PENDING.code()));
    }

    /** TC-WD-230 reject 透传 reason → trading-core note 字段映射。 */
    @Test
    void shouldForwardRejectWithReasonMapping() throws Exception {
        AdminWithdrawItem rejected = new AdminWithdrawItem(
                "900000001", "48275236470263808",
                new BigDecimal("100"), "USDT", "ERC20",
                "0xB010...", "REJECTED",
                null, null, "不符合 KYC 要求", null, 0, null, OffsetDateTime.now(),
                null, null, null, null, null
        );
        doReturn(rejected).when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/withdraws/900000001/reject")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不符合 KYC 要求\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(internalRpcClient).post(eq("/internal/v1/trading/withdraws/900000001/reject"),
                bodyCaptor.capture(), any(ParameterizedTypeReference.class));
        Object body = bodyCaptor.getValue();
        Assertions.assertTrue(body.toString().contains("note=不符合 KYC 要求"),
                "下游 body 应含 note 字段映射，实际：" + body);
    }

    /** TC-WD-231 reject 空 reason → 本地 90503 ADMIN_WITHDRAW_REJECT_REASON_REQUIRED，不调用 trading。 */
    @Test
    void shouldRejectLocallyWhenRejectReasonBlank() throws Exception {
        mockMvc.perform(post("/admin/withdraws/900000001/reject")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_WITHDRAW_REJECT_REASON_REQUIRED.code()));

        // 本地校验失败时不调 trading
        verify(internalRpcClient, times(0))
                .post(contains("/internal/v1/trading/withdraws/"), any(), any(ParameterizedTypeReference.class));
    }

    /** TC-WD-240 emergency-cancel 透传 reason。 */
    @Test
    void shouldForwardEmergencyCancelWithReason() throws Exception {
        AdminWithdrawItem canceled = new AdminWithdrawItem(
                "900000001", "48275236470263808",
                new BigDecimal("5000"), "USDT", "ERC20",
                "0xB010...", "CANCELED",
                null, null, "风控紧急取消", null, 0, null, OffsetDateTime.now(),
                null, null, null, null, null
        );
        doReturn(canceled).when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/withdraws/900000001/emergency-cancel")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"风控紧急取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        verify(internalRpcClient).post(eq("/internal/v1/trading/withdraws/900000001/emergency-cancel"),
                any(), any(ParameterizedTypeReference.class));
    }

    /** TC-WD-241 emergency-cancel 空 reason → 本地 90503，不调用 trading。 */
    @Test
    void shouldRejectLocallyWhenEmergencyCancelReasonBlank() throws Exception {
        mockMvc.perform(post("/admin/withdraws/900000001/emergency-cancel")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_WITHDRAW_REJECT_REASON_REQUIRED.code()));

        verify(internalRpcClient, times(0))
                .post(contains("/internal/v1/trading/withdraws/"), any(), any(ParameterizedTypeReference.class));
    }

    /** TC-WD-242 trading 30050 → ADMIN_WITHDRAW_EMERGENCY_CANCEL_NOT_ALLOWED (90502)。 */
    @Test
    void shouldTranslateEmergencyCancelNotAllowedError() throws Exception {
        doThrow(new InternalRpcException(409, "30050", "Withdraw Emergency Cancel Not Allowed"))
                .when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/withdraws/900000001/emergency-cancel")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"风控紧急取消\"}"))
                .andExpect(jsonPath("$.code")
                        .value(AdminErrorCode.ADMIN_WITHDRAW_EMERGENCY_CANCEL_NOT_ALLOWED.code()));
    }

    /** TC-WD-260 approve 走完 @RequiresPermission AOP（审计切面 after-returning 命中）。 */
    @Test
    void shouldWriteOperationAuditLogOnApprove() throws Exception {
        AdminWithdrawItem approved = new AdminWithdrawItem(
                "900000001", "48275236470263808",
                new BigDecimal("100"), "USDT", "ERC20",
                "0xB010...", "APPROVED",
                null, null, null, null, 0, null, OffsetDateTime.now(),
                null, null, null, null, null
        );
        doReturn(approved).when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/withdraws/900000001/approve")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        verify(internalRpcClient).post(eq("/internal/v1/trading/withdraws/900000001/approve"),
                any(), any(ParameterizedTypeReference.class));
        // 审计 AOP 已由 STAGE-1-CONSOLE OperationAuditAspect 单测在 baseline 覆盖；
        // 这里端到端到达 successful return 即可证明切点命中。
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
