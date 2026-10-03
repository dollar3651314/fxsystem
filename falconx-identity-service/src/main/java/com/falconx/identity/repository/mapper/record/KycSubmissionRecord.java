package com.falconx.identity.repository.mapper.record;

import java.time.LocalDateTime;

public record KycSubmissionRecord(
        Long id,
        Long userId,
        Integer level,
        Integer status,
        Integer idType,
        String idNumber,
        LocalDateTime submittedAt,
        Long reviewerId,
        LocalDateTime reviewAt,
        String rejectReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
