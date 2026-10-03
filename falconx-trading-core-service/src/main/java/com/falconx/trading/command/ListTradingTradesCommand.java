package com.falconx.trading.command;

/**
 * 查询成交列表命令。
 *
 * @param userId 当前用户 ID
 * @param page 页码，从 `1` 开始
 * @param pageSize 每页条数
 */
public record ListTradingTradesCommand(
        Long userId,
        int page,
        int pageSize
) {
}
