# STAGE-2-SYMBOL R7 验证报告

> 阶段 2.3 行情品种管理 R7 收口证据，覆盖：console-service 管理端 8 入口、market-service internal RPC、swap-rate Redis 快照即时刷新、trading-core Swap 快照消费链路、console-frontend 核心 UI 流程。

| 项 | 值 |
| --- | --- |
| 验证时间 | 2026-05-11 |
| 验证人角色 | R7 |
| 测试用例集 | [`STAGE-2-SYMBOL-test-cases.md`](STAGE-2-SYMBOL-test-cases.md)（81 TC 骨架） |
| 验证方式 | live API 脚本 + 目标回归测试 + 浏览器 QA 截图 |
| 整体结论 | **R7 通过**：后端/API `53 pass / 0 fail`，前端浏览器 7 流程截图通过，DevTools `runtimeErrors=0 / networkFailures=0 / symbol5xx=0`。 |

---

## 1. 本轮修复闭环

| 问题 | 根因 | 修复 | 验证 |
| --- | --- | --- | --- |
| market detail / trading-hours 500 | MyBatis `record` 构造参数使用 `Integer/Boolean`，未匹配 primitive `int/boolean` | `MarketSymbolAdminMapper.xml` ResultMap 改用 `_int/_boolean` | `GET /admin/symbols/1870` 与 `GET /admin/symbols/TAOUSD/trading-hours` 均 200，sessions=7 |
| swap-rate 管理端写入后 trading-core 不能立即读取 | `upsertSwapRate` 只写 MySQL，Redis 共享快照依赖启动/定时刷新 | `MarketSymbolAdminApplicationService` 在事务提交后调用 `MarketSwapRateWarmupService.refreshAll()` | R7 API 脚本验证 Redis `falconx:market:swap-rate:TAOUSD` 含新 `effectiveFrom` |
| symbol 高风险操作审计等级为 LOW | `HighRiskPermissionRegistry` 未登记 `symbol:*` 高风险权限 | 增加 `symbol:update / symbol:suspend / symbol:swap-rate:update` | `t_admin_operation_log` 最新三类 symbol 操作均为 `HIGH_RISK` |
| 浏览器 QA 出现 favicon 404 | console 前端未声明 favicon | `index.html` 增加 `/favicon.svg`，新增 `public/favicon.svg` | 浏览器重跑 `runtimeErrors=0` |
| 暂停/恢复弹窗打开后立即关闭 | 同一按钮同时挂 `Popconfirm` 和独立 `Modal`，两者共用 `pauseTarget`；Popconfirm 关闭回调清空状态 | 移除 Popconfirm，统一走高风险 Modal 状态流 | 浏览器验证弹窗保持打开、原因可输入、确认按钮可启用，截图 `/tmp/falconx-symbol-r7/pause-modal-fix.png` |

---

## 2. 命令验证证据

| 命令 | 结果 |
| --- | --- |
| `mvn -pl falconx-market-service -Dtest=MarketServiceApplicationTests test` | ✅ `2 tests, 0 failures` |
| `mvn -pl falconx-market-service -DskipTests package` | ✅ BUILD SUCCESS |
| `mvn -pl falconx-console-service -Dtest=HighRiskPermissionRegistryTests test` | ✅ `3 tests, 0 failures` |
| `mvn -pl falconx-console-service -DskipTests package` | ✅ BUILD SUCCESS |
| R7 API live script（日志：`logs/local/stage-2-symbol-r7-api-20260511-final.log`） | ✅ `SUMMARY pass=53 fail=0` |
| `mvn -pl falconx-trading-core-service -Dtest=TradingSwapSettlementIntegrationTests test` | ✅ `3 tests, 0 failures` |
| `npm run test`（`falconx-console-frontend`） | ✅ `1 file / 4 tests passed` |
| `npm run lint`（`falconx-console-frontend`） | ✅ exit 0；保留 customer 页面既有 2 个 hook warning |
| `npm run build`（`falconx-console-frontend`） | ✅ build 通过；保留 Vite 大 chunk warning |

---

## 3. API 覆盖

