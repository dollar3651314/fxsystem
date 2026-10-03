package com.falconx.console.repository.mapper;

import com.falconx.console.repository.mapper.record.AdminDepositCreditRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 跨 schema 查 falconx_trading.t_deposit 入账状态，按 wallet_tx_id 批量。
 *
 * <p>仅用于 console-service admin 入金列表把 wallet 端事件状态拼上 trading 端入账状态。
 */
@Mapper
public interface AdminDepositCreditEnrichmentMapper {

    List<AdminDepositCreditRecord> selectByWalletTxIds(@Param("walletTxIds") List<Long> walletTxIds);
}
