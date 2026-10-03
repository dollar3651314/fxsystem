package com.falconx.trading.command;

import jakarta.validation.constraints.NotNull;

/**
 * STAGE-14D3a Task 7：按品种类目更新 FX_PAUSED 行为开关的命令（console 透传）。
 *
 * <p>三个开关均不可为空（Bean Validation {@code @NotNull}），缺字段经
 * {@code TradingGlobalExceptionHandler} → {@code INVALID_REQUEST_PAYLOAD(99004)}。
 * {@code allowClose} 在 master §6.5 语义上恒 true（手动平仓不限制），但仍由 console 显式传入，
 * 由 {@code FxPauseBehaviorRepository.updateByCategory} 绝对覆盖写入。
 *
 * @param allowOpen 停盘期间是否允许开仓
 * @param allowClose 停盘期间是否允许平仓
 * @param allowLiquidation 停盘期间是否允许被动强平
 */
public record UpdateFxPauseBehaviorCommand(
        @NotNull Boolean allowOpen,
        @NotNull Boolean allowClose,
        @NotNull Boolean allowLiquidation) {
}
