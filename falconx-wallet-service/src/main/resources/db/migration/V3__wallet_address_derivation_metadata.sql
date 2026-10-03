ALTER TABLE t_wallet_address
    ADD COLUMN token VARCHAR(16) NOT NULL DEFAULT 'USDT' COMMENT '入金代币符号' AFTER chain,
    ADD COLUMN network VARCHAR(16) NULL COMMENT '入金网络展示名，如 TRC20/ERC20' AFTER token,
    ADD COLUMN derivation_path VARCHAR(128) NULL COMMENT 'HD 派生路径' AFTER address_index;

UPDATE t_wallet_address
SET network = CASE chain
        WHEN 'TRON' THEN 'TRC20'
        WHEN 'ETH' THEN 'ERC20'
        ELSE chain
    END,
    derivation_path = CONCAT('legacy:', chain, ':', address_index)
WHERE network IS NULL
   OR derivation_path IS NULL;

ALTER TABLE t_wallet_address
    MODIFY COLUMN network VARCHAR(16) NOT NULL COMMENT '入金网络展示名，如 TRC20/ERC20',
    MODIFY COLUMN derivation_path VARCHAR(128) NOT NULL COMMENT 'HD 派生路径';
