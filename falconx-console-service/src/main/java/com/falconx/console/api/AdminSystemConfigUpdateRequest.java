package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** STAGE-13 PUT /admin/system-config/{configKey} 请求体。 */
public record AdminSystemConfigUpdateRequest(
        @NotBlank String newValue,
        @Size(max = 255) String reason
) {
}
