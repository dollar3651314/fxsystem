-- STAGE-13 系统配置中心：动态化运维配置 + 操作审计
-- 范围：限流速率 / 可信代理 IP / Console IP 白名单 / Token TTL / 登录锁定 /
-- bcrypt strength / Security Headers 开关。
-- 所有 service 启动时从 t_system_config 读取（key prefix 区分 scope），
-- 运行时通过 Redis pub/sub 推送变更事件，service 收到后更新本地 cache。

CREATE TABLE IF NOT EXISTS `t_system_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `config_key` VARCHAR(96) NOT NULL COMMENT '配置 key（如 gateway.ratelimit.auth-per-minute）',
  `config_value` TEXT NOT NULL COMMENT '配置值（按 value_type 解释；列表/对象用 JSON）',
  `value_type` VARCHAR(16) NOT NULL COMMENT 'STRING / INT / LONG / DECIMAL / BOOL / DURATION / JSON',
  `category` VARCHAR(32) NOT NULL COMMENT '分类：RATE_LIMIT / SECURITY / TOKEN / AUTH / HEADER',
  `scope` VARCHAR(32) NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL / SERVICE:<name>',
  `description` VARCHAR(255) NULL COMMENT '人类可读描述（admin UI 显示）',
  `default_value` TEXT NULL COMMENT '硬编码默认值（重置用）',
  `validation_regex` VARCHAR(255) NULL COMMENT '值合法性正则（如 ^\\d+$）',
  `is_sensitive` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否敏感（UI 默认隐藏）',
  `updated_by` BIGINT NULL COMMENT '最后更新的 admin user id',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`),
  KEY `idx_category` (`category`),
  KEY `idx_scope` (`scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='STAGE-13 系统配置中心 / 动态运维参数';

CREATE TABLE IF NOT EXISTS `t_system_config_audit` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `config_key` VARCHAR(96) NOT NULL,
  `old_value` TEXT NULL COMMENT '变更前值（创建时为 NULL）',
  `new_value` TEXT NULL COMMENT '变更后值（删除时为 NULL）',
  `action` VARCHAR(16) NOT NULL COMMENT 'CREATE / UPDATE / DELETE / RESET',
  `operator_id` BIGINT NOT NULL COMMENT '操作的 admin user id',
  `operator_email` VARCHAR(128) NULL COMMENT '操作时刻的 admin 邮箱快照',
  `client_ip` VARCHAR(64) NULL,
  `reason` VARCHAR(255) NULL COMMENT '变更原因（可选填）',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_config_key_time` (`config_key`, `created_at`),
  KEY `idx_operator_time` (`operator_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='STAGE-13 系统配置变更审计日志（永久保留，不删）';

-- ============================================================
-- 默认配置 bootstrap（INSERT IGNORE 防止重复部署冲突）
-- ============================================================

-- ┌──────────────────────────── RATE_LIMIT 限流 ────────────────────────────┐
INSERT IGNORE INTO `t_system_config`
  (config_key, config_value, value_type, category, description, default_value, validation_regex)
VALUES
  ('gateway.ratelimit.auth-per-minute', '20', 'INT', 'RATE_LIMIT',
   '/auth/* 接口每 IP 每分钟最大请求数', '20', '^[1-9][0-9]{0,3}$'),
  ('gateway.ratelimit.trading-per-second', '10', 'INT', 'RATE_LIMIT',
   '交易接口（下单/平仓）每 IP 每秒最大请求数', '10', '^[1-9][0-9]{0,2}$'),
  ('gateway.ratelimit.global-per-minute', '200', 'INT', 'RATE_LIMIT',
   '全局每 IP 每分钟最大请求数（兜底）', '200', '^[1-9][0-9]{0,4}$'),
  ('gateway.ratelimit.market-ws-connection-limit', '5', 'INT', 'RATE_LIMIT',
   '行情 WebSocket 每用户最大并发连接数', '5', '^[1-9][0-9]?$');

-- ┌──────────────────────────── SECURITY 安全 ────────────────────────────┐
INSERT IGNORE INTO `t_system_config`
  (config_key, config_value, value_type, category, description, default_value)
VALUES
  ('gateway.security.trusted-proxy-ips',
   '["127.0.0.1","::1","0:0:0:0:0:0:0:1","172.17.0.1","172.18.0.1","172.19.0.1","172.20.0.1"]',
   'JSON', 'SECURITY',
   '可信代理 IP 列表（JSON 数组）；只有 remoteAddress 命中此列表时才信任客户端 X-Client-Ip header',
   '["127.0.0.1","::1"]'),
  ('console.security.ip-whitelist-enabled', 'true', 'BOOL', 'SECURITY',
   '管理后台 IP 白名单总开关', 'true'),
  ('console.security.ip-whitelist',
   '["127.0.0.1","::1","172.16.0.0/12","10.0.0.0/8"]',
   'JSON', 'SECURITY',
   '管理后台允许访问的 IP / CIDR 列表（JSON 数组）',
   '["127.0.0.1","::1"]'),
  ('gateway.security.headers-enabled', 'true', 'BOOL', 'SECURITY',
   'Security Headers 全局开关（X-Frame-Options / X-Content-Type-Options 等）', 'true');

-- ┌──────────────────────────── TOKEN 令牌 ────────────────────────────┐
INSERT IGNORE INTO `t_system_config`
  (config_key, config_value, value_type, category, description, default_value, validation_regex)
VALUES
  ('identity.token.access-token-ttl-minutes', '60', 'INT', 'TOKEN',
   '用户 access token TTL（分钟）', '60', '^[1-9][0-9]{0,3}$'),
  ('identity.token.refresh-token-ttl-hours', '24', 'INT', 'TOKEN',
   '用户 refresh token TTL（小时）— STAGE-12 之前 72h 偏长', '24', '^[1-9][0-9]{0,2}$'),
  ('console.token.access-token-ttl-minutes', '30', 'INT', 'TOKEN',
   'admin access token TTL（分钟）', '30', '^[1-9][0-9]{0,2}$'),
  ('console.token.refresh-token-ttl-hours', '8', 'INT', 'TOKEN',
   'admin refresh token TTL（小时）', '8', '^[1-9][0-9]{0,2}$');

-- ┌──────────────────────────── AUTH 认证策略 ────────────────────────────┐
INSERT IGNORE INTO `t_system_config`
  (config_key, config_value, value_type, category, description, default_value, validation_regex)
VALUES
  ('identity.password.bcrypt-strength', '10', 'INT', 'AUTH',
   '用户密码 BCrypt 强度（4-31，每+1 慢 2x）', '10', '^([4-9]|1[0-5])$'),
  ('console.password.bcrypt-strength', '12', 'INT', 'AUTH',
   '管理员密码 BCrypt 强度（建议 ≥ 12）', '12', '^(1[0-5])$'),
  ('console.security.login-failure-limit', '5', 'INT', 'AUTH',
   '连续登录失败多少次锁定账号', '5', '^[1-9][0-9]?$'),
  ('console.security.login-lock-duration-minutes', '15', 'INT', 'AUTH',
   '账号锁定时长（分钟）', '15', '^[1-9][0-9]{0,3}$');
