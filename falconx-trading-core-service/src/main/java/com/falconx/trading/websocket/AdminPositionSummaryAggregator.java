package com.falconx.trading.websocket;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 管理端持仓「总未实现盈亏」聚合器。
 *
 * <p>QuoteDrivenEngine 每条 tick 计算完该 symbol 的全部 OPEN 持仓 PnL 后，把
 * Σ unrealizedPnl 写入本聚合器（per-symbol 覆盖式更新）。平台总未实现盈亏 =
 * 跨 symbol 求和。
 *
 * <p>SUM 复杂度 O(symbols)，目前 1571 symbols 单次 ~μs 级。
 * 与 admin.position.summary 推送配套使用（推送由 AdminPositionSummaryPushThrottler 500ms 节流）。
 */
@Component
public class AdminPositionSummaryAggregator {

    private final Map<String, BigDecimal> unrealizedPnlBySymbol = new ConcurrentHashMap<>();

    /**
     * 覆盖某 symbol 的 Σ unrealizedPnl。null 表示该 symbol 当前无 OPEN 持仓 → 移除条目。
     */
    public void updateSymbol(String symbol, BigDecimal totalForSymbol) {
        if (symbol == null || symbol.isBlank()) return;
        if (totalForSymbol == null) {
            unrealizedPnlBySymbol.remove(symbol);
        } else {
            unrealizedPnlBySymbol.put(symbol, totalForSymbol);
        }
    }

    /**
     * 平台所有 symbol 的 Σ unrealizedPnl 之和。零持仓 / 全部缺 quote 时返回 0。
     */
    public BigDecimal platformTotalUnrealizedPnl() {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : unrealizedPnlBySymbol.values()) {
            if (value != null) sum = sum.add(value);
        }
        return sum;
    }

    /** 测试 / 监控用：当前有 PnL 数据的 symbol 数。 */
    public int trackedSymbolCount() {
        return unrealizedPnlBySymbol.size();
    }
}
