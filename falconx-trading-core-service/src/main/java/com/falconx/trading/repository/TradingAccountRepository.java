package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 交易账户仓储接口。
 *
 * <p>该仓储负责承载 `t_account` owner 数据的最小读写能力。
 * 当前正式实现统一通过 `MyBatis Mapper + XML + Repository` 链路访问数据库。
 */
public interface TradingAccountRepository {

    /**
     * 按用户和币种查询账户。
     *
     * @param userId 用户 ID
     * @param currency 账户币种
     * @return 账户可选结果
     */
    Optional<TradingAccount> findByUserIdAndCurrency(Long userId, String currency);

    /**
     * 按用户和币种查询账户并对结果行加悲观锁。
     *
     * <p>该方法用于“先风控、再扣减”的交易写路径，避免并发请求在同一账户上超额占用保证金。
     *
     * @param userId 用户 ID
     * @param currency 账户币种
     * @return 加锁后的账户可选结果
     */
    Optional<TradingAccount> findByUserIdAndCurrencyForUpdate(Long userId, String currency);

    /**
     * 保存账户。
     *
     * @param account 账户对象
     * @return 持久化后的账户对象
     */
    TradingAccount save(TradingAccount account);

    /**
     * 切换账户 margin mode，并写入切换时间与冷静期截止时间。
     *
     * <p>该方法只更新 margin mode 相关列，不触碰 balance/frozen/margin_used，
     * 供用户级 margin mode 切换闸门在事务内调用。
     *
     * @param accountId 账户主键 ID
     * @param marginMode 目标保证金模式
     * @param modeChangedAt 本次切换时间
     * @param modeCoolingUntil 冷静期截止时间，可为 null
     * @return 影响行数
     */
    int switchMarginMode(Long accountId,
                         TradingMarginMode marginMode,
                         OffsetDateTime modeChangedAt,
                         OffsetDateTime modeCoolingUntil);
}
