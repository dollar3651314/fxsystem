package com.falconx.trading.command;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * STAGE-14D3a Task 4：更新平台冷静期秒数命令。
 *
 * <p>范围对齐 master §7.6：冷静期 60-604800 秒（即 1 分钟 - 7 天）。
 * 校验失败经 {@code TradingGlobalExceptionHandler} → {@code INVALID_REQUEST_PAYLOAD}。
 */
public record UpdateCoolingPeriodCommand(
        @NotNull
        @Min(60) @Max(604800)
        Integer coolingPeriodSeconds) {
}
