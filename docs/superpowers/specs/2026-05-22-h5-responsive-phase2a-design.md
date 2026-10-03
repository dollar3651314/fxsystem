# H5 自适应 Phase 2A 设计

> 4 features × mobile 适配 / 桌面 0 regression
> 前置：Phase 0+1（merge 5afffb4）— 提供 useBreakpoint / MobileShell / BottomNav / Drawer / Sheet / 4 档断点 / safe-area token
> 后置 Phase 2B：trading（OrderTicket / Table / Modal）+ wallet — 独立 spec

## Goal

把客户端 4 个 P0 feature 的内部布局从桌面专属改成响应式：
- **terminal**：market 视图（watchlist + chart + tabs/orderticket 3 列）改为 mobile 垂直 stack；TerminalTopbar mobile 形态；Drawer inline 按钮抽出成命名组件
- **auth**：AuthGate brand splash + AuthForm 长表单 mobile 全屏 + 触摸友好 input
- **market/MarketWatchlist**：紧凑 row（Bid/Ask 同行）改 mobile 卡片（symbol + 价 + 涨跌幅 一行 80px）
- **market/MarketChart**：timeframe row touch target 32px+，canvas 适配 40vh，lightweight-charts 库 mobile crosshair/swipe

不在范围（Phase 2B/2C）：trading（OrderTicket 内部表单 mobile 排版、PositionsTable / OrdersTable / 4 Modal mobile）、wallet（充值/提现 multi-tab mobile）。

## Architecture

3 层叠加，每层独立可测：

```
┌─ Layer 3 (feature view)        ───────────────────────────────────────┐
│  TradingTerminal "market" 视图 mobile 重排：                          │
│    < md:  <Watchlist /> + <MarketChart /> + <TradingTabs />            │
│           + <OrderTicketSheetButton /> (FAB)                          │
│    ≥ md:  3 列保持不动                                                 │
└────────────────────────────────────────────────────────────────────────┘
┌─ Layer 2 (component-level mobile CSS)  ───────────────────────────────┐
│  AuthForm / MarketWatchlist / MarketChart 各自 @media (max-width: …)  │
│  规则收敛到 styles/modules/_auth.css 和 _market.css                    │
└────────────────────────────────────────────────────────────────────────┘
┌─ Layer 1 (existing Phase 0+1 infra)  ─────────────────────────────────┐
│  useBreakpoint() / MobileShell / Sheet / token (sm/md/lg/xl)          │
└────────────────────────────────────────────────────────────────────────┘
```

**关键设计选择（用户拍板）：**
1. Market 视图 = **Stack + OrderTicket Sheet**（垂直滚动 watchlist→chart→tabs；FAB 弹 Sheet 下单）
2. MarketChart = **保留核心 + 触摸优化**（不砍功能、不加全屏切换，依赖 lightweight-charts 内置 touch）
3. AuthForm = **全屏 + 单页滚动**（不改 idle/login/register 3 态，纯 CSS responsive）

## File Changes

### Create

- `falconx-frontend/src/styles/modules/_auth.css` — auth-gate / auth-form mobile rules
- `falconx-frontend/src/styles/modules/_market.css` — market-list / market-chart mobile rules + market view stack layout
- `falconx-frontend/src/components/mobile/OrderTicketFab.tsx` — "下单" FAB button，点击打开 Sheet
- `falconx-frontend/src/components/mobile/OrderTicketFab.test.tsx`
- `falconx-frontend/src/features/terminal/TerminalDrawer.tsx` — TradingTerminal inline drawer 抽出
- `falconx-frontend/src/features/terminal/TerminalDrawer.test.tsx`

### Modify

- `falconx-frontend/src/styles/global.css` — @import 新增 `_auth.css` 和 `_market.css`
- `falconx-frontend/src/styles/modules/_mobile-shell.css` — 已有 `.terminal-topbar { display: none }` 保留，新增 market-stack layout rules
- `falconx-frontend/src/features/terminal/TradingTerminal.tsx` — market 视图条件渲染（mobile stack vs desktop 3 列）+ OrderTicketFab + TerminalDrawer 替换 inline
- `falconx-frontend/src/features/auth/AuthGate.tsx` — 不改 JSX 结构；仅在 mobile 下确认 `.language-select` 不遮挡 brand（CSS 在 _auth.css 解决）
- `falconx-frontend/src/features/auth/AuthForm.tsx` — 不改 JSX 结构；样式全部走 _auth.css mobile rules
- `falconx-frontend/src/features/market/MarketWatchlist.tsx` — `.market-row` 加 `data-compact="mobile"` 或保留现 className，在 _market.css 用 @media 改布局
- `falconx-frontend/src/features/market/MarketChart.tsx` — timeframe row 加大 touch target；canvas height var；不动业务逻辑

