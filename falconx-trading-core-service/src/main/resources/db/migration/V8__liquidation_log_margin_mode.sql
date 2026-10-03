ALTER TABLE t_liquidation_log
    ADD COLUMN margin_mode TINYINT NOT NULL DEFAULT 2 COMMENT '1=cross,2=isolated' AFTER side;
