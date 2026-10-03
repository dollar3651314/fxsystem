package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingAccountRecord;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 交易账户 MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `t_account` 的 SQL 声明，Repository 负责领域语义转换。
 */
@Mapper
public interface TradingAccountMapper {

    /**
     * 按用户和币种查询账户记录。
     *
     * @param userId 用户 ID
     * @param currency 币种
     * @return 账户记录
     */
    TradingAccountRecord selectByUserIdAndCurrency(@Param("userId") Long userId, @Param("currency") String currency);

    /**
     * 按用户和币种查询账户记录，并对该行加悲观锁。
     *
     * @param userId 用户 ID
     * @param currency 币种
     * @return 被锁定的账户记录
     */
    TradingAccountRecord selectByUserIdAndCurrencyForUpdate(@Param("userId") Long userId, @Param("currency") String currency);

    /**
     * 插入新账户。
     *
     * @param record 账户记录
     * @return 影响行数
     */
    int insertTradingAccount(TradingAccountRecord record);

    /**
     * 更新账户快照。
     *
     * @param record 账户记录
     * @return 影响行数
     */
    int updateTradingAccount(TradingAccountRecord record);

    /**
     * 切换账户 margin mode，并写入切换时间与冷静期截止时间。
     *
     * <p>仅更新 margin mode 相关列与 updated_at，不触碰 balance/frozen/margin_used。
     *
     * @param accountId 账户主键 ID
     * @param marginModeCode margin mode 码（1=cross, 2=isolated）
     * @param modeChangedAt 本次切换时间（本地时间）
     * @param modeCoolingUntil 冷静期截止时间（本地时间，可为 null）
     * @return 影响行数
     */
    int updateMarginMode(@Param("accountId") Long accountId,
                         @Param("marginModeCode") int marginModeCode,
                         @Param("modeChangedAt") LocalDateTime modeChangedAt,
                         @Param("modeCoolingUntil") LocalDateTime modeCoolingUntil);
}
