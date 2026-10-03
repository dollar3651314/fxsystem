package com.falconx.market.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * `falconx.market.kline.update` 事件 payload 契约。
 *
 * <p>该对象用于定义已聚合完成的 K 线对外事件结构。
 * 当前阶段只冻结字段模型，不引入具体事件发送实现。
 *
 * @param symbol 平台内部标准品种
 * @param interval K 线周期，例如 1m / 5m
 * @param open 开盘价
 * @param high 最高价
 * @param low 最低价
 * @param close 收盘价
 * @param volume 成交量或平台定义量
 * @param openTime 开盘时间
 * @param closeTime 收盘时间
 * @param isFinal 当前 K 线是否已收盘
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketKlineUpdateEventPayload(
        @NotBlank String symbol,
        @NotBlank String interval,
        @NotNull BigDecimal open,
        @NotNull BigDecimal high,
        @NotNull BigDecimal low,
        @NotNull BigDecimal close,
        @NotNull BigDecimal volume,
        @NotNull OffsetDateTime openTime,
        @NotNull OffsetDateTime closeTime,
        boolean isFinal
) {
}
