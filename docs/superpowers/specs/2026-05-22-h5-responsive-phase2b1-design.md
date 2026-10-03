# H5 自适应 Phase 2B-1 设计 — trading 核心交易流 mobile

> Phase 2A 完成后（merge 025a106），用户已能 mobile 进入 market view 看图、watchlist、点 FAB 弹 Sheet 看到 OrderTicket。本 phase 把 OrderTicket 内部 + 平仓闭环（PositionsTable + 3 modal）也 mobile 化，**完成"下单→看持仓→平仓/加保证金/改 SL TP"完整 UX 闭环**。
>
> 前置：Phase 0+1（5afffb4）+ Phase 2A（025a106）
> 后置：Phase 2B-2（订单/成交历史 4 Table + OrderDetailModal）；Phase 2B-3（NotificationCenter + PriceAlerts）

## Goal

5 个 trading 核心组件 mobile 适配：

| 组件 | 当前桌面形态 | mobile 改造 |
|---|---|---|
| **OrderTicket** | 515 行复杂表单 grid 布局 | 一屏滚动 + input 48px + checkbox 24px + 56px CTA |
| **PositionsTable** | 13 列 `<table className="fx-table">` | mobile 切换为 5 行卡片 list（同信息密度，不裁剪字段） |
| **ClosePositionModal** | `.fx-modal` createPortal 居中 | mobile @media 改成 92vw + safe-area + 48px CTA |
| **AddMarginModal** | 同上 | 同上 + input mobile 16px font 防 iOS zoom |
| **EditRiskControlsModal** | 同上 + 2 个 SL/TP input | 同上 |

桌面 0 regression。所有 JSX 改动仅限于 PositionsTable 加 `isMobile` 分支渲染。其他 4 组件纯 CSS @media 改造。

## Architecture

```
┌─ Layer 3 (feature view)  ────────────────────────────────────────┐
│  PositionsTable mobile 卡片分支（useBreakpoint().isMobile）       │
│    < md: 卡片 list × N                                            │
│    ≥ md: 现有 13 列 fx-table（byte-identical）                    │
└────────────────────────────────────────────────────────────────────┘
┌─ Layer 2 (component-level mobile CSS)  ──────────────────────────┐
│  OrderTicket / 3 Modal 全部纯 CSS @media (max-width: 767px)       │
│  规则收敛到 styles/modules/_trading.css                            │
└────────────────────────────────────────────────────────────────────┘
┌─ Layer 1 (Phase 0+1 + 2A infra)  ────────────────────────────────┐
│  useBreakpoint / Sheet (OrderTicket 已在 Phase 2A 装入)           │
└────────────────────────────────────────────────────────────────────┘
```

**关键设计选择（用户拍板）：**
1. OrderTicket = **一屏滚动 + 触摸优化**（input 48px / font 16px / checkbox 24px / CTA 56px）
2. PositionsTable = **详尽卡片**（5 行 / ~140px 高，信息密度同桌面，trading terminal 调子）
3. 4 Modal = **fx-modal mobile @media 适配**（92vw + safe-area，不重写 JSX）

## File Changes

### Create

- `falconx-frontend/src/styles/modules/_trading.css` — OrderTicket / fx-modal / PositionsTable mobile @media rules
- `falconx-frontend/src/features/trading/PositionItemCard.tsx` — PositionsTable mobile 卡片单元组件
- `falconx-frontend/src/features/trading/PositionItemCard.test.tsx` — 渲染 + 字段 + 操作 callback 测试

### Modify

- `falconx-frontend/src/styles/global.css` — `@import "./modules/_trading.css";` 加在 `_market.css` 之后
- `falconx-frontend/src/features/trading/PositionsTable.tsx` — 加 `useBreakpoint`，`isMobile` 时渲染 `<PositionItemCard />` list 替代 `<table>`，桌面分支 byte-identical 保留
- `falconx-frontend/src/features/trading/OrderTicket.tsx` — **不动 JSX**；仅确认 className 命中 _trading.css 规则
- 3 个 Modal `.tsx`（ClosePosition / AddMargin / EditRiskControls）— **不动 JSX**；仅 _trading.css 改造

## Detailed Feature Changes

### 1. OrderTicket mobile（CSS-only）

