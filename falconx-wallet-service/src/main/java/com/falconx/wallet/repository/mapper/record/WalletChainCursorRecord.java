package com.falconx.wallet.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * 链监听游标 MyBatis 记录对象。
 *
 * @param id 主键 ID
 * @param chain 链标识
 * @param cursorType 游标类型
 * @param cursorValue 游标值
 * @param updatedAt 更新时间
 */
public record WalletChainCursorRecord(
        Long id,
        String chain,
        String cursorType,
        String cursorValue,
        LocalDateTime updatedAt
) {
}
