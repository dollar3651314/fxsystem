package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易账户 MyBatis 记录对象。
 *
 * <p>该记录对象与 `t_account` 字段一一对应，
 * 仅在 Repository 与 Mapper 之间传递持久化数据。
 *
 * @param id 主键 ID
 * @param userId 用户 ID
 * @param currency 币种
 * @param balance 现金余额
 * @param frozen 预留冻结金额
 * @param marginUsed 已占用保证金
 * @param marginModeCode 账户级默认保证金模式（1=cross, 2=isolated）
 * @param modeChangedAt margin mode 上次切换时间
 * @param modeCoolingUntil margin mode 冷静期截止时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record TradingAccountRecord(
        Long id,
        Long userId,
        String currency,
        BigDecimal balance,
        BigDecimal frozen,
        BigDecimal marginUsed,
        Integer marginModeCode,
        LocalDateTime modeChangedAt,
        LocalDateTime modeCoolingUntil,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
