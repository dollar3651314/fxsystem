# STAGE-2-DEPOSIT 测试用例集

> R6 一轮（2026-05-12）：阶段 2.2 入金记录管理端测试用例骨架。任务卡：[`STAGE-2-DEPOSIT`](../process/task-cards/STAGE-2-DEPOSIT.md)。

| 维度 | 数量 |
| --- | --- |
| wallet-service internal RPC | 8 TC |
| console-service 转发 + 鉴权 | 6 TC |
| console-frontend | 5 TC |
| 端到端 | 3 TC |
| 合计 | 22 TC |

---

## §1. wallet-service（TC-DEPOSIT-WALLET-*）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-DEPOSIT-WALLET-01 | GET /deposits 无筛选 | 200 + 分页 |
| TC-DEPOSIT-WALLET-02 | GET /deposits 按 userId 筛选 | 仅返回该 user |
| TC-DEPOSIT-WALLET-03 | GET /deposits status=DETECTED,CONFIRMING 多值 | SQL IN 生效 |
| TC-DEPOSIT-WALLET-04 | GET /deposits chain=TRON+token=USDT 组合 | 多条件 AND |
| TC-DEPOSIT-WALLET-05 | GET /deposits onlyOrphan=true | 仅 user_id IS NULL |
| TC-DEPOSIT-WALLET-06 | GET /deposits detectedAt 范围 | 边界正确 |
| TC-DEPOSIT-WALLET-07 | GET /deposits/{id} happy | 200 + Item |
| TC-DEPOSIT-WALLET-08 | GET /deposits/{id} 不存在 | 90850 |

---

## §2. console-service（TC-DEPOSIT-CONSOLE-*）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-DEPOSIT-CONSOLE-01 | GET /admin/deposits 无 token | 401 |
| TC-DEPOSIT-CONSOLE-02 | GET /admin/deposits 缺 deposit:view 权限 | 403 |
| TC-DEPOSIT-CONSOLE-03 | GET /admin/deposits happy | 200 |
| TC-DEPOSIT-CONSOLE-04 | GET /admin/deposits/{id} 不存在 → 透传 90850 | 404 + code=90850 |
| TC-DEPOSIT-CONSOLE-05 | wallet-service 缺 X-Internal-Token | 90702（gateway 注入失败模拟） |
| TC-DEPOSIT-CONSOLE-06 | wallet-service token 不匹配 | 90701 |

---

## §3. console-frontend（TC-DEPOSIT-FE-*）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-DEPOSIT-FE-01 | D1 表格渲染 + status 多选筛选触发 | URLSearchParams 拼接 status=DETECTED,CONFIRMING |
| TC-DEPOSIT-FE-02 | D1 onlyOrphan Switch 切换 | API 含 onlyOrphan=true |
| TC-DEPOSIT-FE-03 | D1 userId 链接跳转 customers 详情 | navigate(/admin/customers/{userId}) |
| TC-DEPOSIT-FE-04 | D2 详情 Drawer 17 字段显示 | 全字段渲染 |
| TC-DEPOSIT-FE-05 | D2 打开 txHash explorer 链接 | window.open 正确域名 |

---

## §4. 端到端（TC-DEPOSIT-E2E-*）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-DEPOSIT-E2E-01 | 管理员登录 → 列表 → 点击详情 → 关闭 | 全链路 200 + 0 console error |
| TC-DEPOSIT-E2E-02 | 模拟脱钩记录（user_id NULL）filtering | onlyOrphan=true 时仅返回脱钩 |
| TC-DEPOSIT-E2E-03 | 未授权角色访问 | 403 + 前端拦截 |

---

## §5. R7 必跑用例（最小集）

R7 只跑 5 个：TC-DEPOSIT-WALLET-01 / 03 / 05 / 08 + TC-DEPOSIT-FE-01。其余 17 TC 进测试债务。
