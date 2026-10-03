-- 提前建好各服务 schema（Flyway 不创建库，只建表）
-- jdbc url 已有 createDatabaseIfNotExist=true 兜底，此脚本作为冗余保护
CREATE DATABASE IF NOT EXISTS falconx_identity DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS falconx_market   DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS falconx_trading  DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS falconx_wallet   DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS falconx_console  DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
