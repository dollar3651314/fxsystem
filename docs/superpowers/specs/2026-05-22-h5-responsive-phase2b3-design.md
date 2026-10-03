# H5 自适应 Phase 2B-3 设计 — trading 通知/预警/Tab 收尾

> 前置：Phase 0+1（5afffb4）+ 2A（025a106）+ 2B-1（1cdcba1）+ 2B-2（2133f6e）
> 收尾：完成后 trading feature mobile 全量交付，进 Phase 2C wallet

## Goal

3 个 trading 收尾组件 mobile 适配：

| 组件 | 当前桌面形态 | mobile 改造 |
|---|---|---|
| **TradingTabs** | 6 tab horizontal bar 含 __num "01-06" | 横向滚动 scroll-snap + 隐藏 __num + tab 紧凑 |
| **NotificationCenter** | bell button + 右侧 .notification-drawer + overlay | drawer 100vw 全屏 + safe-area + close 32x32（CSS-only）|
| **PriceAlertsTable** | 11 列 fx-table + 自带 .fx-modal-mask 创建告警 modal | isMobile 分支 → PriceAlertCard × N；create modal mobile CSS（.fx-modal-title 变体）|

桌面 0 regression。JSX 改动仅限 PriceAlertsTable 加 isMobile 条件渲染。其他 2 组件纯 CSS。

## Architecture

```
┌─ Layer 3 (feature views)  ─────────────────────────────────────┐
│  PriceAlertsTable mobile 卡片分支 (useBreakpoint)               │
│  TradingTabs / NotificationCenter 0 JSX 改动                    │
└──────────────────────────────────────────────────────────────────┘
┌─ Layer 2 (component-level mobile CSS)  ────────────────────────┐
│  _trading.css 同一 @media block 末尾追加：                       │
│    - .fx-trading-tabs / .fx-tab-bar / .fx-tab-button mobile     │
│    - .notification-drawer / .notification-item / .level-* mobile│
│    - .fx-price-alert-card (新外壳)                              │
│    - .fx-modal-title (创建 modal) mobile 适配                   │
└──────────────────────────────────────────────────────────────────┘
┌─ Layer 1 (基础设施)  ───────────────────────────────────────────┐
│  Phase 0+1 + 2A + 2B-1 + 2B-2 (useBreakpoint / .fx-history-card)│
└──────────────────────────────────────────────────────────────────┘
```

**关键设计选择（用户拍板）：**
1. TradingTabs = **横向滚动 + 隐 __num**（scroll-snap 体验流畅，所有 tab 可见）
2. NotificationCenter = **现有 .notification-drawer CSS mobile 适配**（0 JSX 改动）
3. PriceAlertsTable = **PriceAlertCard 卡片 + create modal CSS**（跟 Phase 2B-2 同模式）

## File Changes

### Create

- `falconx-frontend/src/features/trading/PriceAlertCard.tsx` — mobile 价格告警卡片
- `falconx-frontend/src/features/trading/PriceAlertCard.test.tsx` — 4-5 测试

### Modify

- `falconx-frontend/src/styles/modules/_trading.css` — 在现有 @media block 末尾追加：
  - `.fx-trading-tabs / .fx-tab-bar / .fx-tab-button / __num` mobile 规则 (~30 行)
  - `.notification-bell / .notification-overlay / .notification-drawer / .notification-drawer__* / .notification-item / .level-*` mobile 规则 (~80 行)
  - `.fx-modal-title` mobile 规则（PriceAlerts create modal）(~15 行)
- `falconx-frontend/src/features/trading/PriceAlertsTable.tsx` — useBreakpoint + isMobile 条件渲染（桌面 byte-identical）

NotificationCenter.tsx / TradingTabs.tsx **0 JSX 改动**（CSS-only）。

## Detailed Feature Changes

### 1. TradingTabs mobile（CSS-only）

