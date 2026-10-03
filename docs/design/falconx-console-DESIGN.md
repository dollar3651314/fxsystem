# FalconX 管理端设计系统（V1，2026-05-08）

> 本文件是 `falconx-console-frontend` 的设计真源。设计原则：基于 Ant Design 5 默认设计语言做最小 FalconX 品牌覆盖，不引入新 UI 框架，不平行于 AntD 5 设计系统。
>
> 客户端（`falconx-frontend`）DESIGN.md 与本文件**职能分离、品牌呼应**：客户端是面向交易员的高速终端（深色 graphite），管理端是面向运营人员的数据密集后台（浅色为主 + 可选深色）。两者通过品牌色与状态色保持一致，其他维度按职能差异化。
>
> 阶段 1 R3 输出范围：本文件（设计系统）+ [`falconx-console-pages-V1`](./falconx-console-pages-V1.md)（5 个核心页面方案）。客户端按 V2 §3 阶段 1 三端要求豁免。

---

## §1. 设计北极星

FalconX 管理端要给运营人员的第一印象是：**数据可信、操作清晰、风险可见**。

界面遵循三条基线：

| 基线 | 内容 |
| --- | --- |
| 数据密集 | 表格、Form、Modal 是主要载体；视觉权重让位给数据本身 |
| 操作清晰 | 高风险动作（调余额、强平、激活全局风控）必须强视觉警告 + 二次确认 |
| 风险可见 | 风险等级、审计来源、操作人始终可追溯，不靠注释 |

**反模式**（管理端不做）：

- ❌ 装饰性渐变球、玻璃光效、装饰性插画（属于 C 端品牌区域）
- ❌ 自定义图标包（用 AntD 5 内置 `@ant-design/icons`）
- ❌ 平行于 AntD 的卡片视觉系统
- ❌ 大量动效或循环动画（管理端长时间停留，干扰阅读）
- ❌ 只靠颜色表达状态（必须文字/图标兜底，无障碍）

---

## §2. 与客户端的品牌一致性

### 2.1 共享 token（与 `falconx-frontend/src/styles/tokens.css` 一致）

| Token | 值 | 用途 |
| --- | --- | --- |
| `--fx-cyan` | `#54e6ff` | 品牌主色（亮色，仅用于强调与状态点） |
| `--fx-lime` | `#a8ff5c` | 涨/成功 |
| `--fx-short` | `#ff6378` | 跌/失败 |
| `--fx-risk` | `#ffb84d` | 风险/警告 |
| `--fx-danger` | `#ff3d57` | 强失败/危险 |

### 2.2 管理端扩展 token（仅管理端使用）

```css
/* falconx-console-frontend/src/styles/console-tokens.css */
:root {
  /* 品牌主色（管理端浅色背景上的深 cyan） */
  --fx-console-primary: #0c5e7e;        /* C 端 cyan 暗化 → AntD colorPrimary */
  --fx-console-primary-hover: #0a4d68;
  --fx-console-primary-active: #084458;

  /* 状态色（沿用 C 端语义，调整对比度适配浅色） */
  --fx-console-success: #389e0d;        /* C 端 lime 暗化版本 */
  --fx-console-warning: #d48806;        /* C 端 risk 暗化版本 */
  --fx-console-error: #cf1322;          /* C 端 danger 暗化版本 */

  /* 高风险动作专用红 */
  --fx-console-high-risk-bg: #fff1f0;
  --fx-console-high-risk-border: #ffa39e;
  --fx-console-high-risk-text: #cf1322;

  /* 表格密度 */
  --fx-console-table-padding-y-sm: 8px;
  --fx-console-table-padding-y-md: 12px;
  --fx-console-table-padding-y-lg: 16px;

  /* Form 密度 */
  --fx-console-form-item-margin-bottom: 16px;

  /* Drawer 宽度档位 */
  --fx-console-drawer-width-sm: 480px;
  --fx-console-drawer-width-md: 640px;
  --fx-console-drawer-width-lg: 800px;
  --fx-console-drawer-width-xl: 1080px;
}
```

### 2.3 共享 token / 扩展 token 的边界

- **基础语义**（间距 4/8/12/16/24/32、圆角 6/8/12、字号、tabular-nums）→ 共享 C 端 `tokens.css` 的语义命名，避免双源
- **品牌色暗化版本**（深 cyan、暗化的 lime/risk/danger）→ 管理端独有，写在 `console-tokens.css`
- **表格 / Form / Drawer 密度**→ 管理端独有

---

## §3. AntD 5 ConfigProvider 主题配置

