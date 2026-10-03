# H5 自适应改造 — Phase 2B-1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** trading 核心交易流 5 组件 mobile 适配（OrderTicket / PositionsTable / ClosePositionModal / AddMarginModal / EditRiskControlsModal），桌面 0 regression，完成"下单→看持仓→平仓/加保证金/改 SL TP"完整 UX 闭环。

**Architecture:** 3 层：(1) Phase 0+1 + 2A 基础设施 (2) 新增 `_trading.css` 收 OrderTicket / fx-modal / fx-positions cards mobile rules (3) PositionsTable.tsx 加 `isMobile` 分支条件渲染新 `PositionItemCard`，桌面 13 列 table byte-identical。其他 4 组件 0 JSX 改动。

**Tech Stack:** React 19 + TypeScript + Vite + Vitest（已有）+ lucide-react（已有，icon 用 `MoreHorizontal` / `X` / `Plus` / `PenLine`）

---

## File Structure

### Create 3 files

| 路径 | 职责 |
|---|---|
| `falconx-frontend/src/styles/modules/_trading.css` | OrderTicket / fx-modal / fx-positions cards mobile @media rules |
| `falconx-frontend/src/features/trading/PositionItemCard.tsx` | mobile 5 行卡片组件（替代桌面 PositionRow） |
| `falconx-frontend/src/features/trading/PositionItemCard.test.tsx` | 6 单元测试 |

### Modify 2 files

| 路径 | 改动 |
|---|---|
| `falconx-frontend/src/styles/global.css` | 顶部 `@import "./modules/_trading.css";` 加在 `_market.css` 之后 |
| `falconx-frontend/src/features/trading/PositionsTable.tsx` | 加 useBreakpoint + PositionItemCard import，`isMobile` 分支 mobile cards / 桌面 table byte-identical |

---

## Task 1: _trading.css 新建 + OrderTicket mobile rules + global.css @import

**Files:**
- Create: `falconx-frontend/src/styles/modules/_trading.css`
- Modify: `falconx-frontend/src/styles/global.css`

- [ ] **Step 1: 创建 _trading.css 含 OrderTicket mobile rules**

创建 `falconx-frontend/src/styles/modules/_trading.css`：

```css
/* FalconX H5 — trading 核心组件 mobile 适配
 * 桌面（≥ 768px）保持现有 OrderTicket / fx-modal / fx-positions 样式不变。
 * 仅在 < 768px 加 mobile 触摸 / 全屏 / 卡片规则。
 */

@media (max-width: 767px) {
  /* ============ OrderTicket ============ */
  .order-ticket {
    height: auto;
    min-height: 0;
    overflow: visible;
  }

  .order-ticket .fx-panel-head {
    padding: 12px 16px;
  }

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

  .ticket-amount input {
    min-height: 48px;
    font-size: 16px;
  }

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

  .ticket-switch .long.active {
    border: 1px solid var(--fx-long);
    color: var(--fx-long);
  }

  .ticket-switch .short.active {
    border: 1px solid var(--fx-short);
    color: var(--fx-short);
  }

  .order-ticket input[type="text"],
  .order-ticket input[type="number"] {
    min-height: 48px;
    font-size: 16px;
  }

  .ticket-checkbox-row {
    gap: 12px;
    padding: 8px 16px;
  }

  .ticket-checkbox-row input[type="checkbox"] {
    width: 24px;
    height: 24px;
    flex-shrink: 0;
  }

  .order-submit {
    min-height: 56px;
    font-size: 15px;
  }
}
```

- [ ] **Step 2: 修改 global.css 顶部 @import**

读 `falconx-frontend/src/styles/global.css` 顶部 6 行，预期：
```css
@import "./tokens.css";
@import "./modules/_base.css";
@import "./modules/_mobile-shell.css";
@import "./modules/_auth.css";
@import "./modules/_market.css";
```

在 `@import "./modules/_market.css";` 后追加一行：

```css
@import "./modules/_trading.css";
```