桌面：`.fx-tab-bar` 6 个 `.fx-tab-button` 横排，每个 button 含 `.fx-tab-button__num`（"01"-"06"）+ label。

Mobile：
```css
@media (max-width: 767px) {
  .fx-tab-bar {
    overflow-x: auto;
    overflow-y: hidden;
    scroll-snap-type: x proximity;
    flex-wrap: nowrap;
    -webkit-overflow-scrolling: touch;
    /* 隐藏滚动条 */
    scrollbar-width: none;
  }
  .fx-tab-bar::-webkit-scrollbar {
    display: none;
  }

  .fx-tab-button {
    flex-shrink: 0;
    scroll-snap-align: start;
    min-width: auto;
    padding: 8px 14px;
    font-size: 13px;
  }

  /* mobile 隐藏 __num 节省横向空间 */
  .fx-tab-button__num {
    display: none;
  }
}
```

### 2. NotificationCenter mobile（CSS-only）

桌面：bell button + overlay + 右侧 320px `.notification-drawer`。
Mobile：drawer 100vw 全屏，bell button 适配 mobile 顶部位置。

```css
@media (max-width: 767px) {
  /* bell button position 可保持现有（mobile 在 TerminalTopbar 内，TerminalTopbar 已 display:none，
   * 实际 mobile bell 出现在 MobileShell header 中 — 检查后决定是否还需 .notification-bell mobile rules）
   * 若 bell 已在 mobile header，则 .notification-bell mobile 视觉不动 */

  .notification-overlay {
    background: rgba(0, 0, 0, 0.5);
  }

  .notification-drawer {
    width: 100vw;
    max-width: 100vw;
    padding-top: env(safe-area-inset-top);
    padding-bottom: env(safe-area-inset-bottom);
  }

  .notification-drawer__header {
    padding: 12px 16px;
    border-bottom: 1px solid var(--fx-hairline);
  }

  .notification-drawer__header h3 {
    font-size: 16px;
  }

  .notification-drawer__close {
    width: 32px;
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .notification-drawer__body {
    padding: 0;
    overflow-y: auto;
  }

  .notification-item {
    padding: 12px 16px;
    border-bottom: 1px solid var(--fx-hairline);
  }

  .notification-item__row {
    gap: 8px;
    align-items: center;
    margin-bottom: 6px;
  }

  .notification-item__level {
    font-size: 10px;
    letter-spacing: var(--fx-eyebrow-letter);
    text-transform: uppercase;
    padding: 2px 6px;
    border-radius: var(--fx-radius-sm);
  }

  .notification-item__title {
    font-size: 14px;
    font-weight: 500;
    margin-bottom: 4px;
  }

  .notification-item__body {
    font-size: 12px;
    color: var(--fx-muted);
    line-height: 1.5;
  }

  /* level 颜色 mobile 保留桌面定义（INFO=cyan / WARN=risk / CRITICAL=short）
   * 假设已有桌面规则 — 不重复定义 */
}
```

⚠️ subagent 实施时需 grep `.notification-bell` / `.notification-item__level` / `.level-info` 等实际 CSS 看是否需要 mobile 微调（如 padding 不变可省略）。

### 3. PriceAlertCard 组件设计

PriceAlertItem 字段：`id / symbol / direction(ABOVE|BELOW) / targetPrice / status / note / basePrice / triggerCount / remainingTriggers / lastTriggeredAt / lastTriggeredPrice / cancelledAt / cancelSource / createdAt / updatedAt`

```tsx
export interface PriceAlertCardProps {
  item: PriceAlertItem;
  onCancel: (item: PriceAlertItem) => void;
  cancelDisabled: boolean;
}
```

