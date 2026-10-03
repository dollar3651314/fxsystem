# docs/test/archive/ — 已完成阶段验证报告归档

> 本目录归档 STAGE-1 ~ STAGE-7 已收口阶段的 R7 验证报告 + 一次性测试报告。这些文件在产出时已凝固（commit SHA / 测试统计 / 浏览器 QA 截图证据均为快照），**不再更新**。
>
> 阶段当前状态请查 [当前开发计划 §1](../../setup/当前开发计划.md)。
>
> 活跃测试用例清单（`STAGE-*-test-cases.md`）仍在 `docs/test/` 根目录，未移到本目录。

---

## 归档文件清单（13 个）

### STAGE-2 阶段 2 管理端补齐（已收口）

| 文件 | 任务 | 收口日期 |
| --- | --- | --- |
| [`STAGE-2-CUSTOMER-R7-verification-report.md`](./STAGE-2-CUSTOMER-R7-verification-report.md) | 客户管理（跨 schema JOIN + internal RPC + 调余额限额）| 2026-05-09 |
| [`STAGE-2-DEPOSIT-R7-verification-report.md`](./STAGE-2-DEPOSIT-R7-verification-report.md) | 入金记录管理端 | 2026-05-12 |
| [`STAGE-2-RISK-ADMIN-R7-verification-report.md`](./STAGE-2-RISK-ADMIN-R7-verification-report.md) | 风控管理端（动作 + risk_config + market_config）| 2026-05-12 |
| [`STAGE-2-SYMBOL-R7-verification-report.md`](./STAGE-2-SYMBOL-R7-verification-report.md) | 行情品种管理端 | 2026-05-11 |
| [`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT-R7-verification-report.md`](./STAGE-2-SYMBOL-PARAMS-DOWNSHIFT-R7-verification-report.md) | Symbol 参数下沉到 mapping | 2026-05-12 |
| [`STAGE-2-SYMBOL-THREE-TABLE-ADMIN-R7-verification-report.md`](./STAGE-2-SYMBOL-THREE-TABLE-ADMIN-R7-verification-report.md) | Symbol 三表管理端补齐 | 2026-05-11 |
| [`STAGE-2-TRADING-CLIENT-WIRE-R7-verification-report.md`](./STAGE-2-TRADING-CLIENT-WIRE-R7-verification-report.md) | V1 下单功能接入客户端 | 2026-05-12 |
| [`STAGE-2-TRADING-MONITOR-R7-verification-report.md`](./STAGE-2-TRADING-MONITOR-R7-verification-report.md) | 订单与持仓监控管理端 | 2026-05-12 |
| [`STAGE-2-R7-LIVE-REGRESSION-2026-05-12.md`](./STAGE-2-R7-LIVE-REGRESSION-2026-05-12.md) | 阶段 2 整体 live 回归验证 | 2026-05-12 |

### STAGE-5 ~ STAGE-7 业务阶段（已收口）

| 文件 | 任务 | 收口日期 |
| --- | --- | --- |
| [`STAGE-5-WALLET-PROVISION-R7-verification-report.md`](./STAGE-5-WALLET-PROVISION-R7-verification-report.md) | 注册后地址预分配 + 管理端 DLQ 重试 | 2026-05-15 |
| [`STAGE-6-KYC-R7-verification-report.md`](./STAGE-6-KYC-R7-verification-report.md) | KYC（首次出金 + 地址不一致触发）| 2026-05-14 |
| [`STAGE-7-WITHDRAW-Phase3-B-verification-report.md`](./STAGE-7-WITHDRAW-Phase3-B-verification-report.md) | 出金 Phase 3 B 段 Sepolia 真链 E2E | 2026-05-14 |
| [`STAGE-7-WITHDRAW-Phase4-verification-report.md`](./STAGE-7-WITHDRAW-Phase4-verification-report.md) | 出金 Phase 4 管理端审核工作台 | 2026-05-15 |

---

## 关联

- 阶段状态真源：[当前开发计划 §1](../../setup/当前开发计划.md)
- 执行手册：[BBook 一期完成执行路径](../../process/BBook一期完成执行路径.md)
- 活跃测试用例清单：[`docs/test/`](../) 根目录下的 `STAGE-*-test-cases.md`
