package com.falconx.identity.controller;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.falconx.identity.IdentityServiceApplication;
import com.falconx.identity.application.IdentityKycApplicationService;
import com.falconx.identity.application.IdentityKycApplicationService.DocumentPayload;
import com.falconx.identity.application.IdentityRegistrationApplicationService;
import com.falconx.identity.command.RegisterIdentityUserCommand;
import com.falconx.identity.config.IdentityTraceContextFilter;
import com.falconx.identity.contract.auth.RegisterResponse;
import com.falconx.identity.entity.KycDocumentType;
import com.falconx.identity.entity.KycIdType;
import com.falconx.identity.entity.KycSubmission;
import com.falconx.identity.error.IdentityErrorCode;
import com.falconx.identity.repository.mapper.test.IdentityTestSupportMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * STAGE-6-KYC admin internal RPC 集成测试。
 *
 * <p>覆盖 TC-KYC-020~046（admin 列表 / 详情 / approve / reject）。
 * 端点：{@code /internal/v1/identity/kyc}。
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
                "falconx.identity.kafka.consumer-group-id=identity-admin-kyc-ctl-it-${random.uuid}"
        }
)
class AdminInternalKycControllerIntegrationTests {

    private static final String FRONT_B64 = encode("admin-front");
    private static final String BACK_B64 = encode("admin-back");
    private static final String SELFIE_B64 = encode("admin-selfie");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private IdentityTraceContextFilter identityTraceContextFilter;

    @Autowired
    private IdentityRegistrationApplicationService registrationService;

    @Autowired
    private IdentityKycApplicationService kycService;

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

    /** TC-KYC-020 无过滤返回全部 + page/pageSize/total。 */
    @Test
    void shouldListAllWhenNoFilter() throws Exception {
        long u1 = registerUser("admin-list-1@example.com");
        long u2 = registerUser("admin-list-2@example.com");
        submitFor(u1);
        submitFor(u2);

        mockMvc.perform(get("/internal/v1/identity/kyc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.items.length()").value(greaterThanOrEqualTo(2)));
    }

