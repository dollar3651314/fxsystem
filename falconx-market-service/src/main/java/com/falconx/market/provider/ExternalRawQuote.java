package com.falconx.market.provider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 外部行情源原始报价。
 *
 * <p>该对象是 provider 层进入 market-service 标准化链路的最小契约。
 */
public record ExternalRawQuote(
        String ticker,
        BigDecimal bid,
        BigDecimal ask,
        OffsetDateTime ts,
        String source
) {
}
