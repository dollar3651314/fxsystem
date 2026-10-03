package com.falconx.market.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 用户组对单个平台 symbol 的双向加点配置项。
 *
 * <p>该对象冻结跨服务结构（market-service → trading-core / console-service），
 * 用于 internal RPC 列表 / 增量响应。
 *
 * <p>语义：在平台基准价 Layer 1 之上，对应组应用 {@code bidExtra / askExtra}。
 * 允许负值（VIP 让利），CHECK 范围在数据库层兜底。
 *
 * @param groupCode 用户组代码，对应 {@code t_user.group_code}
 * @param platformSymbol 平台 symbol，对应 {@code t_symbol_quote_mapping.platform_symbol}
 * @param bidExtra Bid 组级加点（可负）
 * @param askExtra Ask 组级加点（可负）
 * @param enabled 是否启用（0 等价于无加点）
 * @param updatedAt 配置最近更新时间，trading-core 用于增量刷新
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketGroupMarkupItem(
        @NotBlank String groupCode,
        @NotBlank String platformSymbol,
        @NotNull BigDecimal bidExtra,
        @NotNull BigDecimal askExtra,
        boolean enabled,
        @NotNull OffsetDateTime updatedAt
) {
}