| 范围 | 结果 |
| --- | --- |
| console list/detail | ✅ 默认列表、category、marketCode、status、symbolLike、详情 sessions、not found `90600` |
| console update | ✅ 成功更新 + `90601/90602/90603/90604/99004` |
| console suspend/resume | ✅ 成功暂停/恢复 + 重复操作 `90605/90606` |
| console swap-rate | ✅ GET、PUT、Redis 快照刷新、重复日期 `90610`、越界 `90612`、过去日期 `90611`、reason validation `99004` |
| console trading-hours | ✅ CRYPTO 7 sessions、FX 5 sessions |
| market internal RPC | ✅ `90702/90701/90703` 鉴权错误、list/detail、direct not found HTTP 200 + business code `90600`；console 对外 not found HTTP 404 + `90600` |
| 审计 | ✅ `symbol:update / symbol:suspend / symbol:swap-rate:update` 最新审计均 `HIGH_RISK` |

测试后清理已完成：`TAOUSD` 恢复 `status=1 / maxLeverage=100 / takerFeeRate=0.000500 / minQty=100 / maxQty=800000 / minNotional=100`，临时 `t_swap_rate` 行数为 `0`。

---

## 4. 浏览器 QA 证据

| 截图 | 状态 | 说明 |
| --- | --- | --- |
| `/tmp/falconx-symbol-r7/stage-2-symbol-r7-01-list-desktop.png` | ✅ | desktop 1440 列表加载，TAOUSD 数据可见 |
| `/tmp/falconx-symbol-r7/stage-2-symbol-r7-02-edit-drawer.png` | ✅ | 编辑 Drawer，reason 已填写，随后提交成功 |
| `/tmp/falconx-symbol-r7/stage-2-symbol-r7-03-suspend-modal.png` | ✅ | 暂停高风险 Modal，reason 输入与风险说明可见 |
| `/tmp/falconx-symbol-r7/stage-2-symbol-r7-04-resume-modal.png` | ✅ | 恢复高风险 Modal，reason 输入与风险说明可见 |
| `/tmp/falconx-symbol-r7/stage-2-symbol-r7-05-swap-drawer-history.png` | ✅ | Swap Rate Drawer，提交后 history 刷新并显示新日期 |
| `/tmp/falconx-symbol-r7/stage-2-symbol-r7-06-trading-hours-modal.png` | ✅ | Trading Hours 只读弹窗，sessions 表可见 |
| `/tmp/falconx-symbol-r7/stage-2-symbol-r7-07-list-mobile.png` | ✅ | mobile 390x844 列表加载 smoke |

DevTools 汇总文件：`/tmp/falconx-symbol-r7/stage-2-symbol-r7-browser-summary.json`

```json
{
  "runtimeErrors": [],
  "networkFailures": [],
  "badSymbolResponses": [],
  "symbolResponseCount": 18
}
```

---

## 5. 完成判定

| 判定项 | 结果 |
| --- | --- |
| R4/R9/R10 代码可运行，R7 发现的问题已修复 | ✅ |
| 后端 API、market internal RPC、Redis 快照、trading-core 快照消费链路均有验证证据 | ✅ |
| 管理端前端测试、lint、build、浏览器桌面/移动截图齐全 | ✅ |
| 高风险操作二次确认态、审计落库和 RBAC 权限点覆盖 | ✅ |
| R8 文档同步 | ✅（本报告 + 当前开发计划 + BBook + docs 索引 + TC 映射） |

**结论**：阶段 2.3 行情品种管理 R7 验证收口通过，可作为后续阶段 2.4 / 2.5 继续推进的基线。

**保留说明**：`STAGE-2-SYMBOL-test-cases.md` 中建议的完整 `AdminSymbolControllerIntegrationTests / MarketSymbolAdminInternalControllerIntegrationTests / AdminSymbolE2ETests / SymbolListPage.test.tsx` 尚未逐项落成。本轮以 live API、目标回归测试和浏览器 QA 完成 R7 收口；后续若要把 81 TC 全部转成 CI 自动化，需要单独排测试债务任务。
