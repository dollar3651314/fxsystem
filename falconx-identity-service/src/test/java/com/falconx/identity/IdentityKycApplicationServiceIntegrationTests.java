package com.falconx.identity;

import com.falconx.identity.application.IdentityKycApplicationService;
import com.falconx.identity.application.IdentityKycApplicationService.DocumentPayload;
import com.falconx.identity.application.IdentityRegistrationApplicationService;
import com.falconx.identity.command.RegisterIdentityUserCommand;
import com.falconx.identity.contract.auth.RegisterResponse;
import com.falconx.identity.entity.KycDocumentType;
import com.falconx.identity.entity.KycIdType;
import com.falconx.identity.entity.KycSubmission;
import com.falconx.identity.entity.KycSubmissionStatus;
import com.falconx.identity.error.IdentityBusinessException;
import com.falconx.identity.error.IdentityErrorCode;
import com.falconx.identity.repository.mapper.test.IdentityTestSupportMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * STAGE-6-KYC identity 应用层提交 / 查询集成测试。
 *
 * <p>覆盖 TC-KYC-001~006（提交业务规则）+ TC-KYC-010~013（最新状态查询）。
 * 控制器层校验（idType 枚举、X-User-Id 缺失）见 {@link UserKycControllerIntegrationTests}。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = IdentityServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_identity_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.port=6380",
                "falconx.identity.security.register-limit=1000",
                "falconx.identity.security.login-failure-limit=100",
                "falconx.identity.kafka.consumer-group-id=identity-kyc-app-it-${random.uuid}"
        }
)
class IdentityKycApplicationServiceIntegrationTests {

    private static final String FRONT_B64 = base64Bytes("front-image-content");
    private static final String BACK_B64 = base64Bytes("back-image-content");
    private static final String SELFIE_B64 = base64Bytes("selfie-image-content");

    @Autowired
    private IdentityKycApplicationService kycService;

    @Autowired
    private IdentityRegistrationApplicationService registrationService;

    @Autowired
    private IdentityTestSupportMapper supportMapper;

    @BeforeEach
    void cleanOwnerTables() {
        supportMapper.clearOwnerTables();
    }

    /** TC-KYC-001 正常提交 → PENDING，submission + 3 文档 + sha256 计算正确。 */
    @Test
    void shouldPersistSubmissionAndDocumentsWhenFirstTimeSubmit() {
        long userId = registerUser("tc-kyc-001@example.com");

        KycSubmission submission = kycService.submit(
                userId,
                KycIdType.ID_CARD,
                "110101199001011234",
                buildDocs()
        );

        Assertions.assertEquals(KycSubmissionStatus.PENDING, submission.status());
        Assertions.assertNotNull(submission.id());
        Assertions.assertEquals(1, supportMapper.countKycSubmissionByUserId(userId));
        Assertions.assertEquals(3, supportMapper.countKycDocumentBySubmissionId(submission.id()));
        Assertions.assertEquals(sha256(FRONT_B64),
                supportMapper.selectKycDocumentSha256(submission.id(), KycDocumentType.ID_FRONT.code()));
        Assertions.assertEquals(sha256(BACK_B64),
                supportMapper.selectKycDocumentSha256(submission.id(), KycDocumentType.ID_BACK.code()));
        Assertions.assertEquals(sha256(SELFIE_B64),
                supportMapper.selectKycDocumentSha256(submission.id(), KycDocumentType.HOLDING_SELFIE.code()));
    }

    /** TC-KYC-002 缺少证件正面 → KYC_DOCUMENTS_INCOMPLETE。 */
    @Test
    void shouldThrowDocumentsIncompleteWhenIdFrontMissing() {
        long userId = registerUser("tc-kyc-002@example.com");
        Map<KycDocumentType, DocumentPayload> docs = buildDocs();
        docs.remove(KycDocumentType.ID_FRONT);

        IdentityBusinessException ex = Assertions.assertThrows(IdentityBusinessException.class,
                () -> kycService.submit(userId, KycIdType.ID_CARD, "110101199001011234", docs));
        Assertions.assertEquals(IdentityErrorCode.KYC_DOCUMENTS_INCOMPLETE.code(), ex.getErrorCode().code());
        Assertions.assertEquals(0, supportMapper.countKycSubmissionByUserId(userId));
    }

    /** TC-KYC-003 idNumber 空白 → KYC_ID_NUMBER_INVALID。 */
    @Test
    void shouldThrowIdNumberInvalidWhenBlank() {
        long userId = registerUser("tc-kyc-003@example.com");

        IdentityBusinessException ex = Assertions.assertThrows(IdentityBusinessException.class,
                () -> kycService.submit(userId, KycIdType.ID_CARD, "   ", buildDocs()));
        Assertions.assertEquals(IdentityErrorCode.KYC_ID_NUMBER_INVALID.code(), ex.getErrorCode().code());
        Assertions.assertEquals(0, supportMapper.countKycSubmissionByUserId(userId));
    }

    /** TC-KYC-004 PENDING 期重复提交 → KYC_PENDING_EXISTS。 */
    @Test
    void shouldThrowPendingExistsWhenSubmittingAgainWithPending() {
        long userId = registerUser("tc-kyc-004@example.com");
        kycService.submit(userId, KycIdType.ID_CARD, "110101199001011234", buildDocs());

        IdentityBusinessException ex = Assertions.assertThrows(IdentityBusinessException.class,
                () -> kycService.submit(userId, KycIdType.ID_CARD, "110101199001011234", buildDocs()));
        Assertions.assertEquals(IdentityErrorCode.KYC_PENDING_EXISTS.code(), ex.getErrorCode().code());
        Assertions.assertEquals(1, supportMapper.countKycSubmissionByUserId(userId));
    }

