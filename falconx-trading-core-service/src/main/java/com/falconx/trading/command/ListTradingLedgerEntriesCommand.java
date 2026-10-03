package com.falconx.trading.command;

import com.falconx.trading.entity.TradingLedgerBizType;
import java.time.OffsetDateTime;

/**
 * 查询账本流水命令。
 *
 * @param userId 当前用户 ID
 * @param page 页码，从 `1` 开始
 * @param pageSize 每页条数
 * @param bizType 业务类型筛选（可空 → 不过滤）
 * @param from 起始时间（含，可空 → 不过滤）
 * @param to 结束时间（含，可空 → 不过滤）
 */
public record ListTradingLedgerEntriesCommand(
        Long userId,
        int page,
        int pageSize,
        TradingLedgerBizType bizType,
        OffsetDateTime from,
        OffsetDateTime to
) {
}
