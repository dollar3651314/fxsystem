# 管理端高危操作风控档位

> 本文件是 **管理端高危操作前端二次确认档位**的唯一真源。所有「直接动钱 / 平台级风控开关」类操作前端必须按本档位渲染统一 modal，禁止再造裸 `<Modal>`。
>
> 落地组件：`falconx-console-frontend/src/features/customer/HighRiskConfirmModal.tsx`
>
> 落地范围：2026-05-19 收口，8 个 admin 端高危操作全部接入。

---

## §1. 档位定义

### 1.1 三重门（统一档位）

所有列入本档位的操作必须满足以下 **3 个独立校验**，任一未通过则提交按钮 `disabled`：

| 校验项 | 规则 | 组件机制 |
| --- | --- | --- |
| 操作原因 | TextArea，**长度 ≥ 10 字符**（必填，不接受空白） | `HighRiskConfirmModal` 内置 `reasonOk = reason.length >= 10` |
| 用户名挑战 | Input，必须输入**当前登录 admin 的 username**（`useAdminAuthStore.user.username`） | `usernameChallenge={{ expected, label }}` prop |
| 确认勾选 | Checkbox「我已确认…」 | `requireConfirmCheckbox` prop |

> **设计意图**：三道门各防一类风险——reason 防记录不可追溯、challenge 防 social engineering / 旁人借设备误操作、checkbox 防手滑点击。三者独立可降级（如某操作历史档位无 checkbox，本次升级时按需补齐即可）。

### 1.2 弹窗结构

```
┌──────────────────────────────────────────────┐
│  ⚠ 高风险操作 - <title>                   ✕  │
├──────────────────────────────────────────────┤
│  ⚠ <description> （Alert warning）           │
│                                              │
│  <Descriptions>                              │
│   字段 A:    值 A                            │
│   字段 B:    值 B                            │
│  </Descriptions>                             │
│                                              │
│  <children />（可选，注入额外字段如 Radio）  │
│                                              │
│  操作原因（必填，≥ 10 字符）                 │
│  ┌────────────────────────────────────────┐  │
│  │                                        │  │
│  └────────────────────────────────────────┘  │
│  已输入 0 字符                               │
│                                              │
│  请输入您的用户名 "alice" 确认               │
│  ┌────────────────────────────────────────┐  │
│  │                                        │  │
│  └────────────────────────────────────────┘  │
│  用于最高危操作防误操作                      │
│                                              │
│  ☐ 我已确认 <action> 该 <object>            │
│                                              │
│           [ 取消 ]    [ ⚠ 确认 <op> ]        │
└──────────────────────────────────────────────┘
```

### 1.3 错误码兜底

某些「已是该状态 / 已被其他流程处理」类的错误码不应视为业务失败，应在 `onSubmit` 内吞掉并 `message.warning(…)` 关闭弹窗。各操作的兜底码见 §2 表格。

真正业务失败（如 90802「非 MANUAL_ADMIN 触发不可停用」）应 `throw` 让 `HighRiskConfirmModal` 渲染错误 banner，保留弹窗供 admin 重试或撤销。

---

## §2. 操作清单（2026-05-19 收口，8 项全部三重门）

| # | 操作 | 业务影响 | 源文件 | 错误码兜底 |
| --- | --- | --- | --- | --- |
| 1 | 出金紧急取消 | 余额立即解冻、撤销已审核结论 | `features/withdraw/WithdrawDetailPage.tsx` | — |
| 2 | 客户余额调整 | 直接 ledger 增减、动用户真实余额 | `features/customer/CustomerDetailPage.tsx` | — |
| 3 | 强制撤单（admin） | 用户挂单立即撤销 + 冻结资金解冻 | `features/trading/TradingPendingOrderListPage.tsx` | — |
| 4 | 强制删除价格告警 | 用户告警永久失效，不再触发通知 | `features/trading/TradingPriceAlertListPage.tsx` | — |
| 5 | 手动强平 | 以当前最新报价立即关闭用户持仓，不可撤销 | `features/trading/ManualLiquidateModal.tsx` | 90651 已平→成功 / 90652 锁定→可重试 |
| 6 | 全局自动强平开关 | trading-core 平台级风控开关，影响所有 OPEN 持仓 | `features/trading/AutoLiquidateSwitchModal.tsx` | 90655 已是该状态→成功 |
| 7 | 激活风控动作 | REJECT_OPEN / REDUCE_ONLY / SUSPEND_SYMBOL / **GLOBAL_PAUSE 全平台暂停** | `features/risk/RiskActionActivateModal.tsx` | 90800 已激活→成功 |
| 8 | 停用风控动作 | 撤销已激活的风控动作 | `features/risk/RiskActionDeactivateModal.tsx` | 90801 不存在→成功 / 90802 非 MANUAL_ADMIN→可重试 |

