package com.falconx.console.api;

import java.util.List;

public record AdminKycDetailResponse(
        AdminKycItem submission,
        List<AdminKycDocumentItem> documents
) {
    public record AdminKycDocumentItem(String id, String docType, String mimeType, String dataBase64, String sha256) {}
}