- [ ] **Step 3: build 确认无 CSS @import 错误**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -10`
Expected: build 完成，0 error

- [ ] **Step 4: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b1
git branch --show-current
# 必须输出 worktree-h5-phase2b1
git add falconx-frontend/src/styles/modules/_trading.css \
        falconx-frontend/src/styles/global.css
git commit -m "feat(h5-phase2b1-1): _trading.css OrderTicket mobile + global.css @import

OrderTicket < 768px:
- order-type tabs 等宽 segmented 40px
- ticket-switch LONG/SHORT 两半等宽 44px + long/short token border 色
- 所有 input 48px + 16px font 防 iOS auto-zoom
- TP/SL checkbox 24x24px
- order-submit 56px CTA

桌面体验 0 改动（仅 @media (max-width: 767px) 规则）。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: _trading.css 追加 fx-modal mobile rules (3 modal 共享)

**Files:**
- Modify: `falconx-frontend/src/styles/modules/_trading.css` (在 @media block 末尾追加)

- [ ] **Step 1: 读 _trading.css 末尾确认 @media block 结构**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b1
tail -10 falconx-frontend/src/styles/modules/_trading.css
```

Expected: 看到 `.order-submit { ... }` 后面紧跟 @media block 闭合 `}`

- [ ] **Step 2: 在 @media block 末尾追加 fx-modal rules**

在 `.order-submit { ... }` 之后、@media `}` 闭合之前追加：

```css

  /* ============ fx-modal (ClosePosition / AddMargin / EditRiskControls 3 个 modal 共享) ============ */
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

  .fx-modal-close {
    width: 32px;
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .fx-modal-body {
    padding: 16px;
    overflow-y: auto;
  }

  .fx-modal-dl {
    word-break: break-word;
  }

  .fx-modal-field input,
  .fx-modal-field select {
    min-height: 48px;
    font-size: 16px;
  }

  .fx-modal-error {
    font-size: 13px;
  }

  .fx-modal-footer {
    padding: 12px 16px;
    gap: 8px;
  }

  .fx-modal-footer button {
    min-height: 48px;
    flex: 1;
  }
```

- [ ] **Step 3: 确认 @media block 仍正确闭合**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b1
tail -3 falconx-frontend/src/styles/modules/_trading.css
```

Expected: 末尾 `}` 单独成行

- [ ] **Step 4: build 确认**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -10`
Expected: 0 error

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b1
git add falconx-frontend/src/styles/modules/_trading.css
git commit -m "feat(h5-phase2b1-2): _trading.css fx-modal mobile 适配 (3 modal 共享)

ClosePositionModal / AddMarginModal / EditRiskControlsModal < 768px:
- backdrop safe-area padding
- modal 100% width max 480px + max-height 100dvh - safe-area
- header / body / footer 紧凑 padding
- close button 32x32 touch friendly
- field input 48px + 16px font 防 iOS zoom
- footer CTA 48px 等宽

3 modal 0 JSX 改动，统一 CSS 改造。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: PositionItemCard 组件 + 6 测试

**Files:**
- Create: `falconx-frontend/src/features/trading/PositionItemCard.tsx`
- Create: `falconx-frontend/src/features/trading/PositionItemCard.test.tsx`

- [ ] **Step 1: 写失败测试**

创建 `falconx-frontend/src/features/trading/PositionItemCard.test.tsx`：