> **注**：KYC 拒绝 / RBAC 删除菜单 / 删除符号映射等 **不在本档位**（中等风险，只需 reason 必填即可，无需 challenge）。新增「直接动钱 / 平台级风控开关」类操作时必须列入本表。

---

## §3. 使用规范

### 3.1 调用方约定

```tsx
import { HighRiskConfirmModal } from "features/customer/HighRiskConfirmModal";
import { useAdminAuthStore } from "lib/auth/adminAuthStore";

const adminUser = useAdminAuthStore((s) => s.user);

<HighRiskConfirmModal<TResult>
  open={open}
  title="<具体操作名>"
  okText="确认 <操作>"
  description="<一句话说明业务影响 + 是否可逆>"
  details={[
    ["关键字段 A", value],
    ["关键字段 B", value],
  ]}
  requireConfirmCheckbox
  usernameChallenge={
    adminUser
      ? { expected: adminUser.username, label: `请输入您的用户名 "${adminUser.username}" 确认` }
      : undefined
  }
  onSubmit={async (reason) => {
    try {
      return await api.doDangerousOp(id, reason);
    } catch (err) {
      if (err instanceof ApiError && err.code === "<兜底码>") {
        message.warning("<兜底语>");
        return null; // 视作成功结束
      }
      throw err;
    }
  }}
  onSuccess={(result) => { /* toast + 刷新 */ }}
  onCancel={onClose}
/>
```

### 3.2 复杂表单（Radio / 自定义输入）

如操作需要额外字段（如「激活风控动作」需要 Radio + Symbol Input），通过 `children` 注入 modal 内部，state 由父组件持有，`onSubmit` 时一并发送。**禁止再写裸 `<Modal>`**。

### 3.3 `validate` 钩子

`HighRiskConfirmModal` 提供 `validate?: () => string | null` prop，用于 reason 之外的局部校验（如「非 GLOBAL_PAUSE 时 symbol 必填」）。返回 `null` 通过，返回字符串则在按钮点击时显示为错误 banner。

### 3.4 测试规范

每个新增/重构的高危 modal **必须**至少包含一条 Vitest 用例验证三重门生效（参考 `features/customer/HighRiskConfirmModal.test.tsx` 的 TC-HRC-FE-004）：

- reason ≥ 10 字符**且** challenge 匹配**且** checkbox 勾选 → 按钮启用
- 任一缺失 → 按钮 `disabled`

---

## §4. 审计 / RBAC 关联

- 8 项操作对应的 RBAC 权限点（如 `withdraw:emergency-cancel`、`customer:balance:adjust`）已在 `HighRiskPermissionRegistry` 注册为 `HIGH_RISK`
- `OperationAuditAspect` 在 controller 命中 `@RequiresPermission(HIGH_RISK)` 时 after-returning 自动写 `t_admin_operation_log`，记录：admin user / target / reason / before-after / IP / 操作时间戳
- 前端的三重门是**第一道防线**（防误操作 / social engineering），不替代后端的鉴权 + 审计 + CAS 串行化

---

## §5. 浏览器 QA 截图

8 项全部走 Playwright 完成端到端浏览器 QA，每个 modal 都点开到「三重门齐全可提交」状态：
[`docs/test/screenshots/admin-high-risk-modals/`](../test/screenshots/admin-high-risk-modals/README.md)
（10 张 PNG：00 登录 + 01 仪表盘 + 02-09 八个 modal）。

## §6. 变更记录

| 日期 | 变更 | 关联 commit |
| --- | --- | --- |
| 2026-05-15 | `HighRiskConfirmModal` 组件首版（reason + checkbox） | STAGE-7 Phase 4 commit 2 |
| 2026-05-19 | 加 `usernameChallenge` prop（首次接入出金紧急取消） | §4 commit C |
| 2026-05-19 | 客户余额调整接入挑战项 + 单测 4 条 | `55bd3ec` |
| 2026-05-19 | 强制撤单 / 强制删除告警拉齐档位 | `e42254d` |
| 2026-05-19 | 手动强平 / 全局自动强平开关拉齐档位 | `f832b97` |
| 2026-05-19 | 风控动作激活 / 停用拉齐档位（8 项全部完成） | `bfb6f40` |
| 2026-05-19 | 本档位文档落地 | `387ec8a` |
| 2026-05-19 | 浏览器 QA 10 张截图归档（chromium 系统依赖解锁） | （本 commit）|
