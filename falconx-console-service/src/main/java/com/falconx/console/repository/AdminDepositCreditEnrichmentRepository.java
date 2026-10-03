package com.falconx.console.repository;

import com.falconx.console.repository.mapper.record.AdminDepositCreditRecord;
import java.util.List;

/**
 * STAGE-2-DEPOSIT V23：查 trading-core 端的入金入账状态，按 wallet_tx_id。
 */
public interface AdminDepositCreditEnrichmentRepository {

    List<AdminDepositCreditRecord> findByWalletTxIds(List<Long> walletTxIds);
}