## Detailed Feature Changes

### 1. terminal/TradingTerminal — market 视图 stack

桌面 (≥ md) 当前结构（line 444-490）：
```tsx
<MarketWatchlist />      {/* 列 1, ~280px */}
<div className="terminal-center">
  <MarketChart />        {/* 列 2 中，剩余宽度 */}
  <TradingTabs />        {/* 列 2 下 */}
</div>
<OrderTicket />          {/* 列 3, ~320px */}
```

Mobile (< md) 改为：
```tsx
<MarketWatchlist />      {/* 顶 80px row × N 滚动 */}
<MarketChart />          {/* 中 40vh */}
<TradingTabs />          {/* 下 剩余 */}
<OrderTicketFab />       {/* 浮在底部 BottomNav 上方 16px */}
<Sheet open={ticketOpen}>
  <OrderTicket />        {/* Sheet 内 OrderTicket 原样使用 */}
</Sheet>
```

数据流不变：`selectedSymbolMeta` / `quote` / `pnlMap` 透传，OrderTicket 内部不需要改。

实现要点：
- `const { isMobile } = useBreakpoint()` 已经在 TradingTerminal 中（line 74）
- `const [ticketOpen, setTicketOpen] = useState(false)` 新增
- `currentView === 'market'` 分支内：用 `isMobile` 切换两套 JSX
- 桌面 JSX 保持原状（line 444-490 不动）

### 2. terminal/TerminalTopbar mobile（已完成，无需新改动）

Phase 1 commit 21959a2 已经在 `_mobile-shell.css` 末尾添加 `.terminal-topbar { display: none }` for mobile，MobileShell 渲染自己的 header（hamburger + title）。

本 Phase 2A 不改 TerminalTopbar.tsx — 跳过。

### 3. terminal/TerminalDrawer 抽取

当前 TradingTerminal.tsx line 370-418 把 drawer 内容 inline 写在 `drawerContent={<nav>...</nav>}` prop 里，~50 行 inline style。

抽成命名组件 `<TerminalDrawer onOpenProfile onOpenKyc onOpenWithdraw />`：
- 单一职责
- 可独立单元测试
- 减少 TradingTerminal.tsx 行数（~22KB 已偏大）

### 4. auth/AuthGate + AuthForm mobile

当前 auth-gate 桌面布局：`<FalconBackdrop>` 全屏背景 + `<motion.section>` 居中卡片含 brand + buttons / form。

mobile 时（< md）：
- `.auth-gate` `padding: env(safe-area-inset-top) 20px env(safe-area-inset-bottom)`
- `.auth-gate__brand` `width: 100%; max-width: 480px; margin: auto`
- `.auth-form` `width: 100%`
- `.auth-form input, select, button.auth-form__submit` `min-height: 48px; font-size: 16px;` （iOS 防自动 zoom）
- `.auth-form__name-row` mobile 改为 1 列（不是 last + first 并排）
- `.language-select` 浮在顶部，mobile 时也 fixed top-right

桌面体验保持原样（CSS 只加 mobile @media）。

### 5. market/MarketWatchlist mobile

当前桌面 `.market-row` 是单按钮 flex 行：左 symbol 名 + 右 Bid/Ask 块（4 个小数字 + LIVE + 时间）。

mobile 改为简化卡片：
```
┌─────────────────────────────────────┐
│ BTCUSDT                +1.23%      │ <- 16px name + 12px pct（涨红跌绿）
│ 67,432.50 Bid · 67,433.10 Ask LIVE │ <- 13px 紧凑
└─────────────────────────────────────┘  height: 64px, padding: 12px
```

CSS-only 改动 — `.market-row` mobile 重新排版 grid-template-areas 或 flex-direction column。

Search input 和 tabs row 保持现状（顶部 sticky）。

### 6. market/MarketChart mobile

lightweight-charts 库原生支持 touch crosshair + swipe，所以 mobile 不需要改 canvas 逻辑，只改：

