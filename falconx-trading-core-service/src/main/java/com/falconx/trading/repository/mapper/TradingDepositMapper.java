package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingDepositRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 业务入金 MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `t_deposit` 的 SQL 声明。
 */
@Mapper
public interface TradingDepositMapper {

    /**
     * 按 wallet owner 主键查询业务入金。
     *
     * @param walletTxId wallet owner 产出的稳定原始交易主键
     * @return 入金记录
     */
    TradingDepositRecord selectByWalletTxId(@Param("walletTxId") Long walletTxId);

    /**
     * 插入业务入金记录。
     *
     * @param record 入金记录
     * @return 影响行数
     */
    int insertTradingDeposit(TradingDepositRecord record);

    /**
     * 更新业务入金记录。
     *
     * @param record 入金记录
     * @return 影响行数
     */
    int updateTradingDeposit(TradingDepositRecord record);

    /**
     * STAGE-11-OBS-RECON §11.4：admin 对账查询，按 chain / token / status / 时间窗过滤。
     */
    List<TradingDepositRecord> selectForRecon(@Param("chain") String chain,
                                              @Param("token") String token,
                                              @Param("statusCode") Integer statusCode,
                                              @Param("fromCreatedAt") LocalDateTime fromCreatedAt,
                                              @Param("toCreatedAt") LocalDateTime toCreatedAt,
                                              @Param("offset") int offset,
                                              @Param("limit") int limit);
}
