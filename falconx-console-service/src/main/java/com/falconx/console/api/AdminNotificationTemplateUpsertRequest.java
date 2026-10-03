package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * STAGE-8-NOTIFICATION Phase 3：模板新建 / 编辑 request body。
 *
 * <p>编辑模式下 {@code code} 来自 path variable；request body 中 code 字段被忽略。
 */
public record AdminNotificationTemplateUpsertRequest(
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$",
                 message = "code must match ^[A-Z][A-Z0-9_]{2,63}$")
        String code,
        @NotBlank @Size(max = 255) String titleTemplate,
        @NotBlank @Size(max = 2048) String bodyTemplate,
        @NotBlank @Pattern(regexp = "INFO|WARN|CRITICAL") String level,
        @NotEmpty List<String> channels,
        @Size(max = 512) String description,
        @NotNull Boolean enabled
) {
}
