-- STAGE-8-NOTIFICATION：站内信。
--
-- 范围（Phase 1 MVP）：
--   触发场景：价格告警触发 / 持仓 LIQUIDATED / TP 触发 / SL 触发
--   读 path：用户 REST 列表 / 未读数 / 标记已读；WebSocket notification.created 实时推送
--   入金/出金/KYC 等跨服务事件留到 Phase 2 接入
--
-- 字段语义：
--   type             业务码 (PRICE_ALERT_TRIGGERED / POSITION_LIQUIDATED / POSITION_TP_HIT / POSITION_SL_HIT 等)
--   level            INFO / WARN / CRITICAL；前端 UI 决定徽标颜色 + toast 是否居中
--   title / body     冗余存最终展现文案（V2 一期不做模板插值，直接由 producer 拼接）
--   related_key      事件源类型 (PRICE_ALERT / POSITION) 便于客户端 deep-link
--   related_id       源实体雪花 ID，前端解析为 string 跳转
--   payload_json     额外结构化数据（symbol / triggeredPrice / direction 等），前端按需取
--   status           0=UNREAD / 1=READ

CREATE TABLE t_notification (
    id            BIGINT       NOT NULL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    type          VARCHAR(64)  NOT NULL,
    level         TINYINT      NOT NULL DEFAULT 1 COMMENT '1=INFO,2=WARN,3=CRITICAL',
    title         VARCHAR(255) NOT NULL,
    body          VARCHAR(1024) NOT NULL,
    related_key   VARCHAR(64),
    related_id    BIGINT,
    payload_json  TEXT,
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0=UNREAD,1=READ',
    read_at       DATETIME(3),
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    KEY idx_user_created (user_id, created_at DESC),
    KEY idx_user_unread (user_id, status, created_at DESC),
    KEY idx_related (related_key, related_id)
);
