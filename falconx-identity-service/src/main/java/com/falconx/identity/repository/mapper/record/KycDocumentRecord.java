package com.falconx.identity.repository.mapper.record;

import java.time.LocalDateTime;

public record KycDocumentRecord(
        Long id,
        Long submissionId,
        Integer docType,
        String dataBase64,
        String sha256,
        String mimeType,
        LocalDateTime createdAt
) {
}
