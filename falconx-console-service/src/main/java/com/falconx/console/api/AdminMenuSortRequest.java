package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 菜单排序请求体（与同级相邻菜单交换 sort_order）。
 */
public record AdminMenuSortRequest(
        @NotBlank @Pattern(regexp = "up|down") String direction
) {
}
