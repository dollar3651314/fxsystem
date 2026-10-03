package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * STAGE-8-NOTIFICATION Phase 3：手动发送通知 request body。
 *
 * <p>{@code reason} 是高危审计字段；OperationAuditAspect 将其写入
 * {@code t_admin_operation_log.reason}。前置校验 @NotBlank（90887 兜底 90887 由 service 抛）。
 */
public record AdminNotificationSendRequest(
        @NotNull @Pattern(regexp = "^\\d+$",
                          message = "userId must be snowflake id string of digits")
        String userId,
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$")
        String templateCode,
        Map<String, String> params,
        @NotBlank @Size(min = 1, max = 512) String reason
) {
}
