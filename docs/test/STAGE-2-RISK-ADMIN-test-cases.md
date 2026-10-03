# STAGE-2-RISK-ADMIN 测试用例集

> R6 一轮（2026-05-12）：阶段 2.5 风控管理端测试用例骨架。任务卡：[`STAGE-2-RISK-ADMIN`](../process/task-cards/STAGE-2-RISK-ADMIN.md)。

| 维度 | 数量 |
| --- | --- |
| trading-core internal RPC | 14 TC |
| console-service 转发 + 鉴权 | 10 TC |
| console-frontend 3 页面 + 6 Modal | 8 TC |
| 端到端 | 4 TC |
| 合计 | 36 TC |

---

## §1. trading-core internal RPC（TC-RISK-CORE-*）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-RISK-CORE-01 | GET /risk-actions 无筛选 | 200 + 分页 |
| TC-RISK-CORE-02 | GET /risk-actions 按 triggerSource=MANUAL_ADMIN 筛选 | 仅返回管理员激活 |
| TC-RISK-CORE-03 | POST /risk-actions 新激活 BTCUSDT SUSPEND_SYMBOL MANUAL_ADMIN | 200 + DB 写入 + isActive=1 |
| TC-RISK-CORE-04 | POST /risk-actions 同 symbol+actionType+source 重复 | 90800 |
| TC-RISK-CORE-05 | POST /risk-actions/{id}/deactivate MANUAL_ADMIN happy | 200 + isActive=0 |
| TC-RISK-CORE-06 | POST deactivate id 不存在 | 90801 |
| TC-RISK-CORE-07 | POST deactivate 非 MANUAL_ADMIN 记录 | 90802 |
| TC-RISK-CORE-08 | GET /risk-configs 分页 | 200 |
| TC-RISK-CORE-09 | GET /risk-configs/{symbol} 不存在 | 90803 |
| TC-RISK-CORE-10 | POST /risk-configs 新建 happy | 200 + DB 行 |
| TC-RISK-CORE-11 | POST /risk-configs symbol 重复 | 90804 |
| TC-RISK-CORE-12 | POST /risk-configs maxLeverage=600 | 90805 |
| TC-RISK-CORE-13 | PUT /risk-configs/{symbol} happy | 200 + DB update |
| TC-RISK-CORE-14 | DELETE /risk-configs/{symbol} happy | 200 + DB row deleted |

---

## §2. console-service（TC-RISK-CONSOLE-*）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-RISK-CONSOLE-01 | GET /admin/risk-actions 无 token | 401 |
| TC-RISK-CONSOLE-02 | GET /admin/risk-actions 缺 risk-action:view | 403 |
| TC-RISK-CONSOLE-03 | POST /admin/risk-actions reason 空 | 90810 |
| TC-RISK-CONSOLE-04 | POST happy 写审计日志 | 200 + t_admin_operation_log 有 HIGH_RISK 行 |
| TC-RISK-CONSOLE-05 | POST deactivate happy | 200 + 审计 |
| TC-RISK-CONSOLE-06 | GET /admin/risk-configs happy | 200 |
| TC-RISK-CONSOLE-07 | POST /admin/risk-configs reason 空 | 90810 |
| TC-RISK-CONSOLE-08 | PUT /admin/risk-configs/{symbol} 编辑 happy | 200 + 审计 |
| TC-RISK-CONSOLE-09 | DELETE /admin/risk-configs/{symbol} happy | 200 + 审计 |
| TC-RISK-CONSOLE-10 | PUT /admin/risk-market-configs/{marketCode} happy | 200 + 审计 |

---

## §3. console-frontend（TC-RISK-FE-*）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-RISK-FE-01 | R1 风控动作列表渲染 + triggerSource Tag 颜色 | 4 种颜色正确 |
| TC-RISK-FE-02 | R1 停用按钮仅 MANUAL_ADMIN+active 显示 | 其他行不显示 |
| TC-RISK-FE-03 | M1 GLOBAL_PAUSE 时 symbol 输入禁用 | 切换 actionType 联动 |
| TC-RISK-FE-04 | M1/M2/M3/M4/M5/M6 reason 必填校验 | 不填禁用确认按钮 |
| TC-RISK-FE-05 | R2 risk_config 表渲染 + 编辑/删除按钮 | 操作列正常 |
| TC-RISK-FE-06 | M4 编辑表单 symbol/marketCode 只读 | disabled |
| TC-RISK-FE-07 | R3 risk_market_config Switch 切换触发 M6 | refetch 列表 |
| TC-RISK-FE-08 | 90800 错误本地化 toast | 信息正确 |

---

## §4. 端到端（TC-RISK-E2E-*）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-RISK-E2E-01 | 管理员激活 BTCUSDT REJECT_OPEN MANUAL_ADMIN → 用户开 BTCUSDT 仓被拒 | rejection BBOOK_RISK_OPEN_REJECTED |
| TC-RISK-E2E-02 | 同上停用后用户开仓成功 | 开仓成功 |
| TC-RISK-E2E-03 | 管理员改 risk_config.maxLeverage 100 → 50，用户用 80x 开仓被拒 | 校验生效 |
| TC-RISK-E2E-04 | 管理员 GLOBAL_PAUSE 后所有 symbol 开仓被拒 | BBOOK_RISK_GLOBAL_PAUSE |

---

## §5. R7 必跑用例（最小集）

R7 验证只跑 8 个：TC-RISK-CORE-03 / 04 / 05 / 10 / 11 / 13 / 14 + TC-RISK-FE-03。其余 28 TC 进测试债务。
