package com.falconx.trading.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * STAGE-7-WITHDRAW Phase 2：用户新增白名单请求体。
 *
 * <p>详细业务规则见 docs/api/REST接口规范.md §9.2.6。
 */
public record AddWhitelistRequest(
        @NotBlank @Size(max = 16) String network,
        @NotBlank @Size(max = 128) String address,
        @Size(max = 64) String label
) {
}
