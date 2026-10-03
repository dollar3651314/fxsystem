package com.falconx.identity.entity;

import java.time.OffsetDateTime;

public record KycSubmission(
        Long id,
        Long userId,
        int level,
        KycSubmissionStatus status,
        KycIdType idType,
        String idNumber,
        OffsetDateTime submittedAt,
        Long reviewerId,
        OffsetDateTime reviewAt,
        String rejectReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
