# H5 自适应 Phase 2B-2 设计 — trading 订单/成交历史 mobile

> 前置：Phase 0+1（5afffb4）+ 2A（025a106）+ 2B-1（1cdcba1）
> 后置 Phase 2B-3：NotificationCenter + PriceAlertsTable + TradingTabs 微调
> 后置 Phase 2C：wallet（充值/提现/转账 multi-tab）

## Goal

6 个 trading 历史相关组件 mobile 适配：

| 组件 | 当前桌面形态 | mobile 改造 |
|---|---|---|
| **PendingOrdersTable** | 13 列 `<table className="fx-table">` | `isMobile` 分支 → `PendingOrderCard` × N |
| **OrdersTable** | 13 列 `<table className="fx-table">` | `isMobile` 分支 → `OrderCard` × N |
| **TradesTable** | 10 列 `<table className="fx-table">` | `isMobile` 分支 → `TradeCard` × N |
| **ClosedPositionsTable** | 12 列 `<table className="fx-table">` | `isMobile` 分支 → `ClosedPositionCard` × N |
| **OrderDetailModal** | `.fx-modal-mask--detail` + `.fx-modal--detail` inspect view | CSS-only mobile rules（全宽 + sections 1 列 + 48px close）|
| **TablePaginationFooter** | inline `style` object 按钮 fx-btn-xs | 改 className + _trading.css 加 mobile 加大 button |

桌面 0 regression。JSX 改动仅限于：
- 4 个 Table 加 `useBreakpoint` + 条件渲染分支（桌面 byte-identical）
- TablePaginationFooter inline style 改 className 包装

OrderDetailModal 0 JSX 改动（CSS-only）。

## Architecture

```
┌─ Layer 3 (Table feature views)  ────────────────────────────────────┐
│  4 Table .tsx 加 isMobile 条件渲染:                                  │
│    < md: <ul.fx-history-cards> + <XxxCard /> × N                     │
│    ≥ md: existing 10-13 列 fx-table（byte-identical）                │
└────────────────────────────────────────────────────────────────────────┘
┌─ Layer 2 (4 mobile card components + CSS)  ─────────────────────────┐
│  PendingOrderCard / OrderCard / TradeCard / ClosedPositionCard       │
│  共享 .fx-history-card / .fx-history-card__row1 / __row2 / __actions  │
│  CSS 在 _trading.css 同一 @media block 追加                          │
└────────────────────────────────────────────────────────────────────────┘
┌─ Layer 1 (Phase 0+1 + 2A + 2B-1 infra)  ────────────────────────────┐
│  useBreakpoint / OrderDetailModal / TablePaginationFooter            │
└────────────────────────────────────────────────────────────────────────┘
```

**关键设计选择（用户拍板）：**
1. 4 Table → **4 个独立 Card 组件**（字段差异大不抽公共 generic）
2. OrderDetailModal → **_trading.css 补 fx-modal-mask--detail + fx-modal--detail mobile 规则**（0 JSX 改动）

**核心模式延续 Phase 2B-1**：每个 Card 是 `<li>` 元素，外壳 className `.fx-history-card`（4 card 共享），内部 rows 用 `__row1 / __row2 / __actions` 等通用 BEM。各 Card 不同的字段名用 `__qty` / `__price` / `__pnl` 等额外 class。

## File Changes

### Create

- `falconx-frontend/src/features/trading/PendingOrderCard.tsx` + `.test.tsx`
- `falconx-frontend/src/features/trading/OrderCard.tsx` + `.test.tsx`
- `falconx-frontend/src/features/trading/TradeCard.tsx` + `.test.tsx`
- `falconx-frontend/src/features/trading/ClosedPositionCard.tsx` + `.test.tsx`

（8 个新文件 = 4 组件 + 4 测试文件）

### Modify

- `falconx-frontend/src/styles/modules/_trading.css` — 在现有 @media block 末尾追加：
  - `.fx-history-card` / `.fx-history-card__*` 通用卡片外壳（~80 行）
  - `.fx-modal-mask--detail` + `.fx-modal--detail` + `.fx-detail-*` mobile 规则（~50 行）
  - `.fx-pagination-footer` mobile 加大 button（~20 行）
- `falconx-frontend/src/features/trading/PendingOrdersTable.tsx` — useBreakpoint + 条件渲染
- `falconx-frontend/src/features/trading/OrdersTable.tsx` — 同上
- `falconx-frontend/src/features/trading/TradesTable.tsx` — 同上
- `falconx-frontend/src/features/trading/ClosedPositionsTable.tsx` — 同上
- `falconx-frontend/src/features/trading/TablePaginationFooter.tsx` — inline `style` 改 className（不动 props/逻辑）

## Detailed Feature Changes

