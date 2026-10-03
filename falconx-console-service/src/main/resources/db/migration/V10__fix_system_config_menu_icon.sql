-- =============================================================
-- V10: 修正 system-config 菜单 icon 与 V9 backfill 的菜单命名风格一致
--
-- 历史：V8 写入 system-config 菜单时 icon 值 = 'settings'（小写短名）。
-- V9 backfill 全部其他菜单用 Antd icon component 名（如 'SettingOutlined'）。
-- 前端 ICON_MAP 按 PascalCase component 名映射 → 'settings' 会 fallback 到默认图标，
-- 显示不一致。改正以让 system-config 显示齿轮图标。
-- =============================================================
UPDATE t_admin_menu
SET icon = 'SettingOutlined'
WHERE code = 'system-config' AND icon = 'settings';
