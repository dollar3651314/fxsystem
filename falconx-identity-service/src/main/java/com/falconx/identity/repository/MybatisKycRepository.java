package com.falconx.identity.repository;

import com.falconx.identity.entity.KycDocument;
import com.falconx.identity.entity.KycDocumentType;
import com.falconx.identity.entity.KycIdType;
import com.falconx.identity.entity.KycSubmission;
import com.falconx.identity.entity.KycSubmissionStatus;
import com.falconx.identity.repository.mapper.KycSubmissionMapper;
import com.falconx.identity.repository.mapper.record.KycDocumentRecord;
import com.falconx.identity.repository.mapper.record.KycSubmissionRecord;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisKycRepository implements KycRepository {

    private final KycSubmissionMapper mapper;

    public MybatisKycRepository(KycSubmissionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertSubmission(KycSubmission s) {
        LocalDateTime now = s.createdAt() == null
                ? LocalDateTime.now(ZoneOffset.UTC) : s.createdAt().toLocalDateTime();
        mapper.insertSubmission(new KycSubmissionRecord(
                s.id(), s.userId(), s.level(), s.status().code(),
                s.idType().code(), s.idNumber(),
                s.submittedAt() == null ? now : s.submittedAt().toLocalDateTime(),
                s.reviewerId(),
                s.reviewAt() == null ? null : s.reviewAt().toLocalDateTime(),
                s.rejectReason(),
                now,
                now
        ));
    }

    @Override
    public void insertDocument(KycDocument d) {
        LocalDateTime now = d.createdAt() == null
                ? LocalDateTime.now(ZoneOffset.UTC) : d.createdAt().toLocalDateTime();
        mapper.insertDocument(new KycDocumentRecord(
                d.id(), d.submissionId(), d.docType().code(),
                d.dataBase64(), d.sha256(),
                d.mimeType() == null ? "image/jpeg" : d.mimeType(),
                now
        ));
    }

    @Override
    public Optional<KycSubmission> findById(long id) {
        return Optional.ofNullable(toSubmission(mapper.selectById(id)));
    }

    @Override
    public Optional<KycSubmission> findLatestByUserId(long userId) {
        return Optional.ofNullable(toSubmission(mapper.selectLatestByUserId(userId)));
    }

    @Override
    public boolean hasPending(long userId) {
        Integer cnt = mapper.countPendingByUserId(userId);
        return cnt != null && cnt > 0;
    }

    @Override
    public List<KycSubmission> findAdminPaginated(KycSubmissionStatus status, Long userId, int offset, int limit) {
        Integer statusCode = status == null ? null : status.code();
        return mapper.selectAdminPaginated(statusCode, userId, offset, limit).stream()
                .map(this::toSubmission).toList();
    }

    @Override
    public long countAdminFiltered(KycSubmissionStatus status, Long userId) {
        Integer statusCode = status == null ? null : status.code();
        return mapper.countAdminFiltered(statusCode, userId);
    }

    @Override
    public List<KycDocument> findDocumentsBySubmission(long submissionId) {
        return mapper.selectDocumentsBySubmission(submissionId).stream().map(this::toDocument).toList();
    }

    @Override
    public boolean updateReview(long id, KycSubmissionStatus status, long reviewerId,
                                 OffsetDateTime reviewAt, String rejectReason) {
        return mapper.updateReview(id, status.code(), reviewerId,
                reviewAt == null ? LocalDateTime.now(ZoneOffset.UTC) : reviewAt.toLocalDateTime(),
                rejectReason) > 0;
    }

    @Override
    public int updateUserKycLevel(long userId, int level) {
        return mapper.updateUserKycLevel(userId, level);
    }

    private KycSubmission toSubmission(KycSubmissionRecord r) {
        if (r == null) return null;
        return new KycSubmission(
                r.id(), r.userId(),
                r.level() == null ? 1 : r.level(),
                KycSubmissionStatus.fromCode(r.status() == null ? 0 : r.status()),
                KycIdType.fromCode(r.idType() == null ? 1 : r.idType()),
                r.idNumber(),
                r.submittedAt() == null ? null : r.submittedAt().atOffset(ZoneOffset.UTC),
                r.reviewerId(),
                r.reviewAt() == null ? null : r.reviewAt().atOffset(ZoneOffset.UTC),
                r.rejectReason(),
                r.createdAt() == null ? null : r.createdAt().atOffset(ZoneOffset.UTC),
                r.updatedAt() == null ? null : r.updatedAt().atOffset(ZoneOffset.UTC)
        );
    }

    private KycDocument toDocument(KycDocumentRecord r) {
        if (r == null) return null;
        return new KycDocument(
                r.id(), r.submissionId(),
                KycDocumentType.fromCode(r.docType() == null ? 1 : r.docType()),
                r.dataBase64(), r.sha256(), r.mimeType(),
                r.createdAt() == null ? null : r.createdAt().atOffset(ZoneOffset.UTC)
        );
    }
}