```tsx
import { afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { PositionItemCard } from "./PositionItemCard";
import type { PositionItem } from "./tradingTypes";

afterEach(() => cleanup());

const basePosition: PositionItem = {
  positionId: "pos-abc-123",
  openingOrderId: "ord-1",
  symbol: "BTCUSDT",
  side: "BUY",
  quantity: "0.01",
  entryPrice: "67432.50",
  leverage: "50",
  margin: "5.50",
  marginMode: "ISOLATED",
  liquidationPrice: "61580.00",
  takeProfitPrice: "68500.00",
  stopLossPrice: "66800.00",
  markPrice: "67450.00",
  unrealizedPnl: "18.50",
  closePrice: null,
  closeReason: null,
  realizedPnl: "12.30",
  status: "OPEN",
  quoteStale: false,
  quoteTs: null,
  quoteSource: null,
  openedAt: "2026-05-20T09:00:00Z",
  closedAt: null,
  updatedAt: "2026-05-22T09:00:00Z",
  openFee: "0.13",
  openFeeRate: "0.0002",
};

describe("PositionItemCard", () => {
  it("renders symbol, side, quantity, leverage in row 1", () => {
    render(
      <PositionItemCard
        position={basePosition}
        pnl={undefined}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("多")).toBeInTheDocument();
    expect(screen.getByText("0.01")).toBeInTheDocument();
    expect(screen.getByText("50x")).toBeInTheDocument();
  });

  it("renders entry → mark prices in row 2", () => {
    render(
      <PositionItemCard
        position={basePosition}
        pnl={undefined}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    expect(screen.getByText("67432.50")).toBeInTheDocument();
    expect(screen.getByText("67450.00")).toBeInTheDocument();
  });

  it("applies long class when side is BUY and pnl is positive", () => {
    const { container } = render(
      <PositionItemCard
        position={basePosition}
        pnl={undefined}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    expect(container.querySelector(".fx-long")).not.toBeNull();
  });

  it("calls onClose when 平仓 button clicked", async () => {
    const handle = vi.fn();
    render(
      <PositionItemCard
        position={basePosition}
        pnl={undefined}
        onDetail={() => {}}
        onClose={handle}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /平仓/ }));
    expect(handle).toHaveBeenCalledOnce();
  });

  it("calls onAddMargin and onEditRiskControls from More actions", async () => {
    const onAddMargin = vi.fn();
    const onEditRiskControls = vi.fn();
    render(
      <PositionItemCard
        position={basePosition}
        pnl={undefined}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={onAddMargin}
        onEditRiskControls={onEditRiskControls}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /补保/ }));
    expect(onAddMargin).toHaveBeenCalledOnce();
    await userEvent.click(screen.getByRole("button", { name: /改 TP\/SL/ }));
    expect(onEditRiskControls).toHaveBeenCalledOnce();
  });

  it("uses patch.unrealizedPnl when pnl override is provided", () => {
    render(
      <PositionItemCard
        position={basePosition}
        pnl={{
          positionId: "pos-abc-123",
          unrealizedPnl: "25.99",
          markPrice: "67455.00",
        }}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    expect(screen.getByText("25.99")).toBeInTheDocument();
    expect(screen.getByText("67455.00")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd falconx-frontend && npx vitest run src/features/trading/PositionItemCard.test.tsx`
Expected: FAIL `Cannot find module './PositionItemCard'`

- [ ] **Step 3: 实现 PositionItemCard 组件**

创建 `falconx-frontend/src/features/trading/PositionItemCard.tsx`：

