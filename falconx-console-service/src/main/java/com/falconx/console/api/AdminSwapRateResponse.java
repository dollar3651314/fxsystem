package com.falconx.console.api;

import java.util.List;

/**
 * STAGE-2-SYMBOL: GET platform symbol swap-rate 响应（current 当前生效 + history 最近 5 条）。
 */
public record AdminSwapRateResponse(
        String symbol,
        AdminSymbolDetailResponse.SwapRateItem current,
        List<AdminSymbolDetailResponse.SwapRateItem> history
) {
}
