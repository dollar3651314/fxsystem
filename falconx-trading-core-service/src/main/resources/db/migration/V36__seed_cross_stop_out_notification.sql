-- STAGE-14D2 Task 5：seed CROSS_STOP_OUT_TRIGGERED 通知模板（CROSS 账户级强平汇总站内信）。
--
-- 注意：Flyway migration 不得写 `USE <schema>;`，schema 由连接绑定决定（与 V1-V35 一致）。
--
-- 范围：CrossLiquidationOrchestrator 账户级强平完成后（liquidatedCount>0）调
--   notificationService.send("CROSS_STOP_OUT_TRIGGERED", userId, "CROSS_STOP_OUT_TRIGGERED",
--       {marginLevel, count, symbols}, "ACCOUNT", accountId, null)，该模板提供 title/body 文案。
--   CROSS 强平的逐仓 close 不再发逐仓 POSITION_LIQUIDATED（去重），改由本账户级模板一次汇总。
--   列结构对齐 V22 / V31 / V34 t_notification_template
--   （实际列：code/title_template/body_template/level/channels/description/enabled，无 type/title/body 列）。
--   占位符 ${var} + Map<String,String> params + String.replace（与 V22 / V31 / V34 插值规约一致）。
--   level=3（CRITICAL，与 STOP_OUT_TRIGGERED 同档）；channels=IN_APP。

INSERT INTO t_notification_template (code, title_template, body_template, level, channels, description, enabled) VALUES
  ('CROSS_STOP_OUT_TRIGGERED', '账户强制平仓',
                               '您的账户保证金率 ${marginLevel}% 触发强平线，已强平 ${count} 个仓位：${symbols}',
                               3, 'IN_APP', 'CROSS 账户级 MarginLevel 触发 StopOut，浮亏最大优先逐仓强平汇总', 1);