### 1. 4 个 Card 组件设计

所有 4 个 Card 共用结构：

```tsx
<li className="fx-history-card" onClick={() => onDetail(item)}>
  <div className="fx-history-card__row1">
    {/* symbol + 方向 + 状态/类型 */}
  </div>
  <div className="fx-history-card__row2">
    {/* 数字字段（mono tabular）*/}
  </div>
  <div className="fx-history-card__row3">
    {/* 次要字段 */}
  </div>
  <div className="fx-history-card__footer" onClick={(e) => e.stopPropagation()}>
    <span className="fx-history-card__time">{item.createdAt}</span>
    <div className="fx-history-card__actions">
      {/* 详情 / 取消 / etc. */}
    </div>
  </div>
</li>
```

#### PendingOrderCard

字段（来自 PendingOrderItem）：
- row1: `symbol` + `side`(多/空) + `orderType` + `status`
- row2: `quantity` + `triggerPrice` + `limitPrice` + `leverage`x
- row3: 冻结保证金 `frozenMargin` + 冻结手续费 `frozenFee`
- footer: `createdAt` + 操作（详情 / 取消订单）

callback: `onDetail(item)` + `onCancel(item)`

#### OrderCard

字段（来自 OrderItem）：
- row1: `symbol` + `side` + `orderType` + `status`
- row2: `quantity` × `avgPrice` + `leverage`x
- row3: `margin` + `fee` + 原因（rejectReason / 状态 enum 文案）
- footer: `createdAt` + 操作（详情）

callback: `onDetail(item)`

#### TradeCard

字段（来自 TradeItem）：
- row1: `symbol` + `side` + `orderType`
- row2: `quantity` × `price` + 手续费 `fee`
- row3: 已实现盈亏 `realizedPnl`（fx-long/short class）
- footer: `executedAt` + 操作（详情）

callback: `onDetail(item)`

#### ClosedPositionCard

字段（来自 PositionItem with status=CLOSED）：
- row1: `symbol` + `side`(开多/开空) + `quantity` + `leverage`x
- row2: 开仓 `entryPrice` → 平仓 `closePrice`
- row3: 已实现盈亏 `realizedPnl`（fx-long/short class）+ `closeReason`
- footer: `openedAt` ~ `closedAt` + 操作（详情）

callback: `onDetail(item)`

### 2. 4 个 Table 集成

模式跟 Phase 2B-1 PositionsTable 完全一致：

```tsx
const { isMobile } = useBreakpoint();
// ... existing state / hooks ...

return (
  <div className="fx-tab-content">
    {/* loading / empty 不变 */}

    {items.length > 0 && !isMobile && (
      <table className="fx-table">
        {/* 现有 thead/tbody — byte-identical */}
      </table>
    )}

    {items.length > 0 && isMobile && (
      <ul className="fx-history-cards" aria-label={...}>
        {items.map((item) => (
          <XxxCard
            key={item.id}
            item={item}
            onDetail={setDetailTarget}
            {/* + 各 Table 特有 callback（如 onCancel for pending）*/}
          />
        ))}
      </ul>
    )}

    {/* TablePaginationFooter 保留 */}
    {/* Modals 保留 */}
  </div>
);
```

### 3. OrderDetailModal mobile（CSS-only）

在 _trading.css 追加规则（与 Phase 2B-1 的 `.fx-modal-backdrop` 不同套）：

```css
@media (max-width: 767px) {
  /* ============ OrderDetailModal (fx-modal-mask + fx-modal--detail 变体) ============ */
  .fx-modal-mask {
    padding: env(safe-area-inset-top) 12px env(safe-area-inset-bottom);
  }

  .fx-modal--detail {
    width: 100%;
    max-width: 600px;
    max-height: calc(100vh - env(safe-area-inset-top) - env(safe-area-inset-bottom));
    max-height: calc(100dvh - env(safe-area-inset-top) - env(safe-area-inset-bottom));
    border-radius: var(--fx-radius-lg);
  }

  .fx-detail-header {
    padding: 12px 16px;
  }

  .fx-detail-header__title {
    font-size: 18px;
  }

  .fx-detail-header__eyebrow {
    font-size: 10px;
  }

  .fx-detail-header__subtitle {
    font-size: 12px;
  }

  .fx-detail-close {
    width: 32px;
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  /* sections + fields */
  .fx-detail-section {
    padding: 16px;
  }

  .fx-detail-section__fields {
    grid-template-columns: 1fr;
  }

  .fx-detail-field__label {
    font-size: 10px;
  }

  .fx-detail-field__value {
    font-size: 14px;
  }
}
```

⚠️ subagent 实施时需确认 `.fx-detail-section__fields` 实际 selector 是否一致（grep `.fx-detail-section` 在 OrderDetailModal CSS 实际定义）。