```tsx
import { MoreHorizontal, X } from "lucide-react";
import { useState } from "react";
import type { PositionItem } from "./tradingTypes";
import type { PositionPnlUpdate } from "./useTradingSocket";

export interface PositionItemCardProps {
  position: PositionItem;
  pnl: PositionPnlUpdate | undefined;
  onDetail: (p: PositionItem) => void;
  onClose: (p: PositionItem) => void;
  onAddMargin: (p: PositionItem) => void;
  onEditRiskControls: (p: PositionItem) => void;
}

function fmt(value: string | null | undefined): string {
  return value ?? "—";
}

function pnlClass(value: string | null | undefined): string {
  if (value == null) return "";
  const n = Number(value);
  if (!Number.isFinite(n)) return "";
  return n > 0 ? "fx-long" : n < 0 ? "fx-short" : "";
}

export function PositionItemCard({
  position: p,
  pnl,
  onDetail,
  onClose,
  onAddMargin,
  onEditRiskControls,
}: PositionItemCardProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const sideLabel = p.side === "BUY" ? "多" : "空";
  const sideClass = p.side === "BUY" ? "fx-long" : "fx-short";
  const markPrice = pnl?.markPrice ?? p.markPrice;
  const unrealizedPnl = pnl?.unrealizedPnl ?? p.unrealizedPnl;

  return (
    <li className="fx-position-card" onClick={() => onDetail(p)}>
      <div className="fx-position-card__row1">
        <strong className="fx-position-card__symbol">{p.symbol}</strong>
        <span className={`fx-position-card__side ${sideClass}`}>{sideLabel}</span>
        <span className="fx-position-card__qty">{p.quantity}</span>
        <span className="fx-position-card__lev">{p.leverage}x</span>
      </div>
      <div className="fx-position-card__row2">
        <span className="fx-num">{p.entryPrice}</span>
        <span className="fx-position-card__arrow">→</span>
        <span className="fx-num">{fmt(markPrice)}</span>
        <span className="fx-position-card__mark-label">标记</span>
      </div>
      <div className="fx-position-card__row3">
        <span className="fx-position-card__label">未实现</span>
        <span className={`fx-num ${pnlClass(unrealizedPnl)}`}>{fmt(unrealizedPnl)}</span>
        <span className="fx-position-card__label">净盈</span>
        <span className={`fx-num ${pnlClass(p.realizedPnl)}`}>{fmt(p.realizedPnl)}</span>
      </div>
      <div className="fx-position-card__row4">
        <span className="fx-position-card__label">保证金</span>
        <span className="fx-num">{p.margin}</span>
        <span className="fx-position-card__label">强平</span>
        <span className="fx-num fx-short">{fmt(p.liquidationPrice)}</span>
      </div>
      <div className="fx-position-card__row5" onClick={(e) => e.stopPropagation()}>
        <span className="fx-position-card__label">TP</span>
        <span className="fx-num">{fmt(p.takeProfitPrice)}</span>
        <span className="fx-position-card__sep">·</span>
        <span className="fx-position-card__label">SL</span>
        <span className="fx-num">{fmt(p.stopLossPrice)}</span>
        <div className="fx-position-card__actions">
          <button
            type="button"
            className="fx-position-card__more"
            aria-label="更多操作"
            onClick={() => setMenuOpen((v) => !v)}
          >
            <MoreHorizontal size={16} strokeWidth={1.8} />
          </button>
          <button
            type="button"
            className="fx-position-card__close"
            aria-label="平仓"
            onClick={() => onClose(p)}
          >
            <X size={16} strokeWidth={2} />
            平仓
          </button>
        </div>
      </div>
      {menuOpen && (
        <div className="fx-position-card__menu" onClick={(e) => e.stopPropagation()}>
          <button
            type="button"
            onClick={() => {
              setMenuOpen(false);
              onAddMargin(p);
            }}
          >
            补保
          </button>
          <button
            type="button"
            onClick={() => {
              setMenuOpen(false);
              onEditRiskControls(p);
            }}
          >
            改 TP/SL
          </button>
        </div>
      )}
    </li>
  );
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd falconx-frontend && npx vitest run src/features/trading/PositionItemCard.test.tsx`
Expected: PASS — 6 tests passed

⚠️ 如果 5 号测试（More actions）fail because menu 没默认展开，确保测试先点 "更多操作" 按钮再点 "补保" / "改 TP/SL"。修测试如下：

```tsx
// 5 号测试 click 顺序更新为：先点 more 再点 action
it("calls onAddMargin and onEditRiskControls from More actions", async () => {
  const onAddMargin = vi.fn();
  const onEditRiskControls = vi.fn();
  render(
    <PositionItemCard
      position={basePosition}
      pnl={undefined}
      onDetail={() => {}}
      onClose={() => {}}
      onAddMargin={onAddMargin}
      onEditRiskControls={onEditRiskControls}
    />
  );
  await userEvent.click(screen.getByRole("button", { name: /更多操作/ }));
  await userEvent.click(screen.getByRole("button", { name: /补保/ }));
  expect(onAddMargin).toHaveBeenCalledOnce();
  await userEvent.click(screen.getByRole("button", { name: /更多操作/ }));
  await userEvent.click(screen.getByRole("button", { name: /改 TP\/SL/ }));
  expect(onEditRiskControls).toHaveBeenCalledOnce();
});
```

⚠️ 如果发现 PositionPnlUpdate 类型字段不是 `{ positionId, unrealizedPnl, markPrice }`，根据 `useTradingSocket.ts` 中实际定义调整 `basePosition` 和测试 patch object（fmt unrealizedPnl / markPrice 是关键字段，确认存在即可）。

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b1
git branch --show-current
git add falconx-frontend/src/features/trading/PositionItemCard.tsx \
        falconx-frontend/src/features/trading/PositionItemCard.test.tsx
git commit -m "feat(h5-phase2b1-3): PositionItemCard mobile 5 行卡片 + 6 测试

