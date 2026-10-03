package com.falconx.identity.entity;

import java.time.OffsetDateTime;

public record KycDocument(
        Long id,
        Long submissionId,
        KycDocumentType docType,
        String dataBase64,
        String sha256,
        String mimeType,
        OffsetDateTime createdAt
) {
}