桌面 grid 布局 → mobile 单列 + 加大 touch target。改造规则全部在 _trading.css `@media (max-width: 767px)` block 内：

```css
/* 顶部 panel head 紧凑化 */
.order-ticket .fx-panel-head { padding: 12px 16px; }

/* orderType 切换 — 等宽 segmented */
.ticket-order-type-tabs {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: 0;
  padding: 0 16px;
}
.ticket-order-type-tabs button {
  min-height: 40px;
  font-size: 13px;
}

/* amount input 容器 */
.ticket-amount input {
  min-height: 48px;
  font-size: 16px;
}

/* direction LONG/SHORT switch — 两半等宽 */
.ticket-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  padding: 0 16px;
}
.ticket-switch button {
  min-height: 44px;
  font-size: 14px;
}

/* leverage / triggerPrice / stopLimitPrice / TP / SL input 通用 */
.order-ticket input[type="text"],
.order-ticket input[type="number"] {
  min-height: 48px;
  font-size: 16px;
}

/* TP / SL checkbox 加大 */
.ticket-checkbox-row {
  gap: 12px;
  padding: 8px 16px;
}
.ticket-checkbox-row input[type="checkbox"] {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
}

/* submit button 加大 */
.order-submit {
  min-height: 56px;
  font-size: 15px;
}
```

不修改 OrderTicket.tsx JSX — 所有改动在 CSS。

### 2. PositionsTable mobile（JSX 条件渲染 + CSS）

#### 改造点

- import `useBreakpoint` from `../../lib/responsive`
- `const { isMobile } = useBreakpoint();`
- Loading / Empty state mobile 字号略大（在 _trading.css 解决）
- `<table>` 替换：mobile 时不渲染 `<table>` 而渲染 `<ul className="fx-positions-cards">` 中 `<PositionItemCard />` × N

```tsx
// 概念伪代码（非最终代码）
return (
  <div className="fx-tab-content">
    {/* loading / empty state 不变 */}
    {/* 顶部 positions-summary 不变 — 已有 CSS 处理 mobile */}
    {!isMobile ? (
      <table className="fx-table">{/* 现有 13 列结构完整保留 */}</table>
    ) : (
      <ul className="fx-positions-cards" aria-label="持仓列表">
        {items.map((p) => (
          <PositionItemCard
            key={p.positionId}
            position={p}
            pnl={pnlMap.get(p.positionId)}
            onClose={() => handleClose(p)}
            onAddMargin={() => handleAddMargin(p)}
            onEditRiskControls={() => handleEdit(p)}
          />
        ))}
      </ul>
    )}
  </div>
);
```

#### PositionItemCard 组件结构

```tsx
export interface PositionItemCardProps {
  position: PositionItem;
  pnl: PositionPnlUpdate | undefined;
  onClose: () => void;
  onAddMargin: () => void;
  onEditRiskControls: () => void;
}
```

渲染（5 行卡片）：

```
┌─────────────────────────────────────┐
│ BTCUSDT  多 0.01           50x      │  row1: symbol + side(long/short class) + qty + leverage
│ 67432.50 → 67450.00 (标记)          │  row2: open → mark price (mono tabular)
│ 未实现 +18.50  净盈 +12.30          │  row3: unrealized + net pnl (long/short class)
│ 保证金 5.50  强平 61580             │  row4: margin + liquidation price
│ TP 68500 · SL 66800  [⋯ Ⓞ]         │  row5: TP/SL + actions
└─────────────────────────────────────┘
```

操作按钮：
- `[⋯]` More menu → 触发 `onAddMargin` 或 `onEditRiskControls`（用 Phase 0+1 Sheet 或简单 dropdown）
- `[Ⓞ]` 平仓 cyan accent → 触发 `onClose`

⚠️ More menu 实现简化：用 `<details>` native HTML 元素或纯 button 直接呈现两个按钮（加保证金 / 改 SL TP），避免引入新 dropdown 组件依赖。

PositionsTable 桌面分支必须 byte-identical 保留（含 `<thead>` 13 个 `<th>` + `<tbody>` 完整 `<tr>` 渲染）。

### 3. ClosePositionModal / AddMarginModal / EditRiskControlsModal mobile（CSS-only）

3 个 modal 都用 `.fx-modal-backdrop > .fx-modal` 结构。统一 mobile CSS：