mobile 持仓卡片单元（替代桌面 13 列 PositionRow）：
- row1: symbol + 多/空(long/short class) + qty + leverage
- row2: entry -> mark price (mono tabular)
- row3: 未实现 + 净盈 (pnl 涨跌色)
- row4: 保证金 + 强平价
- row5: TP/SL + 操作(更多/平仓)
- More 展开 补保/改 TP/SL action

接口 4 callback (onDetail/onClose/onAddMargin/onEditRiskControls)
对齐桌面 PositionRow 签名 (PositionItem 参数)，方便复用 PositionsTable 现有 setter。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: _trading.css 追加 PositionItemCard mobile 卡片样式

**Files:**
- Modify: `falconx-frontend/src/styles/modules/_trading.css` (在 @media block 末尾追加)

- [ ] **Step 1: 在 @media block 末尾追加 position cards rules**

在 `.fx-modal-footer button { ... }` 之后、@media `}` 闭合之前追加：

```css

  /* ============ Positions cards (mobile) ============ */
  .fx-positions-cards {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  .fx-position-card {
    position: relative;
    padding: 12px 16px;
    border-bottom: 1px solid var(--fx-hairline);
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-height: 140px;
    background: transparent;
    cursor: pointer;
    transition: background 80ms ease;
  }

  .fx-position-card:active {
    background: rgba(84, 230, 255, 0.04);
  }

  .fx-position-card.selected::before {
    content: "";
    position: absolute;
    left: 0;
    top: 0;
    bottom: 0;
    width: 3px;
    background: var(--fx-cyan);
  }

  .fx-position-card__row1 {
    display: flex;
    align-items: baseline;
    gap: var(--fx-space-2);
    font-size: 15px;
    letter-spacing: 0.02em;
  }

  .fx-position-card__row1 .fx-position-card__lev {
    margin-left: auto;
    font-size: 10px;
    letter-spacing: var(--fx-eyebrow-letter);
    text-transform: uppercase;
    color: var(--fx-muted);
    font-family: var(--fx-mono-stack);
  }

  .fx-position-card__qty {
    font-family: var(--fx-mono-stack);
    font-variant-numeric: tabular-nums;
    font-size: 13px;
    color: var(--fx-muted);
  }

  .fx-position-card__symbol {
    font-weight: 600;
  }

  .fx-position-card__side {
    font-size: 12px;
    letter-spacing: var(--fx-eyebrow-letter);
  }

  .fx-position-card__row2,
  .fx-position-card__row3,
  .fx-position-card__row4 {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font-family: var(--fx-mono-stack);
    font-variant-numeric: tabular-nums;
    font-size: 13px;
  }

  .fx-position-card__arrow {
    color: var(--fx-muted);
  }

  .fx-position-card__mark-label,
  .fx-position-card__label {
    font-size: 10px;
    letter-spacing: var(--fx-eyebrow-letter);
    text-transform: uppercase;
    color: var(--fx-subtle);
    font-family: Inter, ui-sans-serif, system-ui;
  }

  .fx-position-card__row3 .fx-position-card__label:nth-of-type(2),
  .fx-position-card__row4 .fx-position-card__label:nth-of-type(2) {
    margin-left: 12px;
  }

  .fx-position-card__row5 {
    display: flex;
    align-items: center;
    gap: 6px;
    font-family: var(--fx-mono-stack);
    font-variant-numeric: tabular-nums;
    font-size: 11px;
    color: var(--fx-muted);
  }

  .fx-position-card__sep {
    color: var(--fx-subtle);
  }

  .fx-position-card__actions {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .fx-position-card__more {
    width: 32px;
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--fx-hairline);
    border-radius: var(--fx-radius-sm);
    background: transparent;
    color: var(--fx-muted);
    cursor: pointer;
  }

  .fx-position-card__close {
    height: 32px;
    padding: 0 10px;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    border: 1px solid var(--fx-border-strong);
    border-radius: var(--fx-radius-sm);
    background: transparent;
    color: var(--fx-cyan);
    font-family: var(--fx-mono-stack);
    font-size: 12px;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    cursor: pointer;
  }

  .fx-position-card__close:active {
    background: rgba(84, 230, 255, 0.08);
  }

  .fx-position-card__menu {
    position: absolute;
    right: 16px;
    bottom: 56px;
    display: flex;
    gap: 6px;
    background: var(--fx-surface-2);
    border: 1px solid var(--fx-hairline);
    border-radius: var(--fx-radius-md);
    padding: 6px;
    z-index: 5;
    box-shadow: 0 6px 16px -8px rgba(0, 0, 0, 0.6);
  }

  .fx-position-card__menu button {
    padding: 6px 10px;
    border: 0;
    border-radius: var(--fx-radius-sm);
    background: transparent;
    color: var(--fx-text);
    font-size: 13px;
    cursor: pointer;
  }

  .fx-position-card__menu button:active {
    background: rgba(84, 230, 255, 0.08);
  }
```

