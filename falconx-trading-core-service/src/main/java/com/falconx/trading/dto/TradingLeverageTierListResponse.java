package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 杠杆/MM 档位查询响应（GET /api/v1/trading/symbols/{symbol}/leverage-tiers）。
 *
 * <p>B 切片（杠杆 tier 护栏，2026-06-03）：供客户端下单面板按名义价值动态降档——
 * 数量/价格变化时算 notional(AC) 落档，杠杆上限随档位收紧并提示「当前档位最大 Nx」，
 * 替代仅按 SymbolSpec.maxLeverage（=tier1 上限）的静态校验（跨档不动态 → 30070）。
 *
 * <p>档位口径与开仓风控同源（同 SymbolLeverageTierRepository.findTiers，含 default 组回退）。
 *
 * @param symbol        平台 symbol
 * @param groupCode     实际命中的用户组（请求组无配置时回退 default）
 * @param quoteCurrency 计价币代码（QC，来源 SymbolSpec）；过渡期可能为 null
 * @param fxRate        QC→账户币（USDT）当前汇率，客户端用 qty×price×fxRate 估算 notional(AC)；
 *                      同币种为 1，FX 不可用为 null（客户端降级只按 tier1 上限）
 * @param tiers         档位列表（tierNo 升序）；空数组 = 该 symbol 无 tier 配置
 */
public record TradingLeverageTierListResponse(
        String symbol,
        String groupCode,
        String quoteCurrency,
        BigDecimal fxRate,
        List<Item> tiers
) {

    /**
     * @param notionalUpper 档位上界（不含，账户币）；null = 最高档无上限
     */
    public record Item(
            int tierNo,
            BigDecimal notionalLower,
            BigDecimal notionalUpper,
            int maxLeverage,
            BigDecimal mmRate
    ) {
    }
}
