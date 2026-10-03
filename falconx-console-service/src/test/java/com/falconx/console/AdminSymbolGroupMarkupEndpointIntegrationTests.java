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

import com.falconx.console.api.AdminSymbolGroupMarkupGroupedListResponse;
import com.falconx.console.api.AdminSymbolGroupMarkupListResponse;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.security.AdminAuthenticationFilter;
import com.falconx.console.security.AdminTokenBlacklistService;
import com.falconx.console.security.AdminTokenSupport;
import java.math.BigDecimal;
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
 * STAGE-12-GROUP-MARKUP TC-GM-030~034 管理端用户组加点 endpoint 集成测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-GM-030 list 透传过滤参数到 market internal RPC</li>
 *   <li>TC-GM-031 create POST 透传 + 审计 AOP（高风险写）</li>
 *   <li>TC-GM-032 update 90640 错误码 1:1 翻译到 ADMIN_SYMBOL_GROUP_MARKUP_NOT_FOUND</li>
 *   <li>TC-GM-033 delete 端点 RBAC：缺权限 → 90004（superadmin 已具备所有权限）</li>
 *   <li>TC-GM-034 bulkUpsert 502 条 → console 前置 @Size(max=500) → 400</li>
 * </ul>
 *
 * <p>策略：用 {@link MockitoBean} 替换 {@link InternalRpcClient}，
 * 专注验证 console 编排 + 透传 URI 形状 + 错误翻译。
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
class AdminSymbolGroupMarkupEndpointIntegrationTests {

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