- [ ] **Step 2: build 确认**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -10`
Expected: 0 error

- [ ] **Step 3: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b1
git add falconx-frontend/src/styles/modules/_trading.css
git commit -m "feat(h5-phase2b1-4): _trading.css PositionItemCard mobile 5 行卡片样式

mobile 持仓卡片视觉：
- 卡片 140px min-height + 12/16px padding + hairline border-bottom
- selected 左侧 3px cyan 竖条
- 5 行 layout (symbol/side/qty/lev → entry→mark → unrealized+net → margin+liq → TP/SL+actions)
- 数字全部 mono tabular + fx-long/short pnl 色
- eyebrow label 10-11px + 18% letter-spacing uppercase
- More menu absolute 弹层 (补保 / 改 TP/SL)
- 平仓 button cyan border 强调

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: PositionsTable.tsx 集成 isMobile 条件渲染

**Files:**
- Modify: `falconx-frontend/src/features/trading/PositionsTable.tsx`

- [ ] **Step 1: 读 PositionsTable.tsx 当前结构**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b1
sed -n '1,30p' falconx-frontend/src/features/trading/PositionsTable.tsx
sed -n '285,350p' falconx-frontend/src/features/trading/PositionsTable.tsx
```

Expected: 看到现有 imports，PositionRow 调用，最终 return 含 `<table className="fx-table">` 和 4 个 Modal。

- [ ] **Step 2: 添加 imports**

在文件顶部 import block 找到现有 import 列表，追加：

```tsx
import { useBreakpoint } from "../../lib/responsive";
import { PositionItemCard } from "./PositionItemCard";
```

- [ ] **Step 3: 在组件函数体内获取 isMobile**

找到 PositionsTable function 体内 `const [closeTarget, setCloseTarget] = useState<PositionItem | null>(null);` 这一行（line ~243），在其**前**插入：

```tsx
const { isMobile } = useBreakpoint();
```

- [ ] **Step 4: 在 return JSX 中加 isMobile 条件渲染**

找到现有 `<table className="fx-table">` 包裹的整段（line ~306-340 间），把它从：

```tsx
{items.length > 0 && (
  <table className="fx-table">
    <thead>...</thead>
    <tbody>
      {items.map((p) => (
        <PositionRow ... />
      ))}
    </tbody>
  </table>
)}
```

改成：

```tsx
{items.length > 0 && !isMobile && (
  <table className="fx-table">
    <thead>
      <tr>
        <th>持仓 ID</th>
        <th>品种</th>
        <th>方向</th>
        <th>数量</th>
        <th title="实际成交价（含 LP 报价 × 平台基准加点 + 用户组 markup 冻结值）">开仓价</th>
        <th title="公允中间价 (bid + ask) / 2，用于风险计算 / 强平判定 / PnL。不含用户组商业 markup —— 业界 OKX/Binance/MT5 同款口径。">
          标记价 <span style={{ opacity: 0.4, fontSize: 11 }}>ⓘ</span>
        </th>
        <th title="使用含 markup 的有效价（与开仓口径对齐）计算的浮动盈亏，反映价格变化而非 markup 差。">未实现盈亏</th>
        <th>净盈亏</th>
        <th>杠杆</th>
        <th>保证金</th>
        <th>强平价</th>
        <th>TP</th>
        <th>SL</th>
        <th>操作</th>
      </tr>
    </thead>
    <tbody>
      {items.map((p) => (
        <PositionRow
          key={p.positionId}
          position={p}
          patch={pnlMap?.get(p.positionId)}
          onDetail={setDetailTarget}
          onClose={setCloseTarget}
          onRisk={setRiskTarget}
          onMargin={setMarginTarget}
        />
      ))}
    </tbody>
  </table>
)}
{items.length > 0 && isMobile && (
  <ul className="fx-positions-cards" aria-label="持仓列表">
    {items.map((p) => (
      <PositionItemCard
        key={p.positionId}
        position={p}
        pnl={pnlMap?.get(p.positionId)}
        onDetail={setDetailTarget}
        onClose={setCloseTarget}
        onAddMargin={setMarginTarget}
        onEditRiskControls={setRiskTarget}
      />
    ))}
  </ul>
)}
```

