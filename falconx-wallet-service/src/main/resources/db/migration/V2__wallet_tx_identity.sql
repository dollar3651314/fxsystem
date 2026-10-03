ALTER TABLE t_wallet_deposit_tx
    ADD COLUMN token_contract_address VARCHAR(128) NULL COMMENT '代币合约地址，原生币为空' AFTER token,
    ADD COLUMN log_index INT NOT NULL DEFAULT 0 COMMENT '日志索引，原生币固定为 0' AFTER tx_hash;

ALTER TABLE t_wallet_deposit_tx
    DROP INDEX uk_chain_tx,
    ADD UNIQUE INDEX uk_chain_tx_log_index (chain, tx_hash, log_index);