    /** TC-KYC-021 按 status=PENDING 过滤仅返回 PENDING。 */
    @Test
    void shouldFilterByStatusPending() throws Exception {
        long u = registerUser("admin-list-pending@example.com");
        long u2 = registerUser("admin-list-approved@example.com");
        KycSubmission pending = submitFor(u);
        KycSubmission approved = submitFor(u2);
        kycService.approve(approved.id(), 999L);

        mockMvc.perform(get("/internal/v1/identity/kyc").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.submissionId=='" + pending.id() + "')]").exists())
                .andExpect(jsonPath("$.data.items[?(@.submissionId=='" + approved.id() + "')]").doesNotExist());
    }

    /** TC-KYC-022 按 userId 过滤仅返回该用户。 */
    @Test
    void shouldFilterByUserId() throws Exception {
        long u1 = registerUser("admin-list-u1@example.com");
        long u2 = registerUser("admin-list-u2@example.com");
        submitFor(u1);
        submitFor(u2);

        mockMvc.perform(get("/internal/v1/identity/kyc").param("userId", String.valueOf(u1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].userId").value(String.valueOf(u1)));
    }

    /** TC-KYC-023 分页（page=2, size=2）返回第 3-4 条 + total 准确。 */
    @Test
    void shouldRespectPagination() throws Exception {
        for (int i = 0; i < 5; i++) {
            long u = registerUser("admin-list-page-" + i + "@example.com");
            submitFor(u);
        }

        mockMvc.perform(get("/internal/v1/identity/kyc")
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    /** TC-KYC-024 默认按 submittedAt DESC 排序（最新在前）。 */
    @Test
    void shouldSortBySubmittedAtDesc() throws Exception {
        long u1 = registerUser("admin-list-sort-1@example.com");
        long u2 = registerUser("admin-list-sort-2@example.com");
        KycSubmission first = submitFor(u1);
        // 间隔 5ms 保证 submittedAt 不同
        Thread.sleep(5);
        KycSubmission second = submitFor(u2);

        mockMvc.perform(get("/internal/v1/identity/kyc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].submissionId").value(String.valueOf(second.id())))
                .andExpect(jsonPath("$.data.items[1].submissionId").value(String.valueOf(first.id())));
    }

    /** TC-KYC-030 详情返回 submission + 3 个文档（含 dataBase64 + sha256）。 */
    @Test
    void shouldReturnDetailWith3Documents() throws Exception {
        long userId = registerUser("admin-detail@example.com");
        KycSubmission s = submitFor(userId);

        mockMvc.perform(get("/internal/v1/identity/kyc/" + s.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.submission.submissionId").value(String.valueOf(s.id())))
                .andExpect(jsonPath("$.data.documents.length()").value(3))
                .andExpect(jsonPath("$.data.documents[0].dataBase64").exists())
                .andExpect(jsonPath("$.data.documents[0].sha256").exists());
    }

    /** TC-KYC-031 不存在 submissionId → 错误码 10044。 */
    @Test
    void shouldReturnNotFoundWhenSubmissionDoesNotExist() throws Exception {
        mockMvc.perform(get("/internal/v1/identity/kyc/999999999"))
                .andExpect(jsonPath("$.code").value(IdentityErrorCode.KYC_SUBMISSION_NOT_FOUND.code()));
    }

    /** TC-KYC-032 documents 排序固定 ID_FRONT(1) / ID_BACK(2) / HOLDING_SELFIE(3)。 */
    @Test
    void shouldReturnDocumentsInFixedOrder() throws Exception {
        long userId = registerUser("admin-detail-order@example.com");
        KycSubmission s = submitFor(userId);

        mockMvc.perform(get("/internal/v1/identity/kyc/" + s.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents[0].docType").value(KycDocumentType.ID_FRONT.name()))
                .andExpect(jsonPath("$.data.documents[1].docType").value(KycDocumentType.ID_BACK.name()))
                .andExpect(jsonPath("$.data.documents[2].docType").value(KycDocumentType.HOLDING_SELFIE.name()));
    }

    /** TC-KYC-040 PENDING approve → status=APPROVED + t_user.kyc_level=1 + reviewer/reviewAt 落库。 */
    @Test
    void shouldApprovePendingAndUpdateUserKycLevel() throws Exception {
        long userId = registerUser("admin-approve@example.com");
        KycSubmission s = submitFor(userId);

        mockMvc.perform(post("/internal/v1/identity/kyc/" + s.id() + "/approve")
                        .header("X-Admin-User-Id", "777")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        Assertions.assertEquals(Integer.valueOf(1), supportMapper.selectKycSubmissionStatusById(s.id()));
        Assertions.assertEquals(Long.valueOf(777L), supportMapper.selectKycReviewerIdById(s.id()));
        Assertions.assertNotNull(supportMapper.selectKycReviewAtById(s.id()));
        Assertions.assertEquals(Integer.valueOf(1), supportMapper.selectKycLevelByUserId(userId));
    }

    /** TC-KYC-041 非 PENDING approve → 10045。 */
    @Test
    void shouldRejectApproveWhenNotPending() throws Exception {
        long userId = registerUser("admin-approve-not-pending@example.com");
        KycSubmission s = submitFor(userId);
        kycService.approve(s.id(), 999L);

        mockMvc.perform(post("/internal/v1/identity/kyc/" + s.id() + "/approve")
                        .header("X-Admin-User-Id", "777")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(IdentityErrorCode.KYC_NOT_PENDING.code()));
    }

    /** TC-KYC-042 PENDING reject + 原因 → REJECTED；t_user.kyc_level 不变。 */
    @Test
    void shouldRejectPendingWithReasonAndKeepKycLevel() throws Exception {
        long userId = registerUser("admin-reject@example.com");
        KycSubmission s = submitFor(userId);

        mockMvc.perform(post("/internal/v1/identity/kyc/" + s.id() + "/reject")
                        .header("X-Admin-User-Id", "777")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"证件不清晰\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReason").value("证件不清晰"));

        Assertions.assertEquals(Integer.valueOf(2), supportMapper.selectKycSubmissionStatusById(s.id()));
        Assertions.assertEquals("证件不清晰", supportMapper.selectKycRejectReasonById(s.id()));
        Assertions.assertEquals(Integer.valueOf(0), supportMapper.selectKycLevelByUserId(userId));
    }

    /** TC-KYC-043 reject 原因为空 → 业务码 10046（HTTP 200，code 在 body）。 */
    @Test
    void shouldRejectRejectWhenReasonBlank() throws Exception {
        long userId = registerUser("admin-reject-no-reason@example.com");
        KycSubmission s = submitFor(userId);

        mockMvc.perform(post("/internal/v1/identity/kyc/" + s.id() + "/reject")
                        .header("X-Admin-User-Id", "777")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(jsonPath("$.code").value(IdentityErrorCode.KYC_REJECT_REASON_REQUIRED.code()));
        // status PENDING 未变
        Assertions.assertEquals(Integer.valueOf(0), supportMapper.selectKycSubmissionStatusById(s.id()));
    }

    /** TC-KYC-044 不存在 submissionId approve → 10044。 */
    @Test
    void shouldReturn404WhenApproveNonExistent() throws Exception {
        mockMvc.perform(post("/internal/v1/identity/kyc/9999999999/approve")
                        .header("X-Admin-User-Id", "777")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(IdentityErrorCode.KYC_SUBMISSION_NOT_FOUND.code()));
    }

    /** TC-KYC-045 X-Admin-User-Id 缺失 → 拒绝处理（非 200）。 */
    @Test
    void shouldRejectWhenAdminHeaderMissing() throws Exception {
        long userId = registerUser("admin-approve-no-header@example.com");
        KycSubmission s = submitFor(userId);

        mockMvc.perform(post("/internal/v1/identity/kyc/" + s.id() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    Assertions.assertTrue(sc == 400 || sc == 401 || sc == 500,
                            "缺失 X-Admin-User-Id 应拒绝，但 HTTP " + sc);
                });
        // submission 未被审核
        Assertions.assertEquals(Integer.valueOf(0), supportMapper.selectKycSubmissionStatusById(s.id()));
    }

    /**
     * TC-KYC-046 并发 approve（CAS）：仅一个成功，另一个返回 10045。
     *
     * <p>语义：{@code MybatisKycRepository.updateReview} 的 SQL
     * {@code UPDATE ... WHERE id=? AND status=0} 形成 CAS：第二次 update affected=0 →
     * 应用层返回 KYC_NOT_PENDING。
     */
    @Test
    void shouldOnlyAllowOneConcurrentApprove() throws Exception {
        long userId = registerUser("admin-approve-concurrent@example.com");
        KycSubmission s = submitFor(userId);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger notPendingCount = new AtomicInteger();
        ExecutorService exec = Executors.newFixedThreadPool(2);
        CompletableFuture<?> f1 = CompletableFuture.runAsync(() -> {
            try {
                latch.await();
                kycService.approve(s.id(), 700L);
                successCount.incrementAndGet();
            } catch (com.falconx.identity.error.IdentityBusinessException e) {
                if (e.getErrorCode() == IdentityErrorCode.KYC_NOT_PENDING) {
                    notPendingCount.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, exec);
        CompletableFuture<?> f2 = CompletableFuture.runAsync(() -> {
            try {
                latch.await();
                kycService.approve(s.id(), 701L);
                successCount.incrementAndGet();
            } catch (com.falconx.identity.error.IdentityBusinessException e) {
                if (e.getErrorCode() == IdentityErrorCode.KYC_NOT_PENDING) {
                    notPendingCount.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, exec);
        latch.countDown();
        CompletableFuture.allOf(f1, f2).get(10, TimeUnit.SECONDS);
        exec.shutdownNow();

        Assertions.assertEquals(1, successCount.get(), "仅一个并发 approve 应成功");
        Assertions.assertEquals(1, notPendingCount.get(), "另一个应返回 KYC_NOT_PENDING");
        Assertions.assertEquals(Integer.valueOf(1), supportMapper.selectKycSubmissionStatusById(s.id()));
    }

    private long registerUser(String email) {
        RegisterResponse r = registrationService.register(
                new RegisterIdentityUserCommand(email, "Passw0rd!", "127.0.0.1",
                        "Test", null, "User", LocalDate.of(2000, 1, 1), "CHN")
        );
        return r.userId();
    }

    private KycSubmission submitFor(long userId) {
        Map<KycDocumentType, DocumentPayload> docs = new HashMap<>();
        docs.put(KycDocumentType.ID_FRONT, new DocumentPayload(FRONT_B64, "image/jpeg"));
        docs.put(KycDocumentType.ID_BACK, new DocumentPayload(BACK_B64, "image/jpeg"));
        docs.put(KycDocumentType.HOLDING_SELFIE, new DocumentPayload(SELFIE_B64, "image/jpeg"));
        return kycService.submit(userId, KycIdType.ID_CARD, "110101199001011234", docs);
    }

    private static String encode(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
