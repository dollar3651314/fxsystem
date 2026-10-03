package com.falconx.market.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * FX 实时汇率快照事件。
 *
 * <p>对应 Kafka topic {@code falconx.market.fx.rate.update}。
 * <p>消费者：trading-core-service、console-service。
 *
 * @param baseCurrency  基础货币，如 "EUR"
 * @param quoteCurrency 计价货币，如 "USD"
 * @param rate          汇率，1 base = rate × quote
 * @param eventTimeMillis 行情时间戳（来自 LP），毫秒
 * @param sourceLpCode  数据来源 LP，如 "GODSA"
 * @param sourceSymbol  来源平台 symbol，如 "EURUSD"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FxRateSnapshotPayload(
        @NotBlank String baseCurrency,
        @NotBlank String quoteCurrency,
        @NotNull  BigDecimal rate,
        long eventTimeMillis,
        @NotBlank String sourceLpCode,
        @NotBlank String sourceSymbol
) {}
