package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * STAGE-2-TRADING-MONITOR：管理端订单列表响应。
 */
public record AdminTradingOrderListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String orderNo,
            @JsonSerialize(using = ToStringSerializer.class) Long userId,
            String symbol,
            Integer side,
            Integer orderType,
            BigDecimal quantity,
            BigDecimal requestedPrice,
            BigDecimal filledPrice,
            BigDecimal leverage,
            BigDecimal margin,
            BigDecimal fee,
            BigDecimal openFeeRate,
            String clientOrderId,
            Integer status,
            String rejectReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
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
            return new Item(id, orderNo, userId, symbol, side, orderType, quantity, requestedPrice,
                    filledPrice, leverage, margin, fee, openFeeRate, clientOrderId, status, rejectReason,
                    createdAt, updatedAt, pricePrecision, userUid, userEmail, userFullName);
        }
    }
}
