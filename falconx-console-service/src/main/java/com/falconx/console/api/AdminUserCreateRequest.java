package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 新建管理员请求体。
 */
public record AdminUserCreateRequest(
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$") String username,
        @Size(max = 64) String realName,
        @NotEmpty List<Long> roleIds,
        /** 显式密码（可空，缺省时后端生成 16 位随机）。 */
        String password,
        Boolean mustChangePassword
) {
}