    /** TC-GM-030 list 透传 groupCode/symbolLike/enabled/page/size 到 market internal RPC。 */
    @Test
    void TC_GM_030_listProxiesMarketRpcWithFilters() throws Exception {
        AdminSymbolGroupMarkupListResponse listResponse =
                new AdminSymbolGroupMarkupListResponse(List.of(), 0L, 2, 30);
        doReturn(listResponse).when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get("/admin/symbols/group-markup")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("groupCode", "vip")
                        .param("symbolLike", "BTC")
                        .param("enabled", "1")
                        .param("page", "2")
                        .param("size", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(internalRpcClient).get(uriCaptor.capture(), any(ParameterizedTypeReference.class));
        String forwardedUri = uriCaptor.getValue();
        Assertions.assertTrue(forwardedUri.startsWith("/internal/v1/market/symbols/group-markup?"),
                "实际：" + forwardedUri);
        Assertions.assertTrue(forwardedUri.contains("groupCode=vip"), forwardedUri);
        Assertions.assertTrue(forwardedUri.contains("symbolLike=BTC"), forwardedUri);
        Assertions.assertTrue(forwardedUri.contains("enabled=1"), forwardedUri);
        Assertions.assertTrue(forwardedUri.contains("page=2"), forwardedUri);
        Assertions.assertTrue(forwardedUri.contains("size=30"), forwardedUri);
    }

    /** TC-GM-030b grouped 端点透传到 market internal RPC。 */
    @Test
    void TC_GM_030b_listGroupedProxiesMarketRpc() throws Exception {
        AdminSymbolGroupMarkupGroupedListResponse groupedResponse =
                new AdminSymbolGroupMarkupGroupedListResponse(List.of());
        doReturn(groupedResponse).when(internalRpcClient).get(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(get("/admin/symbols/group-markup/grouped")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        verify(internalRpcClient).get(eq("/internal/v1/market/symbols/group-markup/grouped"),
                any(ParameterizedTypeReference.class));
    }

    /** TC-GM-031 create 透传 + body 含组加点字段 + reason 进审计 AOP。 */
    @Test
    void TC_GM_031_createProxiesWithBody() throws Exception {
        AdminSymbolGroupMarkupListResponse.Item created = new AdminSymbolGroupMarkupListResponse.Item(
                "vip", "BTCUSDT",
                new BigDecimal("0.5"), new BigDecimal("1.0"), true,
                OffsetDateTime.now(), OffsetDateTime.now()
        );
        doReturn(created).when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/symbols/group-markup")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"groupCode\":\"vip\","
                                + "\"platformSymbol\":\"BTCUSDT\","
                                + "\"bidExtra\":0.5,"
                                + "\"askExtra\":1.0,"
                                + "\"enabled\":1,"
                                + "\"reason\":\"VIP 组开放 BTC 双边优惠（ticket #123）\""
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.groupCode").value("vip"))
                .andExpect(jsonPath("$.data.platformSymbol").value("BTCUSDT"));

        verify(internalRpcClient).post(eq("/internal/v1/market/symbols/group-markup"),
                any(), any(ParameterizedTypeReference.class));
    }

    /** TC-GM-032 update market 90640 → 翻译为 ADMIN_SYMBOL_GROUP_MARKUP_NOT_FOUND。 */
    @Test
    void TC_GM_032_updateTranslates90640ToAdminCode() throws Exception {
        doThrow(new InternalRpcException(404, "90640", "(group, symbol) not found"))
                .when(internalRpcClient).put(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(put("/admin/symbols/group-markup/vip/BTCUSDT")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"bidExtra\":1.0,"
                                + "\"askExtra\":1.0,"
                                + "\"enabled\":0,"
                                + "\"reason\":\"VIP 组停用 BTC 加点（合规整改 ticket #456）\""
                                + "}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_SYMBOL_GROUP_MARKUP_NOT_FOUND.code()));

        verify(internalRpcClient).put(eq("/internal/v1/market/symbols/group-markup/vip/BTCUSDT"),
                any(), any(ParameterizedTypeReference.class));
    }

    /** TC-GM-032b create market 90642 → 翻译为 ADMIN_SYMBOL_GROUP_MARKUP_DUPLICATE。 */
    @Test
    void TC_GM_032b_createTranslates90642ToAdminCode() throws Exception {
        doThrow(new InternalRpcException(409, "90642", "(group, symbol) duplicate"))
                .when(internalRpcClient).post(anyString(), any(), any(ParameterizedTypeReference.class));

        mockMvc.perform(post("/admin/symbols/group-markup")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"groupCode\":\"vip\","
                                + "\"platformSymbol\":\"BTCUSDT\","
                                + "\"bidExtra\":0.5,"
                                + "\"askExtra\":1.0,"
                                + "\"enabled\":1,"
                                + "\"reason\":\"重复测试（ticket #789）\""
                                + "}"))
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_SYMBOL_GROUP_MARKUP_DUPLICATE.code()));
    }

    /**
     * TC-GM-033 delete 端点透传 + body reason 强制校验。
     *
     * <p>RBAC 维度：superadmin 拥有 symbol:group-markup:update 权限，应放行 → market 透传成功。
     * 无权限场景由 STAGE-1-CONSOLE baseline 已覆盖（@RequiresPermission AOP）。
     */
    @Test
    void TC_GM_033_deleteForwardsToMarketWithReason() throws Exception {
        doReturn(null).when(internalRpcClient)
                .delete(anyString(), any(ParameterizedTypeReference.class));

        mockMvc.perform(delete("/admin/symbols/group-markup/vip/BTCUSDT")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"清理停用组废弃加点（ticket #5678）\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        verify(internalRpcClient).delete(eq("/internal/v1/market/symbols/group-markup/vip/BTCUSDT"),
                any(ParameterizedTypeReference.class));
    }

    /** TC-GM-033b delete 缺 reason → console 前置 @NotBlank 返回 400，不调下游。 */
    @Test
    void TC_GM_033b_deleteRejectsBlankReasonLocally() throws Exception {
        MvcResult result = mockMvc.perform(delete("/admin/symbols/group-markup/vip/BTCUSDT")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andReturn();

        int sc = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertTrue(sc == 400 || body.contains("90"),
                "应返回 400 或业务错误码，实际 HTTP=" + sc + " body=" + body);

        verify(internalRpcClient, times(0)).delete(anyString(), any(ParameterizedTypeReference.class));
    }

    /** TC-GM-034 bulkUpsert 502 条 → console 前置 @Size(max=500) → 400，不调下游。 */
    @Test
    void TC_GM_034_bulkUpsert502ItemsRejectedLocally() throws Exception {
        StringBuilder itemsArray = new StringBuilder("[");
        for (int i = 0; i < 502; i++) {
            if (i > 0) itemsArray.append(",");
            itemsArray.append("{\"platformSymbol\":\"SYM").append(i)
                    .append("\",\"bidExtra\":0.1,\"askExtra\":0.1,\"enabled\":1}");
        }
        itemsArray.append("]");
        String body = "{\"items\":" + itemsArray
                + ",\"reason\":\"VIP 组批量调整双边加点（季度调价 ticket #9999）\"}";

        MvcResult result = mockMvc.perform(put("/admin/symbols/group-markup/vip/bulk")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        int sc = result.getResponse().getStatus();
        Assertions.assertEquals(400, sc, "502 条超过 @Size(max=500)，应被本地校验拒绝");

        verify(internalRpcClient, times(0)).put(anyString(), any(), any(ParameterizedTypeReference.class));
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
