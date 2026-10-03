package com.falconx.identity.repository;

import com.falconx.identity.entity.KycDocument;
import com.falconx.identity.entity.KycSubmission;
import com.falconx.identity.entity.KycSubmissionStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface KycRepository {
    void insertSubmission(KycSubmission submission);
    void insertDocument(KycDocument document);
    Optional<KycSubmission> findById(long id);
    Optional<KycSubmission> findLatestByUserId(long userId);
    boolean hasPending(long userId);
    List<KycSubmission> findAdminPaginated(KycSubmissionStatus status, Long userId, int offset, int limit);
    long countAdminFiltered(KycSubmissionStatus status, Long userId);
    List<KycDocument> findDocumentsBySubmission(long submissionId);
    /** 审核操作；返回是否更新（已审核的行不允许再次更新）。 */
    boolean updateReview(long id, KycSubmissionStatus status, long reviewerId,
                         OffsetDateTime reviewAt, String rejectReason);
    /** 同步 t_user.kyc_level；返回影响行数。 */
    int updateUserKycLevel(long userId, int level);
}
