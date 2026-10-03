package com.falconx.identity.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.identity.IdentityServiceApplication;
import com.falconx.identity.config.IdentityTraceContextFilter;
import com.falconx.identity.repository.mapper.test.IdentityTestSupportMapper;
import com.falconx.identity.service.IdentityTokenService;
import com.falconx.identity.service.PasswordHashService;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 认证控制器集成测试。
 *
 * <p>该测试覆盖 Stage 5 下的最小真实身份链路：
 * 注册 -> 登录 -> Refresh Token 轮换。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = IdentityServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_identity_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root"
        }
)
@ExtendWith(OutputCaptureExtension.class)
class AuthControllerIntegrationTests {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdentityTraceContextFilter identityTraceContextFilter;

    @Autowired
    private IdentityTestSupportMapper identityTestSupportMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IdentityTokenService identityTokenService;

    @Autowired
    private PasswordHashService passwordHashService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        identityTestSupportMapper.clearOwnerTables();
        clearSecurityKeys();
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(identityTraceContextFilter)
                .build();
    }

    @Test
    void shouldAllowLoginImmediatelyAfterRegistration() throws Exception {
        MockHttpServletResponse registerResponse = postJson("/api/v1/auth/register",
                registerBody("immediate-login@example.com"));
        JsonNode registerJson = objectMapper.readTree(registerResponse.getContentAsString());

        Assertions.assertEquals(200, registerResponse.getStatus());
        Assertions.assertEquals("0", registerJson.path("code").asText());
        Assertions.assertEquals("ACTIVE", registerJson.path("data").path("status").asText());
        assertEmailVerifiedFalse(registerJson.path("data"));

        MockHttpServletResponse loginResponse = postJson("/api/v1/auth/login", """
                {
                  "email": "immediate-login@example.com",
                  "password": "Passw0rd!"
                }
                """);
        JsonNode loginJson = objectMapper.readTree(loginResponse.getContentAsString());

        Assertions.assertEquals(200, loginResponse.getStatus());
        Assertions.assertEquals("0", loginJson.path("code").asText());
        Assertions.assertEquals("ACTIVE", loginJson.path("data").path("userStatus").asText());
        assertEmailVerifiedFalse(loginJson.path("data"));
        Assertions.assertFalse(loginJson.path("data").path("accessToken").asText().isBlank());
        Assertions.assertFalse(loginJson.path("data").path("refreshToken").asText().isBlank());
    }

    @Test
    void shouldAllowLegacyPendingDepositUserToLogin() throws Exception {
        long userId = 91011L;
        identityTestSupportMapper.insertPendingDepositUser(
                userId,
                "U00091011",
                "legacy-pending@example.com",
                passwordHashService.hash("Passw0rd!")
        );

        MockHttpServletResponse loginResponse = postJson("/api/v1/auth/login", """
                {
                  "email": "legacy-pending@example.com",
                  "password": "Passw0rd!"
                }
                """);
        JsonNode loginJson = objectMapper.readTree(loginResponse.getContentAsString());

        Assertions.assertEquals(200, loginResponse.getStatus());
        Assertions.assertEquals("0", loginJson.path("code").asText());
        Assertions.assertEquals("ACTIVE", loginJson.path("data").path("userStatus").asText());
        assertEmailVerifiedFalse(loginJson.path("data"));
        Assertions.assertFalse(loginJson.path("data").path("accessToken").asText().isBlank());
        Assertions.assertEquals(1, identityTestSupportMapper.selectUserStatusById(userId));
        Assertions.assertNotNull(identityTestSupportMapper.selectActivatedAtByUserId(userId));
    }

    @Test
    void shouldRegisterLoginAndRefreshSuccessfully(CapturedOutput output) throws Exception {
        MockHttpServletResponse registerResponse = postJson("/api/v1/auth/register",
                registerBody("alice@example.com"));
        JsonNode registerJson = objectMapper.readTree(registerResponse.getContentAsString());
        Assertions.assertEquals(200, registerResponse.getStatus());
        Assertions.assertNotNull(registerResponse.getHeader("X-Trace-Id"));
        Assertions.assertEquals("0", registerJson.path("code").asText());
        Assertions.assertEquals("alice@example.com", registerJson.path("data").path("email").asText());
        Assertions.assertEquals("ACTIVE", registerJson.path("data").path("status").asText());
        assertEmailVerifiedFalse(registerJson.path("data"));
        Assertions.assertEquals(Boolean.FALSE, identityTestSupportMapper.selectEmailVerifiedByUserId(
                registerJson.path("data").path("userId").asLong()
        ));

        MockHttpServletResponse loginResponse = postJson("/api/v1/auth/login", """
                {
                  "email": "alice@example.com",
                  "password": "Passw0rd!"
                }
                """);
        JsonNode loginJson = objectMapper.readTree(loginResponse.getContentAsString());
        Assertions.assertEquals("0", loginJson.path("code").asText());
        assertEmailVerifiedFalse(loginJson.path("data"));
        String accessToken = loginJson.path("data").path("accessToken").asText();
        Assertions.assertFalse(jwtPayload(accessToken).has("emailVerified"));
        String refreshToken = loginJson.path("data").path("refreshToken").asText();

        MockHttpServletResponse refreshResponse = postJson("/api/v1/auth/refresh", """
                {
                  "refreshToken": "%s"
                }
                """.formatted(refreshToken));
        JsonNode refreshJson = objectMapper.readTree(refreshResponse.getContentAsString());
        Assertions.assertEquals("0", refreshJson.path("code").asText());
        assertEmailVerifiedFalse(refreshJson.path("data"));
        String rotatedRefreshToken = refreshJson.path("data").path("refreshToken").asText();
        Assertions.assertNotEquals(
                refreshToken,
                rotatedRefreshToken
        );

        MockHttpServletResponse secondRefreshResponse = postJson("/api/v1/auth/refresh", """
                {
                  "refreshToken": "%s"
                }
                """.formatted(refreshToken));
        JsonNode secondRefreshJson = objectMapper.readTree(secondRefreshResponse.getContentAsString());
        Assertions.assertEquals("10006", secondRefreshJson.path("code").asText());

        String logs = output.toString();
        Assertions.assertTrue(logs.contains("identity.register.received"));
        Assertions.assertTrue(logs.contains("identity.register.completed"));
        Assertions.assertTrue(logs.contains("identity.login.received"));
        Assertions.assertTrue(logs.contains("identity.login.completed"));
        Assertions.assertTrue(logs.contains("traceId="));
        Assertions.assertFalse(logs.contains("Passw0rd!"));
        Assertions.assertFalse(logs.contains(refreshToken));
        Assertions.assertFalse(logs.contains(rotatedRefreshToken));
    }

    @Test
    void shouldRejectDuplicateRegistration() throws Exception {
        String body = registerBody("duplicate@example.com");

        MockHttpServletResponse firstRegisterResponse = postJson("/api/v1/auth/register", body);
        JsonNode firstRegisterJson = objectMapper.readTree(firstRegisterResponse.getContentAsString());
        Assertions.assertEquals("0", firstRegisterJson.path("code").asText());

        MockHttpServletResponse secondRegisterResponse = postJson("/api/v1/auth/register", body);
        JsonNode secondRegisterJson = objectMapper.readTree(secondRegisterResponse.getContentAsString());
        Assertions.assertEquals("10008", secondRegisterJson.path("code").asText());
    }

    @Test
    void shouldRateLimitLoginAfterFiveFailuresFromSameClientIp() throws Exception {
        String clientIp = "198.51.100.10";
        MockHttpServletResponse registerResponse = postJson("/api/v1/auth/register",
                registerBody("locked@example.com"), clientIp);
        JsonNode registerJson = objectMapper.readTree(registerResponse.getContentAsString());
        Assertions.assertEquals("ACTIVE", registerJson.path("data").path("status").asText());

        for (int attempt = 0; attempt < 5; attempt++) {
            MockHttpServletResponse failedLoginResponse = postJson("/api/v1/auth/login", """
                    {
                      "email": "locked@example.com",
                      "password": "WrongPassw0rd!"
                    }
                    """, clientIp);
            JsonNode failedLoginJson = objectMapper.readTree(failedLoginResponse.getContentAsString());
            Assertions.assertEquals("10005", failedLoginJson.path("code").asText());
        }

        MockHttpServletResponse lockedResponse = postJson("/api/v1/auth/login", """
                {
                  "email": "locked@example.com",
                  "password": "Passw0rd!"
                }
                """, clientIp);
        JsonNode lockedJson = objectMapper.readTree(lockedResponse.getContentAsString());
        Assertions.assertEquals("10003", lockedJson.path("code").asText());
        Assertions.assertNotNull(stringRedisTemplate.opsForValue().get("falconx:auth:login:fail:" + clientIp));
    }

    @Test
    void shouldRateLimitRegistrationAfterFiveRequestsFromSameClientIp() throws Exception {
        String clientIp = "198.51.100.20";
        for (int attempt = 1; attempt <= 5; attempt++) {
            MockHttpServletResponse response = postJson("/api/v1/auth/register",
                    registerBody("register-limit-%s@example.com".formatted(attempt)), clientIp);
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            Assertions.assertEquals("0", json.path("code").asText());
        }

        MockHttpServletResponse limitedResponse = postJson("/api/v1/auth/register",
                registerBody("register-limit-6@example.com"), clientIp);
        JsonNode limitedJson = objectMapper.readTree(limitedResponse.getContentAsString());
        Assertions.assertEquals("10004", limitedJson.path("code").asText());
    }

    @Test
    void shouldBlacklistCurrentAccessTokenWhenLogoutSucceeds() throws Exception {
        MockHttpServletResponse registerResponse = postJson("/api/v1/auth/register",
                registerBody("logout@example.com"));
        JsonNode registerJson = objectMapper.readTree(registerResponse.getContentAsString());
        Assertions.assertEquals("ACTIVE", registerJson.path("data").path("status").asText());

        MockHttpServletResponse loginResponse = postJson("/api/v1/auth/login", """
                {
                  "email": "logout@example.com",
                  "password": "Passw0rd!"
                }
                """);
        JsonNode loginJson = objectMapper.readTree(loginResponse.getContentAsString());
        String accessToken = loginJson.path("data").path("accessToken").asText();
        IdentityTokenService.ValidatedAccessToken tokenDetails = identityTokenService.parseAndValidateAccessToken(accessToken);

        MockHttpServletResponse logoutResponse = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andReturn()
                .getResponse();
        JsonNode logoutJson = objectMapper.readTree(logoutResponse.getContentAsString());

        Assertions.assertEquals(200, logoutResponse.getStatus());
        Assertions.assertEquals("0", logoutJson.path("code").asText());
        String blacklistKey = "falconx:auth:token:blacklist:" + tokenDetails.jti();
        Assertions.assertEquals("1", stringRedisTemplate.opsForValue().get(blacklistKey));
        Long blacklistTtlSeconds = stringRedisTemplate.getExpire(blacklistKey);
        Assertions.assertNotNull(blacklistTtlSeconds);
        Assertions.assertTrue(blacklistTtlSeconds > 0);
        // Redis TTL 以秒级返回，JWT 剩余 TTL 基于当前时刻计算，边界时允许 1 秒取整差。
        Assertions.assertTrue(blacklistTtlSeconds <= tokenDetails.remainingTtl().toSeconds() + 1);
    }

    @Test
    void shouldRejectLogoutWhenAuthorizationHeaderMissingOrInvalid() throws Exception {
        MockHttpServletResponse missingAuthorizationResponse = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/logout"))
                .andReturn()
                .getResponse();
        JsonNode missingAuthorizationJson = objectMapper.readTree(missingAuthorizationResponse.getContentAsString());
        Assertions.assertEquals("10001", missingAuthorizationJson.path("code").asText());

        MockHttpServletResponse invalidAuthorizationResponse = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/auth/logout")
                                .header("Authorization", "Bearer invalid-token"))
                .andReturn()
                .getResponse();
        JsonNode invalidAuthorizationJson = objectMapper.readTree(invalidAuthorizationResponse.getContentAsString());
        Assertions.assertEquals("10001", invalidAuthorizationJson.path("code").asText());
    }

    private MockHttpServletResponse postJson(String path, String body) throws Exception {
        return postJson(path, body, null);
    }

    private String registerBody(String email) {
        return """
                {
                  "email": "%s",
                  "password": "Passw0rd!",
                  "firstName": "Test",
                  "middleName": null,
                  "lastName": "User",
                  "birthDate": "2000-01-01",
                  "nationality": "CHN"
                }
                """.formatted(email);
    }

    private MockHttpServletResponse postJson(String path, String body, String clientIp) throws Exception {
        MvcResult mvcResult = mockMvc.perform(MockMvcRequestBuilders.post(path)
                        .header("X-Client-Ip", clientIp == null ? "" : clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        return mvcResult.getResponse();
    }

    private void assertEmailVerifiedFalse(JsonNode dataNode) {
        Assertions.assertTrue(dataNode.has("emailVerified"));
        Assertions.assertTrue(dataNode.path("emailVerified").isBoolean());
        Assertions.assertFalse(dataNode.path("emailVerified").asBoolean());
    }

    private JsonNode jwtPayload(String accessToken) throws Exception {
        String[] parts = accessToken.split("\\.");
        Assertions.assertEquals(3, parts.length);
        return objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private void clearSecurityKeys() {
        deleteKeysByPattern("falconx:auth:login:fail:*");
        deleteKeysByPattern("falconx:auth:register:limit:*");
        deleteKeysByPattern("falconx:auth:token:blacklist:*");
    }

    private void deleteKeysByPattern(String pattern) {
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }
}