- `.timeframe-row button` mobile `min-height: 32px; min-width: 44px; padding: 8px 12px`（当前桌面 26px）
- `.market-chart` 容器 mobile `height: 40vh; max-height: 400px; min-height: 280px`
- canvas resize listener 已存在 — 不动
- price ladder / hover 状态指示器 mobile 简化（symbol + 价格 + 涨跌幅，去掉 Bid/Ask/timestamp 详情，那些已经在 watchlist 显示）

## CSS Module 结构

新增 2 个 module：

**`_auth.css`** ~80 行：
```css
/* desktop default 保留现有 styles 部分；mobile 重排 */
@media (max-width: 768px) {
  .auth-gate { padding: ...; }
  .auth-gate__brand { ... }
  .auth-form { width: 100%; }
  .auth-form input, .auth-form select { min-height: 48px; font-size: 16px; }
  .auth-form__name-row { grid-template-columns: 1fr; }
  .auth-form__submit { min-height: 48px; }
}
```

**`_market.css`** ~150 行：
```css
@media (max-width: 768px) {
  /* market view stack layout */
  .terminal-workspace--market { ... }

  /* watchlist mobile compact */
  .market-list__rows { ... }
  .market-row { flex-direction: column; padding: 12px; min-height: 64px; }

  /* chart mobile */
  .market-chart { height: 40vh; }
  .timeframe-row button { min-height: 32px; min-width: 44px; }

  /* OrderTicketFab */
  .order-ticket-fab { ... }
}
```

import 顺序（`global.css` 顶部）：
```css
@import "./tokens.css";
@import "./modules/_base.css";
@import "./modules/_mobile-shell.css";
@import "./modules/_auth.css";       /* new */
@import "./modules/_market.css";     /* new */
```

## Testing

每个新增组件配单元测试。已有组件不增加测试覆盖（Phase 2A 不改业务逻辑，仅样式 + 条件渲染）。

| Test | 覆盖 |
|---|---|
| `TerminalDrawer.test.tsx` | 渲染 3 个按钮 / 各自 onClick 调用 callback / aria 标签 |
| `OrderTicketFab.test.tsx` | 渲染 / onClick 调用 / disabled state（没选 symbol） |
| `_auth.css` 视觉验证 | Chrome DevTools mobile preview |
| `_market.css` 视觉验证 | Chrome DevTools mobile preview |
| `TradingTerminal market mobile branch` | render in mobile breakpoint，断言 watchlist + chart + tabs 顺序 |

CSS 改动 + lightweight-charts touch 行为：用 DevTools 手机模拟验证，不写自动化 visual diff（YAGNI for Phase 2A）。

## Verification 检查清单

实施完成时必须全过：

- [ ] Chrome DevTools iPhone SE (375x667) 切换 — market 视图 stack 显示正确，FAB 浮在 BottomNav 上方
- [ ] DevTools 1440 桌面切换 — market 3 列布局完全保留，0 改动
- [ ] iOS Safari 真机或 Responsive Design Mode — input focus 不触发 zoom
- [ ] auth login / register 全屏布局，长表单可滚动
- [ ] watchlist mobile 紧凑卡片 + 滚动流畅
- [ ] chart timeframe row touch target ≥ 32px，tap 切换流畅
- [ ] `npm run test` 全过（含 2 新增 test 文件）
- [ ] `npm run build` 0 error
- [ ] `npm run lint` H5 新增 0 regression

## Risk & 已知约束

| 风险 | 缓解 |
|---|---|
| AuthGate `FalconBackdrop` 全屏背景 mobile 性能 | 已是 Phase 0+1 范围外，保持现状；如真机卡顿才看 |
| lightweight-charts mobile touch 在某些 Android Chrome 表现 | 依赖库自身 — 测试发现问题独立 Issue 跟进 |
| OrderTicket 在 Sheet 内 — keyboard 弹起遮挡输入框 | OrderTicket 内已用 native input + scrollIntoView；Sheet `max-height: 85vh` 留出键盘空间 |
| TradingTerminal 22KB 文件继续膨胀 | 抽 TerminalDrawer 后 ~17KB；market 分支 mobile/desktop 用三元有限增大 ~30 行 |
| MarketChart 37KB 不动 | mobile 适配是 CSS-only，不增加 JS bundle |

