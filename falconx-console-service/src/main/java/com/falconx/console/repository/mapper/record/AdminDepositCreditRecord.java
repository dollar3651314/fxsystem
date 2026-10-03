package com.falconx.console.repository.mapper.record;

/**
 * STAGE-2-DEPOSIT V23：从 falconx_trading.t_deposit 跨 schema 查
 * 「wallet 入金 -> trading-core 入账」状态的 enrichment 记录。
 *
 * @param walletTxId wallet 端 t_wallet_deposit_tx.id（也是 trading t_deposit.wallet_tx_id）
 * @param statusCode trading 端 t_deposit.status：1=CREDITED / 2=REVERSED / 3=REJECTED
 * @param rejectionReason 拒收原因（仅 REJECTED 行有值，其他 NULL）
 */
public record AdminDepositCreditRecord(
        Long walletTxId,
        Integer statusCode,
        String rejectionReason
) {
}
