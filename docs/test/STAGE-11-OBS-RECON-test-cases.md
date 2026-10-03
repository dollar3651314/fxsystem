# STAGE-11-OBS-RECON 测试用例（R6 Phase 0，2026-05-15）

> 任务编号：`STAGE-11-OBS-RECON`（可观测性 + 对账）
> 测试编号段：`TC-OBS-` 100-129 / `TC-RECON-` 100-119 / `TC-RECON-FE-` 050-059
> 实施模式：R6 一轮先落骨架，详细 @Test 真代码归 R6 二轮（与 STAGE-5/7/8/9 同模式）

---

## §1. 11.1 Actuator 健康检查 TC

| TC | 场景 | 测试类 |
| --- | --- | --- |
| TC-OBS-100 | identity-service `/actuator/health` 返回 UP + components{db,redis,kafka}.status=UP | `IdentityActuatorHealthIntegrationTests` |
| TC-OBS-101 | identity-service `/actuator/health/liveness` 返回 200 即使 DB DOWN（liveness 仅 JVM 存活） | 同上 |
| TC-OBS-102 | identity-service `/actuator/health/readiness` DB DOWN 时返回 503 | 同上 |
| TC-OBS-103 | identity-service `/actuator/info` 含 git.commit.id + build.time | `IdentityActuatorInfoTests` |
| TC-OBS-104 | trading-core-service /actuator/health DB+Redis+Kafka 全 UP | `TradingActuatorHealthIntegrationTests` |
| TC-OBS-105 | wallet-service /actuator/health 含 ethRpc UP（ETH RPC 可达）+ ethRpc DOWN（unreachable 时） | `WalletActuatorHealthIntegrationTests` |
| TC-OBS-106 | market-service /actuator/health 含 lp 状态（CONNECTED / DISCONNECTED） | `MarketActuatorHealthIntegrationTests` |
| TC-OBS-107 | console-service /actuator/health UP | `ConsoleActuatorHealthIntegrationTests` |
| TC-OBS-108 | gateway /actuator/health 含 downstream 5 服务可达性 | `GatewayActuatorHealthIntegrationTests` |
| TC-OBS-109 | /actuator/metrics 返回 jvm.memory.used 等指标，非 401/403 | 通用 mod |

---

## §2. 11.2 falconx-canary CLI TC

| TC | 场景 | 测试类 |
| --- | --- | --- |
| TC-OBS-120 | `canary login` 返回退出码 0 + 报告含 me.id | `LoginCanaryCommandTests` |
| TC-OBS-121 | `canary login` 错误密码退出码 1 + 报告含 401 | 同上 |
| TC-OBS-122 | `canary trade` 端到端：login → 下单 → 平仓 → 验账户 | `TradeCanaryCommandTests` |
| TC-OBS-123 | `canary deposit-listen` 60s 内见 confirmed 退出 0；超时退出 2 | `DepositListenCanaryCommandTests` |
| TC-OBS-124 | `canary recon` total < maxUnmatched=100 退出 0 | `ReconCanaryCommandTests` |
| TC-OBS-125 | `canary recon` total ≥ maxUnmatched 退出 1 + 报告含告警 | 同上 |
| TC-OBS-126 | `canary health-all` 6 服务全 UP 退出 0 | `HealthAllCanaryCommandTests` |
| TC-OBS-127 | `canary health-all` 任一服务 DOWN 退出 1 + 报告列出 DOWN 列表 | 同上 |

---

## §3. 11.4 入金对账接口 TC

### 3.1 console-service IT（TC-RECON-100 ~ 109）

| TC | 场景 | 测试类 |
| --- | --- | --- |
| TC-RECON-100 | `GET /admin/reconciliation/deposits/unmatched` 返回 WALLET_ONLY 项（wallet confirmed but trading 无） | `AdminReconciliationEndpointIntegrationTests` |
| TC-RECON-101 | 返回 AMOUNT_MISMATCH 项（两端 amount 不一致） | 同上 |
| TC-RECON-102 | 返回 STATUS_DIVERGED 项（wallet=REVERSED 但 trading=CREDITED） | 同上 |
| TC-RECON-103 | filter `chain=ETH` 仅返 ETH 项；`discrepancyType=WALLET_ONLY` 仅返该类型 | 同上 |
| TC-RECON-104 | wallet 端不可达 → 返回 90920 ADMIN_RECONCILIATION_WALLET_UNREACHABLE（HTTP 500） | 同上 |
| TC-RECON-105 | trading-core 端不可达 → 返回 90921 ADMIN_RECONCILIATION_TRADING_UNREACHABLE（HTTP 500） | 同上 |
| TC-RECON-106 | `POST /admin/reconciliation/deposits/{walletTxId}/mark-resolved` reason 缺失 → 90923（HTTP 400） | 同上 |
| TC-RECON-107 | mark-resolved 成功 → t_admin_operation_log 出现 target_type='reconciliation' + permission_code='reconciliation:resolve' | 同上 |
| TC-RECON-108 | 重复 mark-resolved 同一 walletTxId → 90924 ADMIN_RECONCILIATION_ALREADY_RESOLVED（HTTP 409） | 同上 |
| TC-RECON-109 | 缺 `reconciliation:view` → 403；缺 `reconciliation:resolve` → 403 | 同上 |

