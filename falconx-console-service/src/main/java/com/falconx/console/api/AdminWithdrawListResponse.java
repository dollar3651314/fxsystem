package com.falconx.console.api;

import java.util.List;

/**
 * STAGE-7-WITHDRAW Phase 4：管理端出金审核列表分页响应。
 */
public record AdminWithdrawListResponse(
        int page,
        int pageSize,
        long total,
        List<AdminWithdrawItem> items
) {
}
