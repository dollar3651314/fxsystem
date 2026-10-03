package com.falconx.identity.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.falconx.identity.IdentityServiceApplication;
import com.falconx.identity.application.IdentityRegistrationApplicationService;
import com.falconx.identity.command.RegisterIdentityUserCommand;
import com.falconx.identity.config.IdentityTraceContextFilter;
import com.falconx.identity.contract.auth.RegisterResponse;
import com.falconx.identity.repository.mapper.test.IdentityTestSupportMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.Assertions;
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
 * STAGE-6-KYC 用户侧 REST 控制器集成测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-KYC-001 正常 HTTP 提交（应用层业务规则在
 *       {@link com.falconx.identity.IdentityKycApplicationServiceIntegrationTests} 覆盖）</li>
 *   <li>TC-KYC-007 idType 枚举非法值 → 拒绝</li>
 *   <li>TC-KYC-008 X-User-Id header 缺失 → 拒绝</li>
 *   <li>TC-KYC-010 GET 从未提交返回 currentKycLevel + 空 submission 字段</li>
 * </ul>
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = IdentityServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_identity_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.port=6380",
                "falconx.identity.security.register-limit=1000",
                "falconx.identity.security.login-failure-limit=100",
                "falconx.identity.kafka.consumer-group-id=identity-user-kyc-ctl-it-${random.uuid}"
        }
)
class UserKycControllerIntegrationTests {

    private static final String FRONT_B64 = encode("front");
    private static final String BACK_B64 = encode("back");
    private static final String SELFIE_B64 = encode("selfie");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private IdentityTraceContextFilter identityTraceContextFilter;

    @Autowired
    private IdentityRegistrationApplicationService registrationService;

    @Autowired
    private IdentityTestSupportMapper supportMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        supportMapper.clearOwnerTables();
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(identityTraceContextFilter)
                .build();
    }

    /** TC-KYC-001 (HTTP) POST 正常提交返回 PENDING + submissionId 非空 + 响应不含 dataBase64。 */
    @Test
    void shouldReturnPendingWhenSubmitViaHttp() throws Exception {
        long userId = registerUser("tc-kyc-007a@example.com");

        mockMvc.perform(post("/api/v1/me/kyc")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody("ID_CARD", "110101199001011234")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.submissionId").isNotEmpty())
                .andExpect(jsonPath("$.data.idFrontBase64").doesNotExist())
                .andExpect(jsonPath("$.data.dataBase64").doesNotExist());
    }

    /** TC-KYC-007 idType 非法枚举值 → 拒绝处理（IllegalArgumentException 由全局异常处理器兜底）。 */
    @Test
    void shouldRejectWhenIdTypeIsUnknown() throws Exception {
        long userId = registerUser("tc-kyc-007b@example.com");

        mockMvc.perform(post("/api/v1/me/kyc")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody("UNKNOWN", "110101199001011234")))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    Assertions.assertTrue(sc == 400 || sc == 500,
                            "idType 非法应拒绝，但 HTTP " + sc);
                });
    }

    /** TC-KYC-008 X-User-Id 缺失 → 拒绝处理（非 200，HTTP 400/401/500 取决于异常处理器）。 */
    @Test
    void shouldRejectWhenUserIdHeaderMissing() throws Exception {
        mockMvc.perform(post("/api/v1/me/kyc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody("ID_CARD", "110101199001011234")))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    Assertions.assertTrue(sc == 400 || sc == 401 || sc == 500,
                            "缺失 X-User-Id 应拒绝，但 HTTP " + sc);
                });
    }

    /** TC-KYC-010 (HTTP) GET 从未提交返回当前 KYC 等级，submission 字段为空。 */
    @Test
    void shouldReturnNullWhenGetLatestNeverSubmitted() throws Exception {
        long userId = registerUser("tc-kyc-010-http@example.com");

        mockMvc.perform(get("/api/v1/me/kyc")
                        .header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.currentKycLevel").value(0))
                .andExpect(jsonPath("$.data.userId").value(String.valueOf(userId)))
                .andExpect(jsonPath("$.data.submissionId").doesNotExist())
                .andExpect(jsonPath("$.data.status").doesNotExist());
    }

    private long registerUser(String email) {
        RegisterResponse r = registrationService.register(
                new RegisterIdentityUserCommand(email, "Passw0rd!", "127.0.0.1",
                        "Test", null, "User", LocalDate.of(2000, 1, 1), "CHN")
        );
        Assertions.assertNotNull(r.userId());
        return r.userId();
    }

    private String buildBody(String idType, String idNumber) {
        return String.format(
                "{\"idType\":\"%s\",\"idNumber\":\"%s\",\"idFrontBase64\":\"%s\",\"idBackBase64\":\"%s\",\"selfieBase64\":\"%s\"}",
                idType, idNumber, FRONT_B64, BACK_B64, SELFIE_B64);
    }

    private static String encode(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
