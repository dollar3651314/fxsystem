package com.falconx.market.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * `falconx.market.price.tick` 事件 payload 契约。
 *
 * <p>该对象定义 market-service 发布给 trading-core-service 的标准价格事件结构。
 * 当前阶段先冻结字段，不绑定任何传输框架或序列化实现。
 *
 * @param symbol 平台内部标准品种
 * @param bid 买一价
 * @param ask 卖一价
 * @param mid 中间价
 * @param mark 标记价
 * @param ts 报价时间
 * @param source 报价来源
 * @param stale 是否已超时
 * @param quoteStatus 行情质量状态，`FRESH` 是唯一可成交状态
 * @param qualityReason 行情质量原因
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketPriceTickEventPayload(
        @NotBlank String symbol,
        @NotNull BigDecimal bid,
        @NotNull BigDecimal ask,
        @NotNull BigDecimal mid,
        @NotNull BigDecimal mark,
        @JsonFormat(shape = JsonFormat.Shape.NUMBER_FLOAT)
        @NotNull OffsetDateTime ts,
        @NotBlank String source,
        boolean stale,
        String quoteStatus,
        String qualityReason
) {
    public MarketPriceTickEventPayload(String symbol,
                                       BigDecimal bid,
                                       BigDecimal ask,
                                       BigDecimal mid,
                                       BigDecimal mark,
                                       OffsetDateTime ts,
                                       String source,
                                       boolean stale) {
        this(
                symbol,
                bid,
                ask,
                mid,
                mark,
                ts,
                source,
                stale,
                stale ? "STALE" : "FRESH",
                stale ? "QUOTE_TIME_DRIFT_EXCEEDED" : null
        );
    }
}
