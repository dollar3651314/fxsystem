package com.falconx.trading.command;

/**
 * 手动平仓命令。
 *
 * <p>本轮最小接口不额外引入请求体字段，用户只能基于已有持仓主键发起手动平仓。
 */
public record CloseTradingPositionCommand(
        Long userId,
        Long positionId
) {
}