```css
@media (max-width: 767px) {
  .fx-modal-backdrop {
    padding: env(safe-area-inset-top) 12px env(safe-area-inset-bottom);
  }

  .fx-modal {
    width: 100%;
    max-width: 480px;
    max-height: calc(100vh - env(safe-area-inset-top) - env(safe-area-inset-bottom));
    max-height: calc(100dvh - env(safe-area-inset-top) - env(safe-area-inset-bottom));
    border-radius: var(--fx-radius-lg);
  }

  .fx-modal-header {
    padding: 12px 16px;
  }

  .fx-modal-header h3 {
    font-size: 16px;
  }

  .fx-modal-body {
    padding: 16px;
  }

  .fx-modal-dl {
    /* 桌面是 dt/dd 横排；mobile 可保持，但保证 word-break */
    word-break: break-word;
  }

  .fx-modal-field input,
  .fx-modal-field select {
    min-height: 48px;
    font-size: 16px;
  }

  .fx-modal-footer {
    padding: 12px 16px;
    gap: 8px;
  }

  .fx-modal-footer button {
    min-height: 48px;
    flex: 1;
  }
}
```

3 个 modal 0 JSX 改动。

OrderDetailModal 用 `.fx-modal-mask` + `.fx-modal--detail` 变体 — 不在本 Phase 2B-1 范围（Phase 2B-2 处理）。

## CSS Module 结构

新增 `_trading.css`，import 到 global.css：

```css
@import "./tokens.css";
@import "./modules/_base.css";
@import "./modules/_mobile-shell.css";
@import "./modules/_auth.css";
@import "./modules/_market.css";
@import "./modules/_trading.css";   /* new */
```

`_trading.css` 结构：

```css
/* FalconX H5 — trading 核心组件 mobile 适配
 * 桌面（≥ 768px）保持现有 OrderTicket / fx-modal / fx-positions 样式不变。
 */

@media (max-width: 767px) {
  /* ============ OrderTicket ============ */
  .order-ticket { ... }

  /* ============ fx-modal (3 modal 共享) ============ */
  .fx-modal-backdrop { ... }
  .fx-modal { ... }
  .fx-modal-header { ... }
  /* ... */

  /* ============ Positions cards (mobile) ============ */
  .fx-positions-cards { ... }
  .fx-position-card { ... }
  .fx-position-card__row1 { ... }
  /* ... */
}
```

## Visual Design Notes（frontend-design lens）

继续 Phase 2A 已经确立的 **Brutalist Trading Terminal** 调子。Phase 2B-1 设计准则：

### Token / 字体规则（同 Phase 2A）

- 所有数字 `font-family: var(--fx-mono-stack)` + `font-variant-numeric: tabular-nums`
- 涨用 `var(--fx-long)`（lime），跌用 `var(--fx-short)`（red）
- accent 用 `var(--fx-cyan)`
- spacing 用 `var(--fx-space-*)`
- 涉及 input mobile 必须 `min-height: 48px` + `font-size: 16px`（iOS zoom 防御）

### OrderTicket 视觉细节

- orderType 切换按钮 mobile `min-height: 40px`，等宽 grid 1fr
- direction LONG/SHORT switch — mobile 两半等宽，44px 高，active 时:
  - `.long.active`: `border: 1px solid var(--fx-long)`, `color: var(--fx-long)`
  - `.short.active`: `border: 1px solid var(--fx-short)`, `color: var(--fx-short)`
- submit CTA 56px 高（比一般按钮高），下单是关键操作
- amount input 高 48px，font-size 16px（不能改）
- TP / SL checkbox 加大到 24×24px，不引入新 `<input type="range">` slider — 用户拍板用 native number input（plan 时如发现实际有 range 再调整）

### PositionsTable Card 视觉细节

按 Phase 2A Visual Notes 红线：
- 卡片 padding 12px 16px，min-height 140px
- 卡片间隔 1px hairline border-bottom（不加 padding 间距 — 密度优先）
- selected 时左侧 3px cyan 竖条（`::before` pseudo）
- row1 symbol 用 `font-size: 15px` letter-spacing 0.02em；leverage eyebrow `font-size: 10px` letter-spacing 0.18em uppercase
- row2-4 数字必须 mono tabular
- TP/SL 字号 11px，subtle 色
- `[⋯]` More 按钮 32×32 ghost button；`[Ⓞ]` 平仓 button cyan border + cyan label（不是纯红色危险按钮 — close 在 modal 里二次确认）

