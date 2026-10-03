package com.falconx.trading.command;

import com.falconx.trading.entity.TradingPositionStatus;
import java.util.List;

/**
 * 查询持仓列表命令。
 *
 * @param userId 当前用户 ID
 * @param page 页码，从 `1` 开始
 * @param pageSize 每页条数
 * @param statusFilter 状态过滤，null 或空表示返回全部状态；典型值 `[OPEN]` 用于活跃持仓，
 *                     `[CLOSED, LIQUIDATED]` 用于历史持仓
 */
public record ListTradingPositionsCommand(
        Long userId,
        int page,
        int pageSize,
        List<TradingPositionStatus> statusFilter
) {
}