**CRITICAL**: 桌面 `<table>` 分支（`!isMobile`）必须 byte-for-byte 跟原代码相同。如果实际现状代码中 `<thead>` 字段、tooltip 文字、或 `PositionRow` props 跟上面不一致，**保留实际现状**（不要改桌面行为），并在 DONE 报告中说明差异。

- [ ] **Step 5: typecheck**

Run: `cd falconx-frontend && npx tsc -p tsconfig.app.json --noEmit 2>&1 | head -20`
Expected: 0 type error

- [ ] **Step 6: build**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -10`
Expected: 0 error

- [ ] **Step 7: 跑完整测试套件**

Run: `cd falconx-frontend && npx vitest run 2>&1 | tail -20`
Expected: 全部 PASS（含 Task 3 新增 6 PositionItemCard 测试 + Phase 2A 9 测试 + Phase 0+1 23 测试 + 现有 ~60+ 测试 = ~97 PASS / 1 pre-existing fail）

- [ ] **Step 8: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b1
git branch --show-current
git add falconx-frontend/src/features/trading/PositionsTable.tsx
git commit -m "feat(h5-phase2b1-5): PositionsTable isMobile 条件渲染 mobile cards

接入 PositionItemCard:
- imports useBreakpoint + PositionItemCard
- isMobile 时渲染 <ul.fx-positions-cards> + <PositionItemCard /> × N
- 桌面分支 13 列 <table.fx-table> byte-identical 保留
- 4 个 modal triggers (closeTarget/riskTarget/marginTarget/detailTarget) 复用 setter

桌面 0 regression，mobile 持仓 list 现可用。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Final Verification + manual DevTools mobile preview

**Files:** 无文件改动，仅运行验证

- [ ] **Step 1: 完整测试套件**

Run: `cd falconx-frontend && npx vitest run 2>&1 | tail -15`
Expected: 新增 6 PositionItemCard 测试 PASS；总数 ~97 pass / 1 fail（pre-existing `WithdrawDrawer TC-WD-FE-002` 不变）；0 new failure

- [ ] **Step 2: lint**

Run: `cd falconx-frontend && npm run lint 2>&1 | tail -20`
Expected: H5 Phase 2B-1 新增 0 error 0 warning；pre-existing 5 problems 不变

- [ ] **Step 3: production build**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -10`
Expected: build 完成，0 error，CSS bundle 增量 < 6KB gzip

- [ ] **Step 4: TypeScript strict check**

Run: `cd falconx-frontend && npx tsc -p tsconfig.app.json --noEmit 2>&1 | tail -10`
Expected: 0 type errors

- [ ] **Step 5: 启 dev server + DevTools 验证**

Run:
```bash
cd falconx-frontend && npx vite --port 5173 &
```

等待启动，Chrome DevTools iPhone SE (375x667) 验证：

- [ ] 登录后 → market view → 点 OrderTicketFab → Sheet 弹出 OrderTicket
- [ ] OrderTicket orderType tabs (市价/限价/止损限) 等宽 segmented
- [ ] OrderTicket amount input 48px 高 / 16px 字（focus 不触发 iOS zoom）
- [ ] OrderTicket LONG/SHORT 切换 — 左红右绿正确（active 时 long border lime / short border red）
- [ ] OrderTicket TP/SL checkbox 24×24 可点
- [ ] OrderTicket submit (买入/卖出) 56px 高
- [ ] 切到 持仓 tab — 看到 PositionItemCard list（5 行 / 13 字段全）
- [ ] 点卡片整体 → OrderDetailModal 弹出（mobile 用 .fx-modal-mask 桌面样式，未优化 OK）
- [ ] 点卡片 [Ⓞ 平仓] → ClosePositionModal mobile 92vw 居中弹出 + 48px CTA
- [ ] 点卡片 [⋯ 更多] → menu 弹出 (补保 / 改 TP/SL)
- [ ] 点 [补保] → AddMarginModal mobile 弹出 + amount input 48px
- [ ] 点 [改 TP/SL] → EditRiskControlsModal mobile 弹出 + TP/SL inputs 48px

