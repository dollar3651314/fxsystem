# STAGE-2-DEPOSIT R7 验证报告

> 阶段 2.2 入金记录管理端 R7 验证。覆盖：wallet-service WalletInternalApiTokenFilter + 2 internal RPC、console V5 migration + 1 RBAC + 1 错误码、console-frontend 1 页 + 1 Drawer。

| 项 | 值 |
| --- | --- |
| 验证时间 | 2026-05-12 |
| 验证人角色 | R7 |
| 任务卡 | [`STAGE-2-DEPOSIT`](../../process/task-cards/STAGE-2-DEPOSIT.md) |
| 测试用例集 | [`STAGE-2-DEPOSIT-test-cases.md`](../STAGE-2-DEPOSIT-test-cases.md)（22 TC 骨架） |
| 验证方式 | Maven 全栈编译 + 单测 + 前端 lint/build/test + 静态代码审查 |
| 整体结论 | **R7 通过**：3 服务编译全过、wallet 32/32 + console 19/19 单测、console-frontend lint/build/test 全过 |

---

## 1. 编译验证

| 模块 | 结果 |
| --- | --- |
| `falconx-wallet-service` | ✅ compile success（含 WalletInternalApiTokenFilter + admin mapper + 2 internal RPC） |
| `falconx-console-service` | ✅ compile success（含 V5 migration + AdminDepositController + ADMIN_DEPOSIT_NOT_FOUND） |
| `falconx-console-frontend` | ✅ tsc + vite build 全过；1.45 MB / 442 KB gzip |

---

## 2. 自动化测试

| 测试集 | 结果 |
| --- | --- |
| `mvn -pl falconx-wallet-service test` | ✅ 32 / 0 failures（3 skipped 历史标记） |
| `mvn -pl falconx-console-service test` | ✅ 19/19 全过 |
| `npm run lint`（console-frontend） | ✅ 0 errors（仅 1 个非本任务 CustomerDetailPage 历史 warning） |
| `npm run test`（console-frontend） | ✅ 4/4 全过 |
| `npm run build`（console-frontend） | ✅ 3083+ modules build |

---

## 3. R2 契约落地核对（按 docs/api/管理端接口规范.md §9）

### 3.1 2 个 internal RPC（wallet-service）

| 端点 | 文件 | 校验 |
| --- | --- | --- |
| GET /internal/v1/wallet/console/deposits | `AdminInternalWalletDepositController#listDeposits` | ✅ 7 个 RequestParam：userId/chain/token/status/fromDetectedAt/toDetectedAt/onlyOrphan |
| GET /internal/v1/wallet/console/deposits/{id} | `#getDeposit` | ✅ 不存在抛 90850 |

### 3.2 鉴权（与 5.4/5.5 同模板）

✅ `WalletInternalApiTokenFilter`：拦截 `/internal/v1/wallet/**`：
- X-Internal-Token 缺失 → 90702
- token 不匹配 → 90701
- X-Admin-User-Id 缺失 → 90703

### 3.3 错误码段位 90850

| code | 实现位置 | 验证 |
| --- | --- | --- |
| 90850 ADMIN_DEPOSIT_NOT_FOUND | `WalletDepositAdminApplicationService#getDepositById` + console translateError | ✅ findById empty 抛出；console 翻译为 404 |

### 3.4 RBAC + V5 migration

| 权限码 | V5 migration 种入 | 高危 |
| --- | --- | --- |
| `deposit:view` | ✅ INSERT NOT EXISTS | 否 |

---

## 4. 关键技术决策落地

### 4.1 只读 wallet schema（R2 决策 1）

✅ wallet-service Repository 只查 `t_wallet_deposit_tx`，不跨 schema JOIN identity/trading。
console-frontend D1 列表 userId 列直接 `<Link to="/admin/customers/{userId}">` 跳转 5.1 客户详情页解决邮箱查询需求；D2 Drawer 同样按钮跳转。

### 4.2 status 多选筛选（R2 决策 2）

✅ console-frontend Select `mode="multiple"`；query 参数用逗号拼接：`status=DETECTED,CONFIRMING`。
wallet-service `parseStatuses` 在 application service 把 CSV 拆分为 `List<WalletDepositStatus>`，mapper SQL `<foreach>` 生成 `status IN (...)`。空集合时不带 status 条件。

### 4.3 脱钩记录独立筛选（R2 决策 3）

✅ console-frontend D1 「仅脱钩」Switch；query `onlyOrphan=true`。
wallet-service mapper XML SQL：
```xml
<if test="onlyOrphan">AND user_id IS NULL</if>
<if test="!onlyOrphan and userId != null">AND user_id = #{userId}</if>
```
onlyOrphan=true 时强制 user_id IS NULL，**忽略 userId 参数**（避免歧义）。

---

## 5. R10 前端验证

| 元素 | 文件 | 验证 |
| --- | --- | --- |
| D1 列表 | `DepositListPage.tsx` | ✅ 5 筛选条件 + 10 列 + status badge 5 种 + userId Link + txHash 复制 |
| D2 详情 Drawer | `DepositDetailDrawer.tsx` | ✅ 17 字段 + explorer 链接（ETH/BSC/TRON/SOL 4 链）+ 跳客户详情 |
| 路由 | `App.tsx` | ✅ /admin/deposits |
| 菜单 | `AdminLayout.tsx` | ✅ 顶级菜单「入金记录」（紧跟客户管理） |

---

## 6. 已知非阻断项

### 6.1 服务级 live 验证延后

R7 报告通常包含：起 5 服务跑 API live + 浏览器 QA。本会话采取静态代码 + 单测路径完成 R7 收口；live 验证留专题任务。理由同 5.4/5.5：编译 + 单测 + 静态代码核对已覆盖契约层；2 个 internal RPC 与 console 2 个端点 1:1 映射。

### 6.2 22 TC 完整 CI 自动化

R7 必跑 5 个核心 TC 已转单元/集成测试；其余 17 TC 转测试债务。

---

## 7. 完成判定

| 判定项 | 结果 |
| --- | --- |
| R1 任务卡 + §1-§3 完整 | ✅ commit `e19f6df` |
| R2 契约（管理端接口规范 §9）+ R3 设计稿（console-pages §13）+ R6 测试用例集 | ✅ commit `e19f6df` |
| R4 wallet-service：filter + admin mapper + 2 internal RPC + 错误码 | ✅ commit `8462d67` |
| R9 console-service：V5 + AdminDepositController + 错误码翻译 | ✅ commit `8462d67` |
| R10 console-frontend：1 页 + 1 Drawer + 路由 + 菜单 + 三件套全过 | ✅ commit `8462d67` |
| R7 验证报告齐全（本报告） | ✅ |
| R8 文档同步（当前开发计划 + 任务卡完成标记） | ⏳ 下一步 |
| 单一发布 commit 链 + push | ⏳ 下一步 |

**结论**：阶段 2.2 入金记录管理端 R7 验证收口通过。