5 行卡片（沿用 `.fx-history-card` 通用外壳）：
```
┌─────────────────────────────────────┐
│ BTCUSDT  ▲上穿                ACTIVE│  row1: symbol + direction(▲/▼ + label) + status
│ 触发 68000.00 · 基准 67432.50       │  row2: targetPrice + basePrice (mono tabular)
│ 触发 1/3 · 剩 2 · 最近 09:00        │  row3: triggerCount + remaining + lastTriggeredAt
│ "BTC 突破"                           │  row4 (有 note 时): note
│ 2026-05-22 09:00      [取消]        │  footer: createdAt + cancel button (danger)
└─────────────────────────────────────┘
```

颜色规则：
- direction ABOVE → `▲` + fx-long
- direction BELOW → `▼` + fx-short
- status ACTIVE → cyan eyebrow
- status EXHAUSTED / CANCELLED / ADMIN_DELETED → muted eyebrow

操作：
- `cancel` button — fx-history-card__action-btn--danger（仅 status === "ACTIVE" 显示）
- click body 不触发 modal（PriceAlerts 没有 detail modal）— 卡片本身不可点击；只有 cancel 按钮可点

### 4. PriceAlertsTable isMobile 集成

模式同 Phase 2B-2 4 Tables：

```tsx
const { isMobile } = useBreakpoint();

return (
  <div className="fx-tab-content">
    {/* 顶部 新建告警 button 保留 */}
    {/* loading / empty 状态保留 */}

    {items.length > 0 && !isMobile && (
      <table className="fx-table">{/* 现有 byte-identical */}</table>
    )}
    {items.length > 0 && isMobile && (
      <ul className="fx-history-cards" aria-label="价格告警列表">
        {items.map((a) => (
          <PriceAlertCard
            key={a.id}
            item={a}
            onCancel={(item) => cancelMutation.mutate(item.id)}
            cancelDisabled={cancelMutation.isPending}
          />
        ))}
      </ul>
    )}

    {/* 创建告警 fx-modal-mask modal 保留，CSS 在 _trading.css 补 mobile */}
  </div>
);
```

### 5. PriceAlerts create modal mobile（.fx-modal-title 变体）

PriceAlerts 创建告警用 `<div className="fx-modal-mask">` + `<div className="fx-modal">` + `<div className="fx-modal-title">` 结构（不同于 OrderDetailModal 用 `.fx-detail-header`，也不同于 ClosePositionModal 等用 `.fx-modal-header h3`）。

需补的 mobile rules：

```css
@media (max-width: 767px) {
  /* PriceAlerts create modal (.fx-modal-title variant) */
  .fx-modal-title {
    padding: 12px 16px;
    font-size: 16px;
    border-bottom: 1px solid var(--fx-hairline);
  }
}
```

`.fx-modal-mask` 已在 Phase 2B-2 Task 2 加过 mobile safe-area padding 规则 — 不重复。
`.fx-modal` 本身的 mobile 规则（在 Phase 2B-1 Task 2 已加过）继续生效 — 不需要补。

### Tab-bar fade hint（可选）

考虑加 cyan fade gradient 在 tab-bar 右侧暗示可滚动：

```css
@media (max-width: 767px) {
  .fx-tab-bar {
    position: relative;
    /* 右侧 fade 暗示可横滚 */
    mask-image: linear-gradient(to right, black 80%, transparent 100%);
    -webkit-mask-image: linear-gradient(to right, black 80%, transparent 100%);
  }
}
```

但 mask-image 兼容性问题（iOS Safari < 16 不支持）— 用 box-shadow inset 替代或者 YAGNI 略过。Plan 阶段决定。

## Visual Design Notes（继承前序 Phase）

### 沿用红线
- 数字 var(--fx-mono-stack) + tabular-nums
- 涨 var(--fx-long) / 跌 var(--fx-short) / cyan var(--fx-cyan)
- spacing var(--fx-space-*) / hairline var(--fx-hairline)
- eyebrow 10-11px + 0.18em letter-spacing + uppercase + Inter
- 触摸 ≥ 32px / 主操作 ≥ 40px
- 不引入 material ripple / bounce

