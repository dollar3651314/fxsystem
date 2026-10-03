# STAGE-2-TRADING-MONITOR 测试用例集

> R6 一轮（2026-05-12）：阶段 2.4 订单与持仓监控管理端测试用例骨架。
>
> 任务卡：[`STAGE-2-TRADING-MONITOR`](../process/task-cards/STAGE-2-TRADING-MONITOR.md)。

| 维度 | 数量 |
| --- | --- |
| 接口契约（trading-core internal RPC） | 12 TC |
| 接口契约（console-service 转发 + 鉴权） | 10 TC |
| 前端（console-frontend 4 页面） | 8 TC |
| 端到端（强平 happy / 暂停 happy / 异常） | 6 TC |
| 合计 | 36 TC |

---

## §1. trading-core internal RPC（TC-TM-CORE-*）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-TM-CORE-01 | GET /internal/v1/trading/console/orders 无筛选 | 200 + 分页结构正确 |
| TC-TM-CORE-02 | GET orders 按 userId 筛选 | 仅返回该 user 订单 |
| TC-TM-CORE-03 | GET orders 按 symbol + status + 时间范围组合 | 多条件 AND 生效 |
| TC-TM-CORE-04 | GET positions 无筛选 | 200 + 分页 |
| TC-TM-CORE-05 | GET positions 按 status=1（持仓中）筛选 | 不返回已平仓 |
| TC-TM-CORE-06 | GET exposures 全量 | 返回 t_risk_exposure 全行 |
| TC-TM-CORE-07 | GET exposures 按 symbol=BTCUSDT 筛选 | 仅 1 行 |
| TC-TM-CORE-08 | POST positions/{id}/manual-liquidate happy | 200，写 t_liquidation_log close_reason=MANUAL_ADMIN，position.status=CLOSED |
| TC-TM-CORE-09 | POST manual-liquidate position 不存在 | 90650 |
| TC-TM-CORE-10 | POST manual-liquidate position 已平仓 | 90651 |
| TC-TM-CORE-11 | POST risk-switches/auto-liquidate enabled=false happy | 200，DB 写入，Redis 刷新 |
| TC-TM-CORE-12 | POST auto-liquidate 值不变 | 90655 |

---

## §2. console-service 转发 + 鉴权（TC-TM-CONSOLE-*）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-TM-CONSOLE-01 | GET /admin/trading/orders 无 token | 401 |
| TC-TM-CONSOLE-02 | GET orders 用户无 `trading-monitor:order:view` | 403 |
| TC-TM-CONSOLE-03 | GET orders happy | 200 |
| TC-TM-CONSOLE-04 | GET positions happy | 200 |
| TC-TM-CONSOLE-05 | GET exposures happy | 200 |
| TC-TM-CONSOLE-06 | GET risk-switches happy | 200 |
| TC-TM-CONSOLE-07 | POST manual-liquidate reason 为空 | 90656 |
| TC-TM-CONSOLE-08 | POST manual-liquidate happy 写审计日志 | 200 + t_admin_operation_log 含 actor/target/reason |
| TC-TM-CONSOLE-09 | POST risk-switches reason 为空 | 90656 |
| TC-TM-CONSOLE-10 | POST risk-switches happy 写审计日志 | 200 + t_admin_operation_log 含 actor/before/after/reason |

---

## §3. console-frontend（TC-TM-FE-*）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-TM-FE-01 | T1 订单列表渲染 + 筛选 | 表格列正确 / 筛选触发 GET 含参 |
| TC-TM-FE-02 | T2 持仓列表强平按钮仅在 status=1 显示 | 已平仓行无按钮 |
| TC-TM-FE-03 | T3 净敞口看板按 netExposureUsd 倒序 | 数据排序正确 |
| TC-TM-FE-04 | T4 风控开关展示当前状态 | Switch checked 与 enabled 一致 |
| TC-TM-FE-05 | T5 强平 Modal 必填 reason 校验 | 不填禁用确认按钮 |
| TC-TM-FE-06 | T6 暂停 Modal 必填 reason 校验 | 同上 |
| TC-TM-FE-07 | T5 强平成功 toast + 刷新表格 | refetch positions list |
| TC-TM-FE-08 | T6 切换成功 toast + 刷新开关状态 | refetch risk-switches |

---

## §4. 端到端（TC-TM-E2E-*）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-TM-E2E-01 | 管理员登录 → T1 浏览订单 → T2 浏览持仓 | 全 200 + 页面渲染无 console error |
| TC-TM-E2E-02 | 管理员手动强平活跃持仓 → 验证 trading 表 + 审计日志 | position.status=CLOSED, log 一条 |
| TC-TM-E2E-03 | 同一持仓二次强平 | 第二次 90651 |
| TC-TM-E2E-04 | 暂停自动强平 → trading-core worker 不触发强平 → 恢复 | Redis flag 切换；恢复后立即恢复 worker |
| TC-TM-E2E-05 | 未授权角色访问 T4 | 403 + 前端权限拦截 |
| TC-TM-E2E-06 | 重启 trading-core 后开关状态保持 | DB 持久化 → 启动后 Redis 重新 warmup |

---

## §5. R7 必跑用例（最小集）

R7 验证只跑：
- TC-TM-CORE-01 / 04 / 06（3 个查询 happy）
- TC-TM-CORE-08 / 09 / 11（强平 happy + 错误码 + 开关 happy）
- TC-TM-CONSOLE-08 / 10（审计日志写入）
- TC-TM-FE-01 / 02（浏览器 QA 截图）

合计 9 个核心 TC，其余进 R6 测试债务后续 CI 化。
