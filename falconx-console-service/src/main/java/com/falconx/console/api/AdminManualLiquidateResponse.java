package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record AdminManualLiquidateResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long positionId,
        @JsonSerialize(using = ToStringSerializer.class) Long liquidationLogId,
        OffsetDateTime closedAt,
        BigDecimal realizedPnl
) {
}
