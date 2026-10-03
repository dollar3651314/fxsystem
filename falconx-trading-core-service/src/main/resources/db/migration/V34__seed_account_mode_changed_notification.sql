-- STAGE-14D1 Task 4：seed ACCOUNT_MODE_CHANGED 通知模板（margin mode 切换成功站内信）。
--
-- 注意：Flyway migration 不得写 `USE <schema>;`，schema 由连接绑定决定（与 V1-V33 一致）。
--
-- 范围：MarginModeSwitchApplicationService 切换成功后调
--   notificationService.send("ACCOUNT_MODE_CHANGED", userId, ..., {oldMode,newMode}, "ACCOUNT", accountId, null)，
--   该模板提供 title/body 文案。列结构对齐 V22 t_notification_template
--   （实际列：code/title_template/body_template/level/channels/description/enabled，无 type/title/body 列）。
--   占位符 ${var} + Map<String,String> params + String.replace（与 V22 / V31 插值规约一致）。

INSERT INTO t_notification_template (code, title_template, body_template, level, channels, description, enabled) VALUES
  ('ACCOUNT_MODE_CHANGED', '保证金模式已切换',
                           '您的保证金模式已从 ${oldMode} 切换为 ${newMode}',
                           1, 'IN_APP', 'margin mode 切换成功（ISOLATED/CROSS）', 1);
