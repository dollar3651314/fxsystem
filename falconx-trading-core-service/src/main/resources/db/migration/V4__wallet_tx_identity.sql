ALTER TABLE t_deposit
    DROP INDEX uk_chain_tx,
    ADD UNIQUE INDEX uk_wallet_tx_id (wallet_tx_id),
    ADD INDEX idx_chain_tx_hash (chain, tx_hash);
