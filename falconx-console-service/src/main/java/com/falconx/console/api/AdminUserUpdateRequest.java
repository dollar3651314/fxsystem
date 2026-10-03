package com.falconx.console.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 编辑管理员请求体（不允许改 username；status 通过 disable/enable 改）。
 */
public record AdminUserUpdateRequest(
        @Size(max = 64) String realName,
        @NotEmpty List<Long> roleIds
) {
}