管理端**唯一允许**通过 ConfigProvider 注入主题，不允许 `:where` 选择器穿透 AntD 内部样式或在组件外强行覆盖。

```tsx
// falconx-console-frontend/src/providers/ConsoleAntdProvider.tsx
import { ConfigProvider, theme } from 'antd';
import zhCN from 'antd/locale/zh_CN';

export const ConsoleAntdProvider: React.FC<PropsWithChildren> = ({ children }) => (
  <ConfigProvider
    locale={zhCN}
    theme={{
      algorithm: theme.defaultAlgorithm,  // 浅色优先；后续阶段可切 darkAlgorithm
      token: {
        // 品牌色覆盖（最小集）
        colorPrimary: '#0c5e7e',
        colorSuccess: '#389e0d',
        colorWarning: '#d48806',
        colorError:   '#cf1322',
        colorInfo:    '#0c5e7e',

        // 字体（与 AntD 5 默认系统栈一致，简体中文兜底）
        fontFamily: '-apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif',

        // 圆角（与 C 端 --fx-radius-sm 一致）
        borderRadius: 6,

        // 间距（4px 基底，与 C 端一致）
        sizeUnit: 4,
        sizeStep: 4,
      },
      components: {
        Layout: {
          headerBg: '#ffffff',
          headerHeight: 56,
          siderBg: '#001628',          // 深色侧边栏（与 C 端 surface-1 呼应）
          triggerBg: '#001f3a',
        },
        Menu: {
          darkItemBg: '#001628',
          darkItemSelectedBg: '#0c5e7e',
          darkItemHoverBg: '#002645',
        },
        Table: {
          headerBg: '#fafafa',
          rowHoverBg: '#f5f5f5',
          cellPaddingBlockMD: 12,
          cellPaddingBlockSM: 8,
        },
        Form: {
          itemMarginBottom: 16,
        },
        Button: {
          // 危险按钮按 C 端 danger 一致
          colorErrorHover: '#a8071a',
        },
      },
    }}
  >
    {children}
  </ConfigProvider>
);
```

**禁止事项**：

- ❌ 禁止在 `console-tokens.css` 之外用 `!important` 覆盖 AntD 样式
- ❌ 禁止 fork AntD 组件源码（如必须扩展，封装薄层组件）
- ❌ 禁止引入 `tailwindcss`、`styled-components`、`emotion` 与 AntD 平行
- ❌ 禁止引入除 `@ant-design/icons` 之外的图标包

---

## §4. 信息架构

### 4.1 整体布局（桌面端 ≥ 1280px）

```
┌─────────────────────────────────────────────────────────────┐
│ 顶栏 (header, 56px)                                          │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ [Logo + FalconX Console]    面包屑    [搜索] [通知] [Avatar]
│ └─────────────────────────────────────────────────────────┘ │
├──────────────┬──────────────────────────────────────────────┤
│              │                                              │
│  侧边栏       │    内容区 (content)                          │
│  (sider,     │    ┌──────────────────────────────────────┐  │
│   240px)     │    │ 页面标题 + 操作按钮                    │  │
│              │    ├──────────────────────────────────────┤  │
│  - 仪表盘     │    │ 筛选条 (PageHeader + Form 横排)        │  │
│  - 客户管理   │    ├──────────────────────────────────────┤  │
│  - 入金管理   │    │                                      │  │
│  - 出金审核   │    │   主内容区（Table / Form / Card）      │  │
│  - 交易监控   │    │                                      │  │
│  - 风控管理   │    │                                      │  │
│  - 行情品种   │    │                                      │  │
│  - 通知模板   │    ├──────────────────────────────────────┤  │
│  - 审计查询   │    │ 分页 / 操作栏                          │  │
│  - 管理员     │    └──────────────────────────────────────┘  │
│    └ 用户     │                                              │
│    └ 角色     │                                              │
│    └ 菜单     │                                              │
│    └ 权限点   │                                              │
└──────────────┴──────────────────────────────────────────────┘
```

### 4.2 响应式断点

管理端面向运营人员，主要在 PC 浏览器使用，移动端不是核心场景。

| 断点 | 行为 |
| --- | --- |
| `≥ 1440px` | 默认布局：240 sider + 自适应 content |
| `1280-1440px` | sider 变窄到 200px |
| `1024-1280px` | sider 默认折叠为 80px（仅图标），可手动展开 |
| `< 1024px` | sider 隐藏，顶栏显示汉堡菜单；表格强制横向滚动；不优化移动端体验，但保证可用 |

