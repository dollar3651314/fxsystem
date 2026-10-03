package com.falconx.console.api;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 重置密码响应（仅当 request.password=null 时返回 generatedPassword）。
 */
public record AdminUserResetPasswordResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String generatedPassword
) {
}
