package com.falconx.trading.command;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * STAGE-14D3a Task 4：更新平台 MarginLevel 阈值命令（小数口径）。
 *
 * <p>范围对齐 master §7.6：StopOut 0.05-0.95、MarginCall 0.50-2.00。
 * 校验失败经 {@code TradingGlobalExceptionHandler} → {@code INVALID_REQUEST_PAYLOAD}。
 */
public record UpdateRiskThresholdsCommand(
        @NotNull @DecimalMin("0.05") @DecimalMax("0.95")
        BigDecimal stopOutLevel,
        @NotNull @DecimalMin("0.50") @DecimalMax("2.00")
        BigDecimal marginCallLevel) {
}
