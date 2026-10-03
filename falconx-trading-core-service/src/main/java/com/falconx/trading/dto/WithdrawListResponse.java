package com.falconx.trading.dto;

import java.util.List;

/**
 * STAGE-7-WITHDRAW：出金单分页列表响应。
 */
public record WithdrawListResponse(
        int page,
        int pageSize,
        long total,
        List<WithdrawOrderResponse> items
) {
}