### 4.3 颜色分区映射

侧边栏 / 顶栏 / 内容区使用三套颜色，建立"导航 - 内容"的视觉层级：

| 区域 | 背景 | 文本 | 说明 |
| --- | --- | --- | --- |
| 侧边栏 (sider) | `#001628`（深色） | `rgba(255,255,255,0.85)` | 与 C 端 surface-1 呼应；选中项用 `colorPrimary` 实色 |
| 顶栏 (header) | `#ffffff` | `rgba(0,0,0,0.88)` | 浅色，与内容区无视觉断裂 |
| 内容区 (content) | `#f0f2f5` | `rgba(0,0,0,0.88)` | AntD 5 默认浅灰；卡片白底 |

阶段 1 仅做浅色模式；深色模式（`theme.darkAlgorithm`）作为后续阶段扩展，本轮不实现。

---

## §5. 字体规范

| 用途 | 字体 / 字号 / 字重 | 备注 |
| --- | --- | --- |
| 页面标题 | 系统栈 / 24px / 600 | 不使用 Inter |
| 卡片标题 / 分区 | 系统栈 / 16px / 600 | |
| 表头 | 系统栈 / 14px / 600 | |
| 表格正文 | 系统栈 / 14px / 400 | |
| Form Label | 系统栈 / 14px / 400 | |
| 按钮 | 系统栈 / 14px / 400 | 主按钮 500 |
| 数字（金额、ID、计数） | 系统栈 + `font-variant-numeric: tabular-nums` | 禁止字符跳动 |
| 代码 / Hash / Token JTI | `Menlo, Consolas, "Courier New", monospace` / 13px | |

**`letter-spacing` 全局固定为 0**（与 C 端规则一致）。

---

## §6. 间距与圆角

### 6.1 间距（与 C 端 `--fx-space-*` 一致）

| Token | 值 | 用法 |
| --- | --- | --- |
| `--fx-space-1` | 4px  | 紧凑内边距、Tag 内距 |
| `--fx-space-2` | 8px  | 表格单元格 vertical |
| `--fx-space-3` | 12px | Form 项垂直间距、按钮内边距 |
| `--fx-space-4` | 16px | 卡片内边距、Form 项 margin-bottom |
| `--fx-space-5` | 20px | — |
| `--fx-space-6` | 24px | 卡片之间、Drawer 内边距 |
| `--fx-space-8` | 32px | 页面顶部 padding-top |

### 6.2 圆角（与 C 端 `--fx-radius-*` 一致）

| Token | 值 | 用法 |
| --- | --- | --- |
| `--fx-radius-sm` | 6px | AntD `borderRadius` 默认（按钮、Input） |
| `--fx-radius-md` | 8px | 卡片 / Modal |
| `--fx-radius-lg` | 12px | Drawer / 弹层（保留，本阶段不用） |

**禁止**：禁止整页大圆角；禁止漂浮卡片中再嵌卡片。

---

## §7. 状态与反馈

### 7.1 通用状态色（基于 AntD 5 token，沿用 C 端语义）

| 状态 | colorToken | 用法 | 视觉示例 |
| --- | --- | --- | --- |
| 信息 / 默认 | `colorInfo` `#0c5e7e` | 中性提示、Steps active | 蓝点 + 文字 |
| 成功 | `colorSuccess` `#389e0d` | 通过、激活、成功 | ✓ + 文字 |
| 警告 | `colorWarning` `#d48806` | 风险、待审核、待处理 | ⚠ + 文字 |
| 失败 / 危险 | `colorError` `#cf1322` | 拒绝、失败、强平、冻结 | ✕ + 文字 |
| 中性禁用 | `rgba(0,0,0,0.25)` | 禁用按钮、不可操作 | 灰 + 文字 |

### 7.2 业务专用状态映射

| 业务状态 | 视觉 | AntD 组件 |
| --- | --- | --- |
| 用户 ACTIVE / FROZEN | success Tag / error Tag | `<Tag color="green">` / `<Tag color="red">` |
| 入金 PENDING / DETECTED / CONFIRMED / REVERSED | warning / info / success / error Tag | 同上 |
| 订单 OPEN / FILLED / CANCELLED / LIQUIDATED | info / success / default / error Tag | 同上 |
| 风控 REJECT_OPEN / REDUCE_ONLY / SUSPEND_SYMBOL / GLOBAL_PAUSE | warning / warning / error / error Tag + 紧急徽标 | `<Tag>` + `<Badge dot>` |
| KYC PENDING / APPROVED / REJECTED | warning / success / error Tag | 同上 |