    /** TC-KYC-005 已 APPROVED 后重复提交 → KYC_ALREADY_APPROVED。 */
    @Test
    void shouldThrowAlreadyApprovedWhenSubmittingAfterApproved() {
        long userId = registerUser("tc-kyc-005@example.com");
        KycSubmission first = kycService.submit(userId, KycIdType.ID_CARD, "110101199001011234", buildDocs());
        supportMapper.forcePendingSubmissionToApproved(first.id());

        IdentityBusinessException ex = Assertions.assertThrows(IdentityBusinessException.class,
                () -> kycService.submit(userId, KycIdType.ID_CARD, "110101199001011234", buildDocs()));
        Assertions.assertEquals(IdentityErrorCode.KYC_ALREADY_APPROVED.code(), ex.getErrorCode().code());
        Assertions.assertEquals(1, supportMapper.countKycSubmissionByUserId(userId));
    }

    /** TC-KYC-006 REJECTED 后允许重新提交（旧 REJECTED 记录保留）。 */
    @Test
    void shouldAllowResubmitAfterRejected() {
        long userId = registerUser("tc-kyc-006@example.com");
        KycSubmission first = kycService.submit(userId, KycIdType.ID_CARD, "old-rejected", buildDocs());
        // reject 调用，最终态 REJECTED + 不影响 kyc_level
        kycService.reject(first.id(), 999L, "信息不清晰");

        KycSubmission second = kycService.submit(userId, KycIdType.ID_CARD, "new-pending", buildDocs());

        Assertions.assertEquals(KycSubmissionStatus.PENDING, second.status());
        Assertions.assertEquals(2, supportMapper.countKycSubmissionByUserId(userId));
    }

    /** TC-KYC-010 从未提交 → getLatestStatus 返回 empty。 */
    @Test
    void shouldReturnEmptyWhenNeverSubmitted() {
        long userId = registerUser("tc-kyc-010@example.com");
        Optional<KycSubmission> latest = kycService.getLatestStatus(userId);
        Assertions.assertTrue(latest.isEmpty());
    }

    /** TC-KYC-011 PENDING 查询返回最新。 */
    @Test
    void shouldReturnPendingSubmissionAsLatest() {
        long userId = registerUser("tc-kyc-011@example.com");
        kycService.submit(userId, KycIdType.ID_CARD, "110101199001011234", buildDocs());

        Optional<KycSubmission> latest = kycService.getLatestStatus(userId);
        Assertions.assertTrue(latest.isPresent());
        Assertions.assertEquals(KycSubmissionStatus.PENDING, latest.get().status());
        Assertions.assertNotNull(latest.get().submittedAt());
    }

    /** TC-KYC-012 REJECTED 后再 PENDING，查询返回最新 PENDING。 */
    @Test
    void shouldReturnLatestPendingAfterPreviousRejected() {
        long userId = registerUser("tc-kyc-012@example.com");
        KycSubmission first = kycService.submit(userId, KycIdType.ID_CARD, "old-id", buildDocs());
        kycService.reject(first.id(), 999L, "信息不清晰");
        KycSubmission second = kycService.submit(userId, KycIdType.ID_CARD, "new-id", buildDocs());

        Optional<KycSubmission> latest = kycService.getLatestStatus(userId);
        Assertions.assertTrue(latest.isPresent());
        Assertions.assertEquals(second.id(), latest.get().id());
        Assertions.assertEquals(KycSubmissionStatus.PENDING, latest.get().status());
    }

    /** TC-KYC-013 APPROVED 后查询返回 reviewAt + level=1（t_user.kyc_level 同事务更新）。 */
    @Test
    void shouldReflectApprovedReviewAtAndLevel() {
        long userId = registerUser("tc-kyc-013@example.com");
        KycSubmission first = kycService.submit(userId, KycIdType.ID_CARD, "110101199001011234", buildDocs());
        kycService.approve(first.id(), 999L);

        Optional<KycSubmission> latest = kycService.getLatestStatus(userId);
        Assertions.assertTrue(latest.isPresent());
        Assertions.assertEquals(KycSubmissionStatus.APPROVED, latest.get().status());
        Assertions.assertEquals(1, latest.get().level());
        Assertions.assertNotNull(latest.get().reviewAt());
        Assertions.assertEquals(1, supportMapper.selectKycLevelByUserId(userId));
    }

    private long registerUser(String email) {
        RegisterResponse r = registrationService.register(
                new RegisterIdentityUserCommand(email, "Passw0rd!", "127.0.0.1",
                        "Test", null, "User", LocalDate.of(2000, 1, 1), "CHN")
        );
        return r.userId();
    }

    private Map<KycDocumentType, DocumentPayload> buildDocs() {
        Map<KycDocumentType, DocumentPayload> docs = new HashMap<>();
        docs.put(KycDocumentType.ID_FRONT, new DocumentPayload(FRONT_B64, "image/jpeg"));
        docs.put(KycDocumentType.ID_BACK, new DocumentPayload(BACK_B64, "image/jpeg"));
        docs.put(KycDocumentType.HOLDING_SELFIE, new DocumentPayload(SELFIE_B64, "image/jpeg"));
        return docs;
    }

    private static String base64Bytes(String content) {
        return java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }
}
