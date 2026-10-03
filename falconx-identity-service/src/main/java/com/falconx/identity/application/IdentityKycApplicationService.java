package com.falconx.identity.application;

import com.falconx.identity.contract.event.KycReviewedEventPayload;
import com.falconx.identity.entity.KycDocument;
import com.falconx.identity.entity.KycDocumentType;
import com.falconx.identity.entity.KycIdType;
import com.falconx.identity.entity.KycSubmission;
import com.falconx.identity.entity.KycSubmissionStatus;
import com.falconx.identity.error.IdentityBusinessException;
import com.falconx.identity.error.IdentityErrorCode;
import com.falconx.identity.producer.IdentityKafkaEventPublisher;
import com.falconx.identity.repository.KycRepository;
import com.falconx.infrastructure.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * STAGE-6-KYC Phase 1：用户 KYC 提交 / 状态查询 / 管理端审核应用服务。
 */
@Service
public class IdentityKycApplicationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityKycApplicationService.class);

    private final KycRepository kycRepository;
    private final IdGenerator idGenerator;
    private final IdentityKafkaEventPublisher kafkaEventPublisher;

    public IdentityKycApplicationService(KycRepository kycRepository,
                                          IdGenerator idGenerator,
                                          IdentityKafkaEventPublisher kafkaEventPublisher) {
        this.kycRepository = kycRepository;
        this.idGenerator = idGenerator;
        this.kafkaEventPublisher = kafkaEventPublisher;
    }

    /**
     * 用户提交 KYC。
     *
     * <p>幂等约束：用户当前有 PENDING 申请时再次提交直接返回 409，避免重复审核。
     * 已 APPROVED 的用户重复提交：当前业务不允许（升级 KYC 等级是 V2 后续工作），返回 409。
     */
    @Transactional
    public KycSubmission submit(long userId,
                                 KycIdType idType,
                                 String idNumber,
                                 Map<KycDocumentType, DocumentPayload> documents) {
        if (idNumber == null || idNumber.isBlank()) {
            throw new IdentityBusinessException(IdentityErrorCode.KYC_ID_NUMBER_INVALID);
        }
        if (documents == null
                || !documents.containsKey(KycDocumentType.ID_FRONT)
                || !documents.containsKey(KycDocumentType.ID_BACK)
                || !documents.containsKey(KycDocumentType.HOLDING_SELFIE)) {
            throw new IdentityBusinessException(IdentityErrorCode.KYC_DOCUMENTS_INCOMPLETE);
        }
        if (kycRepository.hasPending(userId)) {
            throw new IdentityBusinessException(IdentityErrorCode.KYC_PENDING_EXISTS);
        }
        Optional<KycSubmission> latest = kycRepository.findLatestByUserId(userId);
        if (latest.isPresent() && latest.get().status() == KycSubmissionStatus.APPROVED) {
            throw new IdentityBusinessException(IdentityErrorCode.KYC_ALREADY_APPROVED);
        }

        long submissionId = idGenerator.nextId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        kycRepository.insertSubmission(new KycSubmission(
                submissionId, userId, 1,
                KycSubmissionStatus.PENDING,
                idType, idNumber.trim(),
                now, null, null, null,
                now, now
        ));
        for (Map.Entry<KycDocumentType, DocumentPayload> entry : documents.entrySet()) {
            DocumentPayload p = entry.getValue();
            kycRepository.insertDocument(new KycDocument(
                    idGenerator.nextId(),
                    submissionId,
                    entry.getKey(),
                    p.dataBase64(),
                    sha256(p.dataBase64()),
                    p.mimeType() == null || p.mimeType().isBlank() ? "image/jpeg" : p.mimeType(),
                    now
            ));
        }
        log.info("identity.kyc.submitted userId={} submissionId={} idType={} docCount={}",
                userId, submissionId, idType, documents.size());
        return kycRepository.findById(submissionId).orElseThrow();
    }

    public Optional<KycSubmission> getLatestStatus(long userId) {
        return kycRepository.findLatestByUserId(userId);
    }

    public List<KycSubmission> listForAdmin(KycSubmissionStatus status, Long userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return kycRepository.findAdminPaginated(status, userId, (safePage - 1) * safeSize, safeSize);
    }

    public long countForAdmin(KycSubmissionStatus status, Long userId) {
        return kycRepository.countAdminFiltered(status, userId);
    }

    public KycSubmission detailForAdmin(long submissionId) {
        return kycRepository.findById(submissionId)
                .orElseThrow(() -> new IdentityBusinessException(IdentityErrorCode.KYC_SUBMISSION_NOT_FOUND));
    }

    public List<KycDocument> documentsForAdmin(long submissionId) {
        return kycRepository.findDocumentsBySubmission(submissionId);
    }

    @Transactional
    public KycSubmission approve(long submissionId, long reviewerId) {
        KycSubmission s = detailForAdmin(submissionId);
        if (s.status() != KycSubmissionStatus.PENDING) {
            throw new IdentityBusinessException(IdentityErrorCode.KYC_NOT_PENDING);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!kycRepository.updateReview(submissionId, KycSubmissionStatus.APPROVED, reviewerId, now, null)) {
            throw new IdentityBusinessException(IdentityErrorCode.KYC_NOT_PENDING);
        }
        kycRepository.updateUserKycLevel(s.userId(), 1);
        log.info("identity.kyc.approved submissionId={} userId={} reviewerId={}",
                submissionId, s.userId(), reviewerId);
        KycSubmission reviewed = kycRepository.findById(submissionId).orElseThrow();
        publishReviewedAfterCommit(reviewed, "APPROVED", 1, null);
        return reviewed;
    }

    @Transactional
    public KycSubmission reject(long submissionId, long reviewerId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IdentityBusinessException(IdentityErrorCode.KYC_REJECT_REASON_REQUIRED);
        }
        KycSubmission s = detailForAdmin(submissionId);
        if (s.status() != KycSubmissionStatus.PENDING) {
            throw new IdentityBusinessException(IdentityErrorCode.KYC_NOT_PENDING);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String trimmedReason = reason.trim();
        if (!kycRepository.updateReview(submissionId, KycSubmissionStatus.REJECTED, reviewerId, now, trimmedReason)) {
            throw new IdentityBusinessException(IdentityErrorCode.KYC_NOT_PENDING);
        }
        log.info("identity.kyc.rejected submissionId={} userId={} reviewerId={} reason={}",
                submissionId, s.userId(), reviewerId, reason);
        KycSubmission reviewed = kycRepository.findById(submissionId).orElseThrow();
        publishReviewedAfterCommit(reviewed, "REJECTED", 0, trimmedReason);
        return reviewed;
    }

    /**
     * STAGE-6-KYC Phase 4：注册事务后发布 KYC reviewed 事件。
     *
     * <p>必须放在 afterCommit，确保只有事务真正落库后才发事件——失败重试不会回滚库。
     * 测试场景（无事务上下文）直接发布。
     */
    private void publishReviewedAfterCommit(KycSubmission reviewed,
                                             String result,
                                             int kycLevel,
                                             String rejectReason) {
        KycReviewedEventPayload payload = new KycReviewedEventPayload(
                reviewed.id(),
                reviewed.userId(),
                result,
                kycLevel,
                reviewed.reviewerId(),
                reviewed.reviewAt(),
                rejectReason
        );
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kafkaEventPublisher.publishKycReviewed(payload);
                }
            });
        } else {
            kafkaEventPublisher.publishKycReviewed(payload);
        }
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

    public record DocumentPayload(String dataBase64, String mimeType) {}
}
