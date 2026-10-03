# STAGE-6 KYC 客户端提交 Drawer 设计

> R3 输出。本文件冻结客户端 KYC 提交入口与 Drawer 的信息架构、状态表、字段集、响应式规则与高风险态。
> R5 实施前必须先读取本文件 + [`R2 KYC 契约`](../api/管理端接口规范.md)（KYC 用户侧 REST 已在 commit `94ac29b` 落地于 `/api/v1/me/kyc`）+ [`KycReviewedEventPayload`](../../falconx-identity-contract/src/main/java/com/falconx/identity/contract/event/KycReviewedEventPayload.java)。

## 1. 设计目标

- 用户在终端任意页面都能一键打开 KYC 提交 Drawer
- 用户清晰看到当前 KYC 状态（未提交 / 审核中 / 已通过 / 已拒绝）
- 提交流程不打断当前交易上下文（Drawer 非全屏覆盖式 Modal）
- 复用现有视觉系统（`fx-modal` + `tokens.css` + `ProfilePanel` 风格）
- 不引入新设计系统、UI 框架或图标库

## 2. 入口位置

| 入口 | 位置 | 行为 |
| --- | --- | --- |
| **主入口** | `TerminalTopbar` — 在 `NotificationCenter` 与 `个人资料按钮` 之间新增一个 `KYC 认证` 按钮 | 点击打开 `KycSubmitDrawer` |
| 入口图标 | `lucide-react` 的 `ShieldCheck`（已通过）/ `ShieldAlert`（未通过 / 待审 / 已拒）| 不引入新图标库 |
| 状态徽章 | 按钮右上角小圆点：`未提交` 灰色 / `PENDING` 蓝色 / `REJECTED` 红色 / `APPROVED` 绿色 | 复用 `notification-bell__badge` 同款 |
| 出金触发跳转 | 阶段 7 出金前置失败时（错误码待 R2 阶段 7 冻结）→ 顶层调用打开本 Drawer | 本阶段先暴露 props `defaultOpen` |

按钮文案：`KYC 认证`（已通过时显示 `KYC 已通过`）。

> ❌ 不在 `ProfilePanel` 内嵌 KYC tab —— 用户选择 TerminalTopbar 独立按钮，避免与个人资料资料编辑混淆。

## 3. 信息架构与状态表

Drawer 入口加载时调用 `GET /api/v1/me/kyc` 获取最近一次 submission，按返回值分支：

| 后端返回 | Drawer 视图 | 用户可操作 |
| --- | --- | --- |
| `null`（从未提交） | 显示"开始 KYC 认证"提示 + 提交表单 | 提交 |
| `status = "PENDING"` | 显示"审核中"只读视图 + 提交时间 + 提交 ID + 提交字段（脱敏 idNumber 后 4 位） | 仅关闭 |
| `status = "APPROVED"` | 显示绿色成功 banner + 通过时间 + "您的姓名 / 出生日期 / 国籍已锁定，需重新 KYC 才能修改" 提示 | 仅关闭 |
| `status = "REJECTED"` | 显示红色失败 banner + 拒绝原因 + 拒绝时间 + 提交表单（允许重新提交） | 重新提交 |

补充状态：

| 状态 | 表现 |
| --- | --- |
| `loading` | Drawer 内 `加载中…`（与 ProfilePanel `fx-modal-empty` 一致） |
| `error`（GET 失败） | 显示错误 banner + `重试` 按钮 |
| `submitting` | 提交按钮变 `提交中…` + disabled，整 form fieldset disabled |
| `submit error` | 表单顶部红色 banner + 错误码（来自 `FalconApiError`） |
| `submit success`（即时） | 切换为 `PENDING` 视图，并派发 `falconx:toast` `KYC 已提交，等待审核` |

## 4. 提交表单字段

| 字段 | 控件 | 校验 | 备注 |
| --- | --- | --- | --- |
| `idType` | `select` 三选一 | 必填 | 选项：`ID_CARD` / `PASSPORT` / `DRIVER_LICENSE`；展示为 `身份证 / 护照 / 驾照` |
| `idNumber` | `input maxLength=64` | 必填，非空白 | 不在前端做正则校验，由后端 `10040` 报错回显 |
| `idFront` | `input type="file" accept="image/jpeg,image/png,image/webp"` | 必填 | 上传后立即 `FileReader.readAsDataURL`，去掉 `data:image/xxx;base64,` 前缀后存 `idFrontBase64` |
| `idBack` | 同上 | 必填 | 同上 |
| `selfie` | 同上 | 必填 | 手持证件自拍 |