### Modal mobile atmosphere

- backdrop `rgba(0,0,0,0.6)` + `backdrop-filter: blur(4px)`（继承现有）
- 顶部 close 按钮 32×32，touch friendly
- footer 主操作（确认/平仓）48px 高，cyan border 或 short 红 border 视语义
- error 文字 `var(--fx-short)` 14px

## Testing

| Test | 覆盖 |
|---|---|
| `PositionItemCard.test.tsx` | 渲染 5 行字段 + 3 callback (onClose / onAddMargin / onEditRiskControls) + pnl 涨跌颜色 class + selected state | ~6 测试 |
| 视觉验证（DevTools mobile preview）| OrderTicket / PositionsTable 卡片 / 3 modal 全部 mobile 表现 | 手动 |

无新增对 PositionsTable.tsx 本身的 unit test（现有逻辑不变，仅加 isMobile 分支条件渲染；分支由 PositionItemCard 单元测试间接覆盖）。

不为 OrderTicket / 3 modal 添加新测试（本 phase 不改它们 JSX 或逻辑）。

## Verification 检查清单

- [ ] DevTools iPhone SE — 点 OrderTicketFab，OrderTicket Sheet 弹出，表单滚动流畅
- [ ] OrderTicket input focus 不触发 iOS auto-zoom
- [ ] OrderTicket LONG / SHORT 切换 active 颜色正确
- [ ] OrderTicket submit 56px 高，loading 状态正常
- [ ] PositionsTable mobile 切换 — 显示卡片 list，13 字段全部可见
- [ ] PositionsTable mobile 卡片 selected 左侧 3px cyan
- [ ] 点卡片 `[Ⓞ]` → ClosePositionModal mobile 92vw 居中弹出
- [ ] 点卡片 `[⋯]` 展开两个操作（加保证金 / 改 SL TP）
- [ ] AddMarginModal mobile input focus 不触发 zoom
- [ ] EditRiskControlsModal mobile TP/SL input 48px 高
- [ ] DevTools 1440 桌面 — OrderTicket grid 布局 / PositionsTable 13 列 table / 3 modal 居中弹窗 全部 byte-identical 0 regression
- [ ] `npm run test` 全过（新增 PositionItemCard 6 测试 + 现有 91/1）
- [ ] `npm run build` 0 error，bundle 增量 < 6KB gzip
- [ ] `npm run lint` H5 新增 0 regression

## Risk & 已知约束

| 风险 | 缓解 |
|---|---|
| PositionsTable 桌面分支 JSX 漂移导致 regression | spec 强调 byte-identical；diff 验证 |
| PositionItemCard `[⋯]` More menu UX 复杂（需要展开/折叠交互） | 简化：用 `<details>` native 元素或 inline 两个按钮直接显示，不引入新组件 |
| OrderTicket `.ticket-switch` direction 切换涉及 `.long` / `.short` 状态色 | _trading.css mobile 规则严格用 `var(--fx-long)` / `var(--fx-short)` |
| 3 modal 共享 `.fx-modal-*` className，CSS 一处改动影响 3 modal — 期望行为 | spec coverage 3 modal 一起视觉验证 |
| OrderTicket 表单很长（515 行 JSX），mobile 一屏滚动用户可能找不到 submit | submit 默认 sticky 底部？YAGNI — 先看实际体验 |
| `.fx-positions-summary` mobile 已有桌面样式 | _trading.css 不重复处理 summary，仅 cards container 单独 |

## Out-of-Scope（Phase 2B-2 / 2B-3）

明确不做：
- PendingOrdersTable / OrdersTable / TradesTable / ClosedPositionsTable mobile（8.1KB / 6.4KB / 5.3KB / 8.0KB）→ Phase 2B-2
- OrderDetailModal mobile（`.fx-modal--detail` 变体）→ Phase 2B-2
- TablePaginationFooter mobile → Phase 2B-2
- NotificationCenter / PriceAlertsTable mobile → Phase 2B-3
- TradingTabs mobile 微调 → Phase 2B-3
- Phase 3 客户端 P1 features / Phase 4 管理端 / Phase 5 真机 QA