### 7.3 高风险操作的视觉警告

> **真源已迁移到** [`admin高危操作风控档位.md`](./admin高危操作风控档位.md)（2026-05-19）。下列条目仅保留入口说明，
> 操作清单 / 三重门规则 / 错误码兜底 / 使用规范 / 审计 RBAC 关联**全部以新文档为准**。

8 个高危操作（出金紧急取消 / 客户余额调整 / 强制撤单 / 强制删除告警 / 手动强平 /
全局自动强平开关 / 激活风控动作 / 停用风控动作）统一走
`HighRiskConfirmModal` 三重门：

1. **reason ≥ 10 字符**（必填，不接受空白）
2. **用户名挑战**：必须输入当前 admin 自己的 username（防 social engineering）
3. **确认勾选**：Checkbox「我已确认…」（防手滑）

新增「直接动钱 / 平台级风控开关」类操作前，必须先在 [`admin高危操作风控档位.md §2`](./admin高危操作风控档位.md)
登记 + 接入 `HighRiskConfirmModal`，禁止再造裸 `<Modal>`。

### 7.4 通用 loading / error / empty 表

每个数据展示组件必须显式处理以下状态（不接受默认渲染）：

| 状态 | AntD 组件 | 视觉 |
| --- | --- | --- |
| 初始 loading | `Skeleton` (Form / List) / `Table loading` | 灰条骨架 |
| 局部 loading（提交中） | 按钮 `loading={true}` | 旋转图标 |
| 接口失败 | `Result status="error"` + 重试按钮 | 红色叉 + 错误码 + 重试 |
| 数据为空 | `Empty image="default"` | 灰图 + 文案 |
| 无权限 | `Result status="403"` | 锁图 + 「无权访问」 |
| 未登录 / Token 过期 | 跳转 `/admin/login` | 不渲染当前页 |

---

## §8. 动效

管理端是长时间停留场景，动效**克制**。

| 动效 | 时长 | 缓动 | 用途 |
| --- | --- | --- | --- |
| 按钮点击反馈 | 90-140ms | ease-out | hover / active 颜色变化 |
| Modal / Drawer 进入退出 | 200ms | ease-in-out | AntD 默认 |
| Table 行展开折叠 | 180ms | ease-out | AntD 默认 |
| 切换菜单 / 路由 | 即时 | — | 不做过渡动画 |
| 数据更新 | 即时 | — | 不做高亮闪烁（与 C 端价格闪烁不同） |

**禁止**：

- ❌ 长时间循环动画（loading spinner 除外）
- ❌ 装饰性动画（漂浮、shimmer、视差）
- ❌ scroll-driven 动画
- ❌ 模态框关闭后的延迟回执动画

**Reduced motion**：遵守 `prefers-reduced-motion`，所有上面动效降级为透明度变化。

---

## §9. 字段呈现规范

管理端展示的数据必须遵守业务真源，**禁止**在 UI 层做字段拼接、单位换算、状态推断。

| 字段类型 | 规范 |
| --- | --- |
| 用户 ID | 14 位 Snowflake，等宽显示，可点击复制 |
| 邮箱 | 完整展示（不脱敏） |
| 金额 | tabular-nums + 2 位小数 + 货币符号；负数 `colorError`、零值 `rgba(0,0,0,0.45)` |
| 时间戳 | ISO 8601 + 用户时区（默认 Asia/Shanghai）；hover 显示 UTC |
| Symbol | 等宽 + 完整展示数据库中的 symbol（如 `BTCUSD`、`AAPL.NAS`、运营自定义后缀）；管理端不按 `.p / .c / .f` 或其他后缀做特殊限制 |
| Trace ID | 等宽 + monospace + 可点击复制 |
| 状态枚举 | 用 Tag + 中文名称，**禁止**直接显示英文枚举值（如 `OPEN`） |
| 错误码 | `<Tag color="red">90004</Tag>` + 中文消息；hover 显示完整 traceId |
| Token / Hash / 密钥 | **禁止**完整显示；前 4 + 后 4 字符 + 复制按钮（`abc1...wxyz`） |

---

## §10. 安全约束（与 [`安全规范`](../security/安全规范.md) 对齐）

