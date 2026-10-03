package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * STAGE-2-TRADING-MONITOR：管理端持仓列表响应。
 */
public record AdminTradingPositionListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long openingOrderId,
            @JsonSerialize(using = ToStringSerializer.class) Long userId,
            String symbol,
            Integer side,
            BigDecimal quantity,
            BigDecimal entryPrice,
            BigDecimal leverage,
            BigDecimal margin,
            Integer marginMode,
            BigDecimal liquidationPrice,
            BigDecimal takeProfitPrice,
            BigDecimal stopLossPrice,
            BigDecimal closePrice,
            Integer closeReason,
            BigDecimal realizedPnl,
            BigDecimal openFeeRate,
            Integer status,
            LocalDateTime openedAt,
            LocalDateTime closedAt,
            LocalDateTime updatedAt,
            /** OPEN 持仓基于 quote snapshot 计算的实时 markPrice；非 OPEN 或缺 quote 为 null */
            BigDecimal markPrice,
            /** OPEN 持仓基于 markPrice 计算的浮动盈亏；非 OPEN 或缺 quote 为 null */
            BigDecimal unrealizedPnl,
            /** symbol 价格显示精度（透传 trading-core SymbolSpec.pricePrecision）；过渡期可能为 null。 */
            Integer pricePrecision,
            /** 跨 schema enrich：用户对外短号（t_user.uid）。null = 未 enrich / 用户不存在。 */
            String userUid,
            /** 跨 schema enrich：用户邮箱。null = 未 enrich。 */
            String userEmail,
            /** 跨 schema enrich：用户姓名（firstName + lastName，profile 未填为 null）。 */
            String userFullName
    ) {
        /** Builder-style helper：基于已有 item 覆写 3 个用户信息字段，主字段不变。 */
        public Item withUserInfo(String userUid, String userEmail, String userFullName) {
            return new Item(id, openingOrderId, userId, symbol, side, quantity, entryPrice, leverage,
                    margin, marginMode, liquidationPrice, takeProfitPrice, stopLossPrice, closePrice,
                    closeReason, realizedPnl, openFeeRate, status, openedAt, closedAt, updatedAt,
                    markPrice, unrealizedPnl, pricePrecision, userUid, userEmail, userFullName);
        }
    }
}