文件大小限制（**前端强校验**，避免提交后 413）：

- 单文件原始 ≤ 2 MB（base64 后约 2.7 MB）
- 超出 → 显示红色提示 `证件图片过大（最大 2MB）`

mime 类型：传后端 `idFrontMimeType` 等字段（值为 `image/jpeg` / `image/png` / `image/webp`）。

## 5. 响应式规则

| 断点 | Drawer 宽度 |
| --- | --- |
| 桌面（≥ 1024px）| 固定 `600px`，从右侧滑入 |
| 移动（< 1024px） | 100vw 全屏覆盖 |

复用现有 `.fx-modal.fx-modal--wide` 类，已支持响应式自适应；本阶段不新增 CSS 断点变量。

## 6. 视觉规则（复用 tokens）

- 成功 banner：复用 `profile-form__success` 的绿色样式
- 失败 banner：复用 `fx-modal-error` 的红色样式
- 审核中 banner：使用 `.notification-item.level-info` 的蓝色配色
- 表单 fieldset / legend：复用 `.profile-form fieldset` 与 `.profile-form__row`
- 文件预览（缩略图）：在 file input 下方显示 80×80px 的缩略图，复用 `.notification-item` 的圆角与边框

## 7. Drawer 关闭规则

- 点击右上角 `×` 关闭
- 点击背景遮罩（`.fx-modal-backdrop`）关闭
- `Escape` 关闭（参考 NotificationCenter 现有 ESC handler）
- 提交中（`submitting=true`）时 **禁用关闭**（避免请求孤儿）

## 8. 数据依赖（来自 R2 契约）

| 来源 | 字段 |
| --- | --- |
| `POST /api/v1/me/kyc`（已存在）| `idType / idNumber / idFrontBase64 / idFrontMimeType / idBackBase64 / idBackMimeType / selfieBase64 / selfieMimeType` |
| `GET /api/v1/me/kyc`（已存在）| `submissionId / userId / level / status / idType / idNumber / submittedAt / reviewAt / rejectReason` |
| 错误码 | `10040 / 10041 / 10042 / 10043`（已在 `IdentityErrorCode` 落地） |
| Kafka 事件触发（实时刷新）| `falconx.identity.kyc.reviewed` → trading-core 写 `t_notification` → WS `notification.created` → 复用 NotificationCenter 的 `falconx:notification:created` window event → KycSubmitDrawer 内 React Query invalidate `["identity", "kyc", "latest"]` |

## 9. 高风险态

KYC 提交属于 **一次性高风险操作**（提交后 PENDING 期内不可重提）。视觉上：

- 表单底部加灰底说明："提交后将进入审核流程，期间不可修改；审核完成前请不要刷新页面"
- 提交按钮文案使用主色（与 `fx-btn-primary` 一致），不需要二次确认 Modal（资料未到资金动作级，复合 `console-DESIGN §7.3` 的"非资金类高风险不强制二次确认"）

## 10. Toast 集成

- `submit success` → `window.dispatchEvent("falconx:toast", { kind: "ok", text: "KYC 已提交，等待审核" })`
- 收到 `notification.created` 且 `relatedKey === "kyc.reviewed"` 时 → NotificationCenter 已自动弹 toast（无需 KycSubmitDrawer 额外弹）

## 11. 文件结构（R5 实施时落点）

```
src/features/kyc/
  kycApi.ts            -- submitKyc(token, payload) / getLatestKyc(token)
  types.ts             -- KycStatus / KycIdType / KycSubmission
  KycSubmitDrawer.tsx  -- 主组件
  KycSubmitDrawer.test.tsx  -- Vitest 用例
```

`TerminalTopbar.tsx` 增加一个 `onOpenKyc` prop（与现有 `onOpenProfile` 同模式），由 `TerminalPage` 持有 `kycOpen` 状态。

## 12. R5 实施前置检查（自检卡）

- [ ] 已读取本文件全部章节
- [ ] 已读取 `R2 KycReviewedEventPayload` contract
- [ ] 已确认 `POST /api/v1/me/kyc` 与 `GET /api/v1/me/kyc` 字段名与本文件一致
- [ ] 已检查 `tokens.css` 与 `.fx-modal--wide` 复用边界
- [ ] 已检查 `NotificationCenter` 的 `falconx:notification:created` window event 监听复用方案
- [ ] 已确认本阶段不引入新 UI 框架、新图标库、新 CSS 断点变量
