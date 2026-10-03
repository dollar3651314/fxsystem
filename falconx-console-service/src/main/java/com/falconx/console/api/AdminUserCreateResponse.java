package com.falconx.console.api;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 新建管理员响应（仅本次返回 generatedPassword）。
 */
public record AdminUserCreateResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String username,
        /** 仅当 request.password=null 时返回；本次后不再可查。 */
        String generatedPassword
) {
}