## Visual Design Notes（frontend-design lens）

### Aesthetic Statement

FalconX 的视觉方向是 **Brutalist Trading Terminal** — 极深背景 (`#050608`) + 锐利 hairline + JetBrains Mono / tabular-nums + cyan/lime/short 三色高对比 accent + 18% letter-spaced eyebrow caps。Bloomberg/IBKR/CFD 专业终端调子，不是 Robinhood 那种圆润扁平 retail app。

**Phase 2A mobile 适配的设计纪律**：
- 不引入新 design 语言、不加圆角 modal、不加 shadow soft 效果
- 字号、间距、border 严格沿用 token system
- 微交互保持克制（FalconX 已有 `.market-list__conn-dot` 脉冲、`framer-motion` AuthForm 切换；mobile 不加新动画）
- 触摸态用 cyan accent 表达 — 不是 material ripple

### Typography Scale on Mobile

桌面 base font-size: 13-14px / mono 12-13px。Mobile 抬高基线：

| 用途 | desktop | mobile (< md) | font |
|---|---|---|---|
| Body 正文 | 13px | 15px | Inter |
| 数字（价 / pnl） | 13px tabular | 16px tabular | JetBrains Mono |
| Eyebrow caps（§01 / LIVE） | 10px / 0.18em | 11px / 0.18em | Inter |
| H2 视图标题 | 16px | 18px | Inter |
| Input 内容 | 14px | 16px | Inter（防 iOS auto-zoom） |
| Button label | 13px | 14px | Inter |
| BottomNav label | 10px | 10px | Inter（不动）|

mobile 不放大 BottomNav label — 5 个 tab 拥挤；icon 是主要锚点。

### Mobile Micro-Interactions

| 元素 | 触摸态 | 实现 |
|---|---|---|
| `.market-row` mobile | tap 时 0.96 scale + cyan hairline 闪 80ms | `:active` pseudo + CSS transition |
| `.timeframe-row button` | tap 时 cyan bg fade 120ms | `:active { background: rgba(84,230,255,0.12) }` |
| `OrderTicketFab` | tap 时 inner cyan glow pulse | `:active { box-shadow: inset 0 0 0 2px var(--fx-cyan) }` |
| `BottomNav item` | 切换时 cyan 顶部 2px hairline 滑入 | 已在 _mobile-shell.css 用 active class，加 ::before pseudo |
| `.auth-form__submit` | tap loading 时 cyan border flash 380ms | 已有 `isSubmitting` 状态，加 keyframes |

不加：bounce、wobble、emoji 动画、material ripple — 与 brutalist trading 调子冲突。

### OrderTicketFab 视觉规格

**不是普通 round FAB**。沿用 FalconX terminal 调子：

```
mobile (在 BottomNav 上方 16px，居中):
  ┌──────────────────────────────────┐
  │ ▾ BTCUSDT · 67,432.50  LIVE     │  <- height: 56px
  │            下单 →                 │  <- mono small label
  └──────────────────────────────────┘
      ↑ width: calc(100vw - 32px), max 480px
```

CSS：
- bg: `var(--fx-surface-2)` 微透明
- border: `1px solid var(--fx-border-strong)`（cyan 22% 半透）
- radius: `var(--fx-radius-md)` (8px)
- 内部左半 symbol/price（mono tabular），右半 cyan eyebrow "下单 →"
- disabled state（没选 symbol）：opacity 0.4，cursor not-allowed
- 这种"信息条 + CTA 一体"比单纯 FAB 圆按钮更有 trading terminal 气质

### MarketWatchlist Mobile Card

桌面 row 是"挤"的（symbol + 4 个 Bid/Ask 数字一行）。Mobile 改为 2 行卡片，**保留 trading terminal 紧凑感**，不变成 retail app 的"圆角大卡"：

```
  ┌──────────────────────────────────────┐
  │ BTCUSDT  XAU/USD · 100x      +1.23% │  <- row1: name eyebrow + chg
  │ 67,432.50  ↗  Bid 67432  Ask 67433  │  <- row2: mono nums tabular
  └──────────────────────────────────────┘
   ↑ hairline divider, padding 12px 16px, height 64-72px
   ↑ selected 时左侧 3px cyan 竖条 (::before)
```