DevTools 1440 桌面切换：

- [ ] OrderTicket 桌面 grid 布局完整（非 mobile 单列）
- [ ] PositionsTable 显示 13 列 `<table>` 完整（非卡片）
- [ ] 3 modal 居中弹出（非 mobile 全宽）
- [ ] 0 regression

杀 dev server：
```bash
kill %1 2>/dev/null; pkill -f "vite --port 5173" 2>/dev/null; true
```

- [ ] **Step 6: Merge to main + push**

> ⚠️ **CRITICAL**: 此步骤只在 controller（不是 subagent）执行。

controller 流程：
```bash
cd /home/ives/code/FalconX
git checkout main
git merge --no-ff worktree-h5-phase2b1 -m "Merge worktree-h5-phase2b1: H5 自适应 Phase 2B-1 实施完成

trading 核心交易流 5 组件 mobile 适配 + 桌面 0 regression：
- _trading.css OrderTicket mobile (Task 1)
- _trading.css fx-modal 3 modal 共享 (Task 2)
- PositionItemCard 5 行卡片 + 6 测试 (Task 3)
- _trading.css PositionItemCard 样式 (Task 4)
- PositionsTable isMobile 条件渲染 (Task 5)

完成手机 \"下单 -> 看持仓 -> 平仓/加保证金/改 SL TP\" 闭环。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
git push origin main
```

---

## Self-Review Checklist

完成 Task 1-6 后 controller 自查：

- [ ] Spec coverage：5 组件全部对应 task — OrderTicket (Task 1) / 3 Modal (Task 2) / PositionItemCard 组件 (Task 3) / PositionItemCard 样式 (Task 4) / PositionsTable 集成 (Task 5) ✓
- [ ] Visual Design Notes 红线（mono / cyan / hairline / 48-56px touch / 16px input / long-short token）全部在 _trading.css 体现 ✓
- [ ] 新增 1 组件 6 单元测试 ✓
- [ ] 桌面 JSX byte-identical (Task 5 step 4) ✓
- [ ] Phase 0+1 + 2A 基础设施引用正确（useBreakpoint）✓
- [ ] 没有引入新 npm dep ✓

---

## Risk Mitigation

| 风险 | 缓解 |
|---|---|
| Task 5 桌面分支 JSX 漂移 | spec 强调 byte-identical，subagent 必须 verify 实际现状 |
| PositionItem 类型字段名实际跟 spec 不符 | Task 3 step 4 mention：subagent 必须读 tradingTypes.ts 实际定义调整 basePosition mock |
| PositionPnlUpdate 类型字段（unrealizedPnl / markPrice）不符 | Task 3 step 4 mention：subagent 必须读 useTradingSocket.ts 实际定义调整 patch object |
| Menu 弹出位置在小屏被遮挡 | Task 4 CSS 绝对定位 bottom 56px，方便看；如真机有问题再调 |
| OrderTicket 桌面 grid 布局可能用 `.order-ticket > div` 子选择器，mobile CSS 改 button height 可能误伤桌面 | _trading.css 所有规则在 `@media (max-width: 767px)` 内，桌面 @ ≥ 768px 0 影响 |
| Task 5 step 4 修改后 PositionRow 桌面分支跟原代码不字符串相同（如 tooltip 文字微差） | subagent 必须按现状保留桌面分支，不要按 plan 文本 overwrite — plan 是参考，现状是真源 |

---

## Done Definition

- [ ] 6 tasks 全部 commit 到 `worktree-h5-phase2b1` 分支
- [ ] 6 新单元测试全 PASS
- [ ] `npm run lint` H5 新增 regression = 0
- [ ] `npx vite build` 0 error，CSS 增量 < 6KB gzip
- [ ] Chrome DevTools iPhone SE 视觉验证全通过（Task 6 step 5）
- [ ] Chrome DevTools 1440 桌面 0 regression
- [ ] worktree merge 到 main 并 push origin
