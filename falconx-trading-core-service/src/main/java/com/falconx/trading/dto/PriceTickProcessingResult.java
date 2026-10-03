package com.falconx.trading.dto;

import com.falconx.trading.entity.TradingQuoteSnapshot;

/**
 * 行情 tick 处理结果。
 *
 * <p>当前阶段该对象主要用于确认 `market-service` 推来的最新价格
 * 已被交易核心接收并写入内部快照仓储，同时预留触发动作数量字段给后续限价单和强平引擎。
 */
public record PriceTickProcessingResult(
        TradingQuoteSnapshot snapshot,
        int triggeredActions
) {
}
