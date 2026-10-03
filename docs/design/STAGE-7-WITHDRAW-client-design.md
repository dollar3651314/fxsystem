# STAGE-7-WITHDRAW 客户端设计（R3，Phase 0 冻结）

> 本文档冻结 falconx-frontend 端出金链路的视觉、交互与状态结构。R2 契约见 [REST 接口规范 §9.2](../api/REST接口规范.md#92-钱包出金-api-冻结契约stage-7-withdraw-phase-0)。
>
> 落地组件位于 `falconx-frontend/src/features/withdraw/`（Phase 1+ 创建）；遵循现有 `tokens.css` + 复用 `KycSubmitDrawer` / `OrderTicket` 风格。

## §1. 页面清单

| 页面 | 入口 | Mobile 适配 | 关联 hook |
| --- | --- | --- | --- |
| 出金申请 Drawer | TerminalTopbar "出金" 按钮 | drawer 居中 + 表单纵向 | `useWithdrawSubmit` |
| 白名单管理 Drawer | 出金 Drawer 内 "管理白名单" 链接 | 列表 + 新增 + 删除 | `useWithdrawWhitelist` |
| 出金历史 | TerminalTopbar "通知" 列表项跳转 / Activity tab | 列表纵向 + 分页 | `useWithdrawHistory` |
| 出金详情 Modal | 历史列表点击 | 模态居中 + 关键字段 | `useWithdrawDetail` |

## §2. 出金申请 Drawer

### 2.1 状态机驱动的视图分支

| 业务状态 | 视图 |
| --- | --- |
| `kycLevel = 0` | 红色 banner "未完成 KYC" + 按钮 "去 KYC" → 跳转 KycSubmitDrawer |
| `kycLevel ≥ 1` 但无白名单 | banner "请先添加白名单地址" + 按钮 "管理白名单" → 跳转 §3 |
| `kycLevel ≥ 1` 有 ACTIVE 白名单 | 渲染完整申请表单 |

### 2.2 表单字段

```
┌─ 出金申请 ─────────────────────────────┐
│  [×]                                       │
│                                            │
│  可用余额：  1,234.50 USDT                 │
│  ──────────────────────────────────────   │
│                                            │
│  网络         [▼ ERC20]                    │
│  目标地址     [▼ 选择白名单地址 ▼]          │
│                Ledger - 0xabc...d12        │
│                                            │
│  金额（USDT） [           100.50  ]        │
│  说明：最小 $10，单笔最大 $10K              │
│        当日已用 $250 / $30K                 │
│                                            │
│  [冷静期提示卡片]                          │
│  ⓘ 提交后将进入 2 小时冷静期，期间您可自助取消 │
│    若金额 ≥ $3000，审核通过后将额外延迟 6h │
│                                            │
│  [submit error banner，红色]               │
│                                            │
│  [关闭]                  [提交出金]        │
└────────────────────────────────────────────┘
```

### 2.3 提交后切到只读视图

提交成功后 Drawer 渲染：

```
┌─ 出金已提交 ───────────────────────────┐
│                                            │
│  [绿色 banner] 出金申请已提交，处于冷静期   │
│                                            │
│  提交编号    48275238449975296             │
│  金额        100.50 USDT                   │
│  目标        ERC20 - 0xabc...d12           │
│  状态        ⏳ COOLING                     │
│  可取消至    2026-05-14 07:08              │
│                                            │
│  [立即取消]   [关闭]                       │
└────────────────────────────────────────────┘
```

- "立即取消" 按钮调用 `POST /api/v1/me/withdraw/{id}/cancel`
- WebSocket `notification.created`（status 变化通知）触发 `useQuery(["withdraw","detail",id])` invalidate 自动刷新状态徽章

## §3. 白名单管理 Drawer

```
┌─ 白名单地址 ───────────────────────────┐
│  [×]                                       │
│                                            │
│  ⓘ 新增地址需 24h 冷静期才能用于出金        │
│                                            │
│  + 添加新地址                              │
│  ┌──────────────────────────────────────┐ │
│  │ 网络     [▼ ERC20]                   │ │
│  │ 地址     [0x...]                     │ │
│  │ 备注     [可选]                      │ │
│  │                          [取消][保存]│ │
│  └──────────────────────────────────────┘ │
│                                            │
│  当前白名单（4/10）                        │
│  ┌──────────────────────────────────────┐ │
│  │ Ledger Cold Wallet     [ACTIVE]      │ │
│  │ ERC20 0xabc...d12   2026-04-01 添加  │ │
│  │                              [删除]  │ │
│  ├──────────────────────────────────────┤ │
│  │ Binance                [PENDING]     │ │
│  │ ERC20 0xdef...456   12h 后可用       │ │
│  │                              [删除]  │ │
│  └──────────────────────────────────────┘ │
└────────────────────────────────────────────┘
```

交互：

- 删除带二次确认 modal "确定删除？删除后该地址需重新冷静期 24h"
- 新增地址表单中 `network` 选择器 + 地址格式校验（前端基础校验，后端最终校验）
- ACTIVE 状态绿色徽章，PENDING 灰色 + 倒计时

## §4. 出金历史

集成在 Activity tab 下新增 "出金" 子 tab（与 "持仓 / 挂单 / 订单 / 成交 / 告警" 并列）：

```
┌─ Activity → 出金 ─────────────────────────────────────────┐
│                                                              │
│  ID            金额      网络    目标            状态        │
│  482752384...  100.50    ERC20   0xabc...d12   ⏳ COOLING   │
│  482751111...  500.00    TRC20   TQfx...abc    ✅ COMPLETED │
│  482750000...  3,200.00  ERC20   0xdef...ghi   ⏱ APPROVED_  │
│                                                  DELAYED     │
│  482749999...  50.00     ERC20   0xabc...d12   ❌ REJECTED  │
│                                                              │
│           [< 1 2 3 >]   每页 [20 ▾]                          │
└──────────────────────────────────────────────────────────────┘
```

状态徽章 token：

- `COOLING` / `PENDING` / `APPROVED_DELAYED` → 黄色（waiting）
- `APPROVED` / `PROCESSING` → 蓝色（in-flight）
- `COMPLETED` → 绿色
- `FAILED` / `REJECTED` / `CANCELED` → 红色 / 灰色

## §5. 出金详情 Modal

点击列表行：

```
┌─ 出金详情 #48275238449975296 ──────────────────┐
│  状态：✅ COMPLETED  (12 confirmations)           │
│                                                   │
│  金额    100.50 USDT     网络  ERC20              │
│  目标    0xabc...d12     创建  2026-05-14 13:08  │
│  审核    admin#777       完成  2026-05-14 13:20  │
│                                                   │
│  链上 tx                                         │
│  0xdef456789...abc                                │
│  [📋 复制]  [🔗 etherscan.io 查看]                │
│                                                   │
│                                       [关闭]     │
└───────────────────────────────────────────────────┘
```

FAILED 状态额外显示 `failureReason`，CANCELED 显示取消时间 + 来源（user/admin emergency）。

## §6. 实时推送集成

复用现有 `notification.created` WebSocket channel：

- `t_notification.type=withdraw.cooling` → topbar 计数 +1，列表行刷新
- `t_notification.type=withdraw.approved / .rejected / .emergency-canceled` → 同上
- `t_notification.type=withdraw.completed / .failed` → 同上 + 弹 Toast

页面订阅 `window.addEventListener("falconx:notification:created", handler)`，handler 调用 `queryClient.invalidateQueries({ queryKey: ["withdraw"] })`。

## §7. 表单交互细节

- 金额输入：实时格式化 + 显示 USD 等值（用 ticker price），单笔/单日上限超过时输入框右侧红色提示
- 目标地址：下拉只显示 ACTIVE 白名单；如有 PENDING 显示灰色禁用项 "PENDING（24h 冷静期未过）"
- 提交按钮：未填全 + KYC 未通过 + 余额不足均 disabled
- 大额提示：`amount >= $3000` 时表单底部出现黄色 banner "金额较大，审核通过后将额外延迟 6 小时，期间可由 admin 紧急取消"

## §8. 安全 / 隐私

- 浏览器侧不存储任何完整链上私钥、签名材料（KmsSigner 仅在 wallet-service 后端）
- 目标地址 mask 显示：`0xabc...d12`（前 4 后 4），点击展开完整显示
- 不发送 `X-Trace-Id`（按 AGENTS.md §3.13.5）