色彩规则：
- `+1.23%` 用 `var(--fx-long)` (lime)、`-0.x%` 用 `var(--fx-short)` (red)
- LIVE 用 `var(--fx-cyan)` 小 dot 脉冲
- 卡间分隔仅 `border-bottom: 1px solid var(--fx-hairline)`，不加 padding 间隔（密度优先）

### MarketChart Mobile Atmosphere

- timeframe row 用 segmented control 风格，**所有按钮等宽**，不要等内容宽度 — 终端 grid 美学
- 选中态：bg `var(--fx-surface-3)` + text `var(--fx-cyan)` + 底部 1px cyan 实线
- 未选中：text `var(--fx-muted)`
- canvas wrapper border-top 用 `var(--fx-border-strong)` (cyan 22%) — 让 chart 与上下分区有强分隔
- lightweight-charts 的 grid line 颜色已经在 desktop 配过 — mobile 不动

### AuthGate Mobile Atmosphere

**FalconBackdrop 是品牌 splash 的灵魂**，mobile 不能去掉 — 但要降到背景层不抢主体：

- mobile (< md) `.falcon-backdrop` 加 `opacity: 0.4 + filter: blur(2px)`（桌面 1.0）
- `.auth-gate__brand` mobile 时 `padding-top: 12vh`（避免顶到刘海屏）
- `<FalconMark>` + `<FalconWordmark>` mobile 时缩到 `transform: scale(0.85)`
- 按钮 `.auth-button.primary` mobile 48px 高 + cyan border + mono "登录" 文字 + tap 时 cyan inner glow
- `.auth-form__name-row` mobile 改 1 列（last + first 拆 2 行）— spec 已写
- `.language-select` mobile 缩小到顶部右上角 32px，且只在 `mode === 'idle'` 显示（避免遮 form）

### Empty / Loading / Error State on Mobile

每个新增 mobile UI 都要有 3 态：

| 元素 | empty | loading | error |
|---|---|---|---|
| MarketWatchlist | 现有 `.panel-empty` 文案 mobile font 加大到 14px | 现有 `<Loader2>` icon mobile size 20 | 现有 `.market-list__error` mobile 重排为 vertical stack |
| MarketChart | "未选择品种" + cyan eyebrow，居中 mobile | `<Loader2>` 20px 居中 + mono "K 线加载中..." | 现有 desktop error 复用 |
| AuthForm | n/a | 现有 `isSubmitting` 灰按钮 + cyan border pulse | 现有 `.auth-form__error` 红字 |
| OrderTicketFab | "选择品种下单" disabled | n/a | n/a |

### 设计 hold-the-line 清单（implementer 红线）

实施时**禁止做**：

- 引入新字体（必须 JetBrains Mono / Inter / system mono）
- 大圆角（`> var(--fx-radius-xl)` = 18px 是上限）
- Soft shadow / glow blur 大于 12px
- Material Design ripple 动画
- Emoji 作 icon（必须 lucide-react）
- 引入新色 token（必须用现有 fx-* token）
- Mobile 自己一套 button 样式（必须复用 .fx-ghost-btn / .auth-button 类）
- Mobile 把表头 / eyebrow 字大小翻倍（终端调子要密度）

实施时**必须做**：

- 数字 `font-family: var(--fx-mono-stack)` + `font-variant-numeric: tabular-nums`
- 涨用 `var(--fx-long)`，跌用 `var(--fx-short)`，引用 cyan 用 `var(--fx-cyan)`
- Border 用 `var(--fx-hairline)` 或 `var(--fx-border-strong)`，不用 px solid #xxx 字面值
- Spacing 用 `var(--fx-space-*)`
- Touch target ≥ 32px（小元素 padding 加大）/ ≥ 44px（主操作）

## Out-of-Scope（Phase 2B/2C）

明确不做：
- OrderTicket 内部表单 mobile 重排（amount/leverage/SL/TP slider 触摸优化） → Phase 2B
- PositionsTable / OrdersTable / TradesTable mobile 转卡片 → Phase 2B
- 4 个 Modal（AddMargin / ClosePosition / EditRiskControls / OrderDetail）mobile 形态 → Phase 2B
- WalletPage（充值/提现/转账 multi-tab）mobile → Phase 2C
- Activity / Dashboard / Settings 已经在 TerminalView 但 mobile 适配 → Phase 3
- iOS Safari / Android Chrome 多尺寸真机 QA → Phase 5