### 4. TablePaginationFooter mobile

JSX 改动：把 inline `style` 改为 className `fx-pagination-footer`，内部按钮 className 加上 `fx-pagination-footer__btn`，inline style 替换为 _trading.css 中的规则。

`TablePaginationFooter.tsx` 修改 only 把 inline style 提取出来：
```tsx
<div className="fx-pagination-footer">
  <button
    type="button"
    className="fx-btn-secondary fx-btn-xs fx-pagination-footer__btn"
    disabled={page <= 1 || fetching}
    onClick={() => onChange(Math.max(1, page - 1))}
  >
    上一页
  </button>
  <span className="fx-pagination-footer__page fx-mono">
    {page} / {totalPages}
  </span>
  {/* next button 同结构 */}
  <span className="fx-pagination-footer__total fx-mono">
    共 {total} 条
  </span>
</div>
```

_trading.css 加 mobile 规则：

```css
@media (max-width: 767px) {
  .fx-pagination-footer {
    display: flex;
    gap: 8px;
    align-items: center;
    padding: 12px 8px;
    justify-content: center;
  }
  .fx-pagination-footer__btn {
    min-height: 40px;
    padding: 0 12px;
    font-size: 13px;
  }
  .fx-pagination-footer__total {
    margin-left: 8px;
  }
}
```

桌面下保留原 inline style 视觉（在 `_base.css` 或 global.css 补桌面默认规则也可，但 YAGNI — 改 _trading.css 加 mobile + global.css 保留旧 inline style 默认值，把 inline style 删了在 `.fx-pagination-footer { display:flex; gap:12px; align-items:center; padding:8px 0; justify-content:flex-end; }` 写入 _trading.css **外** ⚠️：作为非 @media rule 提供桌面默认值；或者用 `.fx-pagination-footer { ... }` 作为顶层 rule 写在 _trading.css 文件顶部（在 @media block 外）。

**最干净的做法**：_trading.css 文件结构改为：
```css
/* ============ Desktop default for TablePaginationFooter ============ */
.fx-pagination-footer {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 8px 0;
  justify-content: flex-end;
}
.fx-pagination-footer__total {
  color: var(--fx-subtle);
}

@media (max-width: 767px) {
  /* ... 所有现有 mobile rules ... */
  /* + 新增 4 cards / fx-modal-mask--detail / fx-pagination-footer mobile rules */
}
```

这样 _trading.css 既有桌面默认 (just for pagination footer)，又有 @media mobile。

### 5. 4 cards 共享外壳 CSS

```css
@media (max-width: 767px) {
  /* ============ History cards (Pending / Orders / Trades / Closed) ============ */
  .fx-history-cards {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  .fx-history-card {
    position: relative;
    padding: 12px 16px;
    border-bottom: 1px solid var(--fx-hairline);
    display: flex;
    flex-direction: column;
    gap: 4px;
    background: transparent;
    cursor: pointer;
    transition: background 80ms ease;
  }

  .fx-history-card:active {
    background: rgba(84, 230, 255, 0.04);
  }

  .fx-history-card__row1 {
    display: flex;
    align-items: baseline;
    gap: var(--fx-space-2);
    font-size: 15px;
    letter-spacing: 0.02em;
  }

  .fx-history-card__row1 .fx-history-card__type,
  .fx-history-card__row1 .fx-history-card__status {
    margin-left: auto;
    font-size: 10px;
    letter-spacing: var(--fx-eyebrow-letter);
    text-transform: uppercase;
    color: var(--fx-muted);
    font-family: var(--fx-mono-stack);
  }

  .fx-history-card__symbol {
    font-weight: 600;
  }

  .fx-history-card__side {
    font-size: 12px;
    letter-spacing: var(--fx-eyebrow-letter);
  }

  .fx-history-card__row2,
  .fx-history-card__row3 {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font-family: var(--fx-mono-stack);
    font-variant-numeric: tabular-nums;
    font-size: 13px;
  }

  .fx-history-card__label {
    font-size: 10px;
    letter-spacing: var(--fx-eyebrow-letter);
    text-transform: uppercase;
    color: var(--fx-subtle);
    font-family: Inter, ui-sans-serif, system-ui;
  }

  .fx-history-card__row3 .fx-history-card__label:nth-of-type(2) {
    margin-left: 12px;
  }

  .fx-history-card__footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-top: 4px;
    padding-top: 8px;
    border-top: 1px solid var(--fx-hairline);
  }

  .fx-history-card__time {
    font-family: var(--fx-mono-stack);
    font-size: 11px;
    color: var(--fx-muted);
  }

  .fx-history-card__actions {
    display: flex;
    gap: 6px;
  }

  .fx-history-card__action-btn {
    height: 28px;
    padding: 0 10px;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    border: 1px solid var(--fx-hairline);
    border-radius: var(--fx-radius-sm);
    background: transparent;
    color: var(--fx-text);
    font-family: var(--fx-mono-stack);
    font-size: 11px;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    cursor: pointer;
  }

  .fx-history-card__action-btn--danger {
    border-color: var(--fx-short);
    color: var(--fx-short);
  }
}
```