### 3.2 console-frontend Vitest（TC-RECON-FE-050 ~ 055）

| TC | 场景 | 测试类 |
| --- | --- | --- |
| TC-RECON-FE-050 | reconciliationApi.list 透传 5 query 参数 | `reconciliationApi.test.ts` |
| TC-RECON-FE-051 | 默认分页 page=1 size=20 | 同上 |
| TC-RECON-FE-052 | markResolved POST body 含 reason + resolutionType | 同上 |
| TC-RECON-FE-053 | ReconciliationListPage 渲染 + Table 行 + discrepancyType Tag 着色 | `ReconciliationListPage.test.tsx` |
| TC-RECON-FE-054 | 详情 Drawer 打开 + Modal mark-resolved（reason ≥10 + confirm checkbox） | 同上 |
| TC-RECON-FE-055 | 缺 reconciliation:resolve → 标记按钮 disabled + Tooltip | 同上 |

---

## §4. 11.3 日志检索手册（R8 文档任务）

无 @Test 覆盖；R7 验证时通过抽查手册中典型场景的 grep 模板能否在本地日志命中作为间接验证（PASS 条件：5 个典型场景全部命中）。

---

## §5. 三端 E2E

### TC-E2E-RECON-001 入金对账整链

**步骤**：
1. 模拟 wallet 端 t_wallet_deposit_tx confirmed 但 trading-core t_deposit 无对应（人为构造 unmatched）
2. console 端 GET /admin/reconciliation/deposits/unmatched 见该项 discrepancyType=WALLET_ONLY
3. 管理端 P19 列表页见该项 + Tag 着色
4. 点击详情 → Drawer 显示完整字段
5. 点击 [标记 resolved] → Modal 填 reason + resolutionType=MANUAL_CREDIT + confirm checkbox → 确认
6. t_admin_operation_log 出现新条目（target_type='reconciliation' + risk_level='MEDIUM'）
7. 重新拉列表，该项不再出现（已 resolved 过滤）

---

## §6. 已知不覆盖项（R6 显式记录）

- Actuator 内网受限 endpoint 的安全约束 IT（V2 一期 permitAll，安全 IT 归 PROD-READY-01）
- canary CLI 在 CI 环境的定时执行 E2E（归运维 PROD-READY-01）
- 日志检索手册中提及的外部日志平台（Elasticsearch / Splunk）查询语法 IT（V2 一期不接外部平台）
- 自动周期对账（V2 一期人工触发）

---

## §7. R6 落地状态

> 完整收口状态详见 [当前开发计划 §1](../setup/当前开发计划.md)。本节仅维护测试类清单 + 真测试结果。

| 测试类 | 计划 TC | 已落地 | 状态 |
| --- | --- | --- | --- |
| 6 服务 ActuatorHealthIntegrationTests | 10 | — | ⏳ R6 二轮 |
| canary command tests | 8 | — | ⏳ R6 二轮 |
| CanaryReportTests（工厂方法 + exit code 单元） | 3 | **3 / 3 pass** | ✅ Phase 2 |
| AdminReconciliationEndpointIntegrationTests | 10 | — | ⏳ R6 二轮 |
| reconciliationApi.test.ts | 3 | **3 / 3 pass** | ✅ Phase 4（TC-RECON-FE-050/051/052） |
| ReconciliationListPage.test.tsx | 3 | — | ⏳ R6 二轮（TC-RECON-FE-053/054/055） |
| TC-E2E-RECON-001 | 1 | — | ⏳ 浏览器 QA 就绪后 |

**合计 ~35 TC 计划，6 已落地（3 frontend + 3 backend 工厂方法 UT）**。完整收口含 commit/日期/已知不阻断项详见 [当前开发计划 §1 STAGE-11-OBS-RECON 条目](../setup/当前开发计划.md)。
