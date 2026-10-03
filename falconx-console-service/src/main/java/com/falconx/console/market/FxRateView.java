package com.falconx.console.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * STAGE-14E2 Task3：管理端 FX 实时汇率监控视图（透传 market-service 快照）。
 *
 * <p>字段对齐 market 上游 {@code FxRateSnapshotPayload}（14A 已上线，不改）：
 * {@code baseCurrency / quoteCurrency / rate / eventTimeMillis / sourceLpCode / sourceSymbol}。
 * 该 payload 不含独立 {@code stale} 布尔字段——stale 由前端依据 {@code eventTimeMillis}
 * 与当前时间差判定，console 仅透传原始快照。
 *
 * @param baseCurrency    基础货币，如 "EUR"
 * @param quoteCurrency   计价货币，如 "USD"
 * @param rate            汇率，1 base = rate × quote
 * @param eventTimeMillis 行情时间戳（来自 LP），毫秒
 * @param sourceLpCode    数据来源 LP，如 "GODSA"
 * @param sourceSymbol    来源平台 symbol，如 "EURUSD"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FxRateView(
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        long eventTimeMillis,
        String sourceLpCode,
        String sourceSymbol
) {}