## Visual Design Notes（继承 Phase 2A + 2B-1）

### 沿用红线（不重复）

- 数字 `var(--fx-mono-stack)` + tabular-nums
- 涨 `var(--fx-long)`, 跌 `var(--fx-short)`, accent `var(--fx-cyan)`
- spacing `var(--fx-space-*)`
- hairline `var(--fx-hairline)`, strong cyan `var(--fx-border-strong)`
- eyebrow caps 10-11px + 0.18em letter-spacing + uppercase + Inter
- Material ripple / bounce / emoji icon 禁用

### 本 Phase 特有视觉细节

| 元素 | 规格 |
|---|---|
| `.fx-history-card` | min-height 自适应（不固定，2-3 行 + footer ≈ 110-130px） |
| `.fx-history-card` 点击 ripple | scale 0.985 + cyan rgba 0.04 bg |
| `.fx-history-card__status` (Pending/Order) | eyebrow 10px cyan/muted/lime/short 视状态 |
| 取消订单 button | `__action-btn--danger`（red border + red text） |
| 详情 button | `__action-btn`（hairline border + text 色） |
| `.fx-history-card__footer` 顶部 hairline 分隔 | time 左 / actions 右 |
| OrderDetailModal mobile | 全宽 max-width 600px / sections 1 列 / fields label 10px + value 14px |
| TablePaginationFooter mobile | 居中 + 40px button + 13px font，桌面保留 right-justify 12px gap |

## Testing

| Test 文件 | 覆盖（每个 4-5 测试）|
|---|---|
| `PendingOrderCard.test.tsx` | render symbol/side/type/status / row2 数字 / onDetail click / onCancel click |
| `OrderCard.test.tsx` | render symbol/side/type / row2 qty+price / onDetail click / fx-long class for 多 |
| `TradeCard.test.tsx` | render symbol/side / row3 realizedPnl with fx-long/fx-short class / onDetail click |
| `ClosedPositionCard.test.tsx` | render symbol/side / entry→close prices / pnl class / onDetail click |

无新增对 4 Tables 本身的 unit test（现有逻辑不变，仅加 isMobile 分支）。
无新增对 OrderDetailModal 的 test（CSS-only 改造）。

## Verification 检查清单

- [ ] DevTools iPhone SE — Activity tab 切到 PendingOrders → 看到卡片列表
- [ ] PendingOrderCard 显示完整字段，点击 → OrderDetailModal mobile 全宽弹出
- [ ] OrderDetailModal mobile sections 1 列，close button 32x32
- [ ] PendingOrderCard 点 "取消" → ClosePendingOrderModal（如有），或者 cancel mutation
- [ ] 同样验证 Orders / Trades / ClosedPositions tabs
- [ ] TablePaginationFooter mobile button 40px 高，居中显示
- [ ] DevTools 1440 — 4 个 Table 桌面 byte-identical 0 regression
- [ ] OrderDetailModal 桌面布局保留
- [ ] `npm run test` 全过（新 4 cards × ~4-5 测试 = ~18 新测试）
- [ ] `npm run build` 0 error
- [ ] `npm run lint` 0 H5 regression

## Risk & 已知约束

| 风险 | 缓解 |
|---|---|
| 4 个 Table 桌面分支 JSX 漂移 regression | subagent 必须读 actual current code，byte-identical copy |
| `.fx-detail-section__fields` className 实际不一致 | subagent 必须先 grep 现有 OrderDetailModal CSS 找 selector，按现状调整 mobile 规则 |
| TradeItem / OrderItem / PendingOrderItem 字段名跟 spec 不一致 | subagent 必须先 grep tradingTypes.ts 找 exact 字段名 |
| PendingOrderCard `onCancel` callback 跟现有 PendingOrdersTable cancel 流程对接（detail 触发的是 status mutation 不是新 modal） | spec 阶段不细化；plan task 阶段 subagent 看 PendingOrdersTable 实际 cancel 逻辑（可能是 button onClick → mutation 或 confirm 然后 mutation） |

## Out-of-Scope（Phase 2B-3 / 2C）

明确不做：
- NotificationCenter mobile → Phase 2B-3
- PriceAlertsTable mobile → Phase 2B-3
- TradingTabs mobile 微调 → Phase 2B-3
- WalletPage 充值/提现/转账 mobile → Phase 2C