- ✅ 管理员 Access Token 仅存于 `sessionStorage`（非 localStorage），关闭浏览器即销毁
- ✅ Refresh Token 单飞刷新，禁止并发刷新
- ✅ 浏览器**不**主动发送 `X-Trace-Id`
- ✅ Console 错误日志、错误详情、用户输入校验提示**禁止**包含完整 token、密码哈希、密钥
- ✅ 高风险操作必须二次确认 + 操作原因留痕
- ✅ 所有写操作必须等待 API 响应（`isSubmitting` 锁住按钮），不允许乐观更新
- ❌ 不缓存敏感字段到 IndexedDB / localStorage
- ❌ 不在错误 toast 中暴露后端 stack trace

---

## §11. 组件清单（基于 AntD 5）

阶段 1 使用的 AntD 5 组件：

| 类别 | 组件 |
| --- | --- |
| 布局 | `Layout`、`Layout.Sider`、`Layout.Header`、`Layout.Content` |
| 导航 | `Menu`、`Breadcrumb`、`Dropdown`、`Avatar` |
| 表单 | `Form`、`Form.Item`、`Input`、`Input.Password`、`Select`、`Radio`、`Checkbox`、`DatePicker`、`Switch` |
| 数据 | `Table`、`Tag`、`Badge`、`Descriptions`、`Empty`、`Result`、`Skeleton`、`Tree` |
| 反馈 | `Modal`、`Drawer`、`message`、`notification`、`Popconfirm`、`Spin` |
| 操作 | `Button`、`Space`、`Divider`、`Tabs`、`Pagination` |
| 图标 | `@ant-design/icons` |

**不允许引入**：

- ❌ `antd-pro-components` / `@ant-design/pro-components`（重量级、约定过强，本阶段不需要）
- ❌ 任何第三方表格库（`react-table`、`ag-grid` 等）
- ❌ 任何第三方表单库（`react-hook-form` 等）
- ❌ 任何 CSS-in-JS 库（AntD 5 已用 cssinjs）

---

## §12. 与后端契约的边界

R3 设计**禁止**做下列动作（违反即视为越界，返回 R1）：

- ❌ 自行决定字段命名、类型、枚举值
- ❌ 自行决定 API 路径、HTTP 方法
- ❌ 自行决定错误码
- ❌ 自行决定 RBAC 权限点（`{module}:{action}` 已由 [`管理端架构`](../architecture/管理端架构.md) §2.2 冻结）

R3 在每个页面方案中必须显式标注**数据依赖**章节，引用：

- [`管理端接口规范`](../api/管理端接口规范.md) §2 已冻结接口
- [`管理端架构`](../architecture/管理端架构.md) §2 RBAC 模型
- 字段定义（路径 + 字段名）必须与契约文档一字不差

后续阶段（阶段 2 业务管理端）的接口契约由 R2 在阶段启动时增量冻结，本文件不预先决定。

---

## §13. 决策日志

| 日期 | 决策 | 理由 |
| --- | --- | --- |
| 2026-05-08 | 选定 Ant Design 5 作为唯一 UI 框架 | 管理端架构 §5.1 已冻结；运营场景成熟、组件齐全、RBAC 配套友好 |
| 2026-05-08 | colorPrimary 选 `#0c5e7e`（C 端 cyan 暗化） | 与 C 端 cyan 品牌呼应；浅色背景对比度达标（WCAG AA），不刺眼 |
| 2026-05-08 | 浅色模式优先，深色作为后续阶段扩展 | AntD 5 浅色为默认，运营人员长时间阅读浅色更友好；与 C 端深色 terminal 形成职能区分 |
| 2026-05-08 | 字体使用系统栈，不引入 Inter | 管理端不强制 Inter；系统栈对简体中文支持更好；减少字体加载体积 |
| 2026-05-08 | 不引入 `@ant-design/pro-components` | 约定过强、定制成本高；本阶段使用 AntD 5 标准组件足够 |
| 2026-05-08 | sider 深色 + content 浅色 | 建立强烈视觉层级，符合企业级管理后台习惯 |

---

## §14. 关联文档

- [管理端架构](../architecture/管理端架构.md)（服务边界 + RBAC 模型 + 跨服务调用规则）
- [管理端接口规范](../api/管理端接口规范.md)（阶段 1 接口契约）
- [falconx-console-pages-V1](./falconx-console-pages-V1.md)（5 个核心页面方案）
- [客户端 DESIGN.md](../../DESIGN.md)（C 端品牌真源）
- [客户端 tokens.css](../../falconx-frontend/src/styles/tokens.css)（C 端 token 真源）
- [全栈 Figma 协作流程](../process/全栈Figma协作流程.md)
- [安全规范](../security/安全规范.md)
- [BBook 一期完成执行路径 §4 阶段 1](../process/BBook一期完成执行路径.md)
- [完成定义](../process/完成定义.md)