### 本 Phase 特有
- TradingTabs mobile 横向滚动：scroll-snap-type x proximity，flex-shrink 0
- TradingTabs mobile 隐 __num（节省横向空间）
- NotificationCenter mobile drawer 100vw + safe-area
- PriceAlertCard direction ABOVE = fx-long ▲，BELOW = fx-short ▼
- PriceAlertCard status ACTIVE = cyan eyebrow，其他 = muted
- PriceAlertCard click body 不触发 detail（不像其他 history card）— 仅 cancel 按钮可点

## Testing

| Test | 覆盖 |
|---|---|
| `PriceAlertCard.test.tsx` | render symbol/direction/status row1 / targetPrice+basePrice row2 / direction class (long/short) / onCancel click / cancel hidden when status !== ACTIVE | 5 测试 |

无新增 TradingTabs / NotificationCenter test（CSS-only 改造）。
无新增 PriceAlertsTable test（仅加 isMobile 分支）。

## Verification 检查清单

- [ ] DevTools iPhone SE — TradingTabs 6 tab 横向滚动 scroll-snap 流畅
- [ ] TradingTabs mobile 不显示 __num "01-06"
- [ ] 点 bell button → NotificationCenter drawer 全宽 100vw 滑入
- [ ] notification-item level 着色（INFO/WARN/CRITICAL）正确
- [ ] notification-drawer__close 32x32 touch 友好
- [ ] Activity tab 切到 告警 → PriceAlertCard 卡片列表
- [ ] PriceAlertCard direction ABOVE=lime ▲ / BELOW=red ▼ / status ACTIVE=cyan eyebrow
- [ ] PriceAlertCard 点 取消（仅 ACTIVE 状态可见）→ cancel mutation
- [ ] 点 "新建告警" → create modal mobile 全宽 + title 16px
- [ ] DevTools 1440 桌面 0 regression（TradingTabs 6 tab 横排 + __num 可见 / NotificationCenter 右侧 320px drawer / PriceAlertsTable 11 列 table / create modal 居中）
- [ ] `npm run test` 全过（新 5 测试）
- [ ] `npm run build` 0 error
- [ ] `npm run lint` 0 H5 regression

## Risk & 已知约束

| 风险 | 缓解 |
|---|---|
| PriceAlertsTable 桌面 `<table>` byte-identical regression | subagent 必须读 actual current code，byte-identical copy |
| `.notification-item__level` CSS 桌面已有 padding/font，mobile 重复 declaration | spec 给出 mobile values；如果跟桌面相同的属性，subagent 实施时可 omit 重复 |
| `.fx-tab-bar` 桌面已经有 scrollbar 样式或 overflow-x 设置 | mobile @media 强制覆盖 — overflow-x: auto 优先级足够 |
| `.fx-tab-button__num` 桌面用 ::before / inline span — mobile display none 都生效 | OK |
| mobile TradingTabs scroll snap 在 Android Chrome 老版本不支持 | 优雅退化（普通 overflow-x scroll 不影响功能） |
| PriceAlertCard 没有 detail modal — UX 跟其他 history card 不一致（不可点 body） | 用户拍板：PriceAlerts 不需要 detail，仅 cancel action |
| `.fx-modal-mask` 已经在 Phase 2B-2 Task 2 加过 mobile safe-area 规则 | spec 提醒 subagent 不重复定义，只补 `.fx-modal-title` 一个新 selector |

## Out-of-Scope（Phase 2C / 3 / 4 / 5）

明确不做：
- Wallet（充值/提现/转账 multi-tab）mobile → Phase 2C
- WithdrawDrawer / KycSubmitDrawer / ProfilePanel mobile → Phase 3（客户端 P1）
- Activity / Dashboard / Settings mobile feature 内部布局 → Phase 3
- 管理端 console-frontend mobile → Phase 4
- iOS Safari / Android Chrome 多尺寸真机 QA → Phase 5
