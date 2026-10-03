# H5 自适应改造 — Phase 2B-2 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** trading 订单/成交历史 6 组件 mobile 适配（PendingOrdersTable / OrdersTable / TradesTable / ClosedPositionsTable + OrderDetailModal + TablePaginationFooter），桌面 0 regression，手机 Activity tab 4 Table 全部可用。

**Architecture:** 3 层：(1) `_trading.css` 同一 @media block 追加 `.fx-history-card`（4 card 共享外壳）+ OrderDetailModal mobile rules + Pagination mobile + 桌面 default for pagination (2) 4 个新 Card 组件（PendingOrder/Order/Trade/ClosedPosition），DOM 结构一致字段不同 (3) 4 个 Table 加 `useBreakpoint` 条件渲染，桌面 byte-identical。OrderDetailModal 0 JSX 改动，TablePaginationFooter inline style → className。

**Tech Stack:** React 19 + TypeScript + Vite + Vitest（已有）+ lucide-react（icon 用 `X`、`Eye`、`PenLine`、`Ban`）+ Phase 0+1/2A/2B-1 基础设施。

---

## File Structure

### Create 8 files

| 路径 | 职责 |
|---|---|
| `falconx-frontend/src/features/trading/PendingOrderCard.tsx` | mobile 挂单卡片（detail/edit/cancel 3 callback） |
| `falconx-frontend/src/features/trading/PendingOrderCard.test.tsx` | 5 测试 |
| `falconx-frontend/src/features/trading/OrderCard.tsx` | mobile 已成交/拒绝订单卡片（detail 1 callback） |
| `falconx-frontend/src/features/trading/OrderCard.test.tsx` | 4 测试 |
| `falconx-frontend/src/features/trading/TradeCard.tsx` | mobile 成交记录卡片（detail 1 callback） |
| `falconx-frontend/src/features/trading/TradeCard.test.tsx` | 4 测试 |
| `falconx-frontend/src/features/trading/ClosedPositionCard.tsx` | mobile 已平仓持仓卡片（detail 1 callback） |
| `falconx-frontend/src/features/trading/ClosedPositionCard.test.tsx` | 4 测试 |

### Modify 6 files

| 路径 | 改动 |
|---|---|
| `falconx-frontend/src/styles/modules/_trading.css` | 顶部加 `.fx-pagination-footer` 桌面默认；@media block 追加 history-card / OrderDetailModal mobile / pagination mobile rules |
| `falconx-frontend/src/features/trading/PendingOrdersTable.tsx` | useBreakpoint + isMobile 分支渲染 PendingOrderCard，桌面 byte-identical |
| `falconx-frontend/src/features/trading/OrdersTable.tsx` | 同上（OrderCard） |
| `falconx-frontend/src/features/trading/TradesTable.tsx` | 同上（TradeCard） |
| `falconx-frontend/src/features/trading/ClosedPositionsTable.tsx` | 同上（ClosedPositionCard） |
| `falconx-frontend/src/features/trading/TablePaginationFooter.tsx` | inline `style` → className `fx-pagination-footer`，逻辑不变 |

---

## Task 1: _trading.css 通用 .fx-history-card 外壳样式

**Files:**
- Modify: `falconx-frontend/src/styles/modules/_trading.css`（在 @media block 末尾追加 history-card rules）

- [ ] **Step 1: 读 _trading.css 末尾确认 @media block 结构**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
tail -10 falconx-frontend/src/styles/modules/_trading.css
grep -c "@media" falconx-frontend/src/styles/modules/_trading.css
```

Expected: `@media` count = 1（来自 Phase 2B-1）；末尾是 `.fx-position-card__menu button:active { ... }` 后跟 @media 闭合 `}`

- [ ] **Step 2: 在 @media block 末尾追加 history-card rules**

在 `.fx-position-card__menu button:active { ... }` 之后、@media `}` 闭合之前追加：

```css

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
    transition: background 80ms ease, transform 80ms ease;
  }

  .fx-history-card:active {
    background: rgba(84, 230, 255, 0.04);
    transform: scale(0.99);
  }

  .fx-history-card__row1 {
    display: flex;
    align-items: baseline;
    gap: var(--fx-space-2);
    font-size: 15px;
    letter-spacing: 0.02em;
  }

  .fx-history-card__symbol {
    font-weight: 600;
  }

  .fx-history-card__side {
    font-size: 12px;
    letter-spacing: var(--fx-eyebrow-letter);
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

  .fx-history-card__row2,
  .fx-history-card__row3 {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font-family: var(--fx-mono-stack);
    font-variant-numeric: tabular-nums;
    font-size: 13px;
    flex-wrap: wrap;
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

  .fx-history-card__arrow {
    color: var(--fx-muted);
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

  .fx-history-card__action-btn:active {
    background: rgba(84, 230, 255, 0.08);
  }

  .fx-history-card__action-btn--danger {
    border-color: var(--fx-short);
    color: var(--fx-short);
  }

  .fx-history-card__action-btn--danger:active {
    background: rgba(255, 99, 120, 0.08);
  }

  .fx-history-card__action-btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
```

- [ ] **Step 3: build 确认 + @media count**

Run:
```bash
cd falconx-frontend && npx vite build 2>&1 | tail -8
grep -c "@media" src/styles/modules/_trading.css
```

Expected: build 0 error；@media count 仍为 1

- [ ] **Step 4: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
git branch --show-current
# 必须输出 worktree-h5-phase2b2
git add falconx-frontend/src/styles/modules/_trading.css
git commit -m "feat(h5-phase2b2-1): _trading.css history-card 通用 mobile 外壳

mobile 历史卡片共享外壳（4 cards: Pending/Order/Trade/ClosedPosition 复用）:
- .fx-history-cards / .fx-history-card 卡片容器 + 卡片单元
- __row1/__row2/__row3 三行布局 (symbol/numeric/numeric)
- __footer time + actions hairline 分隔
- __action-btn / __action-btn--danger (cyan/red border)
- :active scale 0.99 + cyan rgba bg

复用 Phase 2B-1 PositionItemCard 的视觉语言（mono tabular + eyebrow + cyan accent）。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: _trading.css OrderDetailModal mobile rules

**Files:**
- Modify: `falconx-frontend/src/styles/modules/_trading.css`（在 @media block 末尾追加 detail modal rules）

OrderDetailModal 实际用的 className：`.fx-modal-mask` + `.fx-modal--detail` + `.fx-detail-header` + `.fx-detail-header__main/__eyebrow/__title/__subtitle` + `.fx-detail-close` + `.fx-detail-body` + `.fx-detail-section/__head/__num/__title/__rule` + `.fx-detail-grid/__row/__row--wide/__label/__value/__value--mono/__value--{emphasis}/__value--empty` + `.fx-detail-footer`。

- [ ] **Step 1: 在 @media block 末尾追加 OrderDetailModal mobile rules**

在 `.fx-history-card__action-btn:disabled { ... }`（Task 1 末尾）之后、@media `}` 闭合之前追加：

```css

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
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }

  .fx-detail-header {
    padding: 12px 16px;
    flex-shrink: 0;
  }

  .fx-detail-header__title {
    font-size: 18px;
  }

  .fx-detail-header__eyebrow {
    font-size: 10px;
    letter-spacing: var(--fx-eyebrow-letter);
  }

  .fx-detail-header__subtitle {
    font-size: 12px;
    color: var(--fx-muted);
  }

  .fx-detail-close {
    width: 32px;
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .fx-detail-body {
    flex: 1;
    overflow-y: auto;
  }

  .fx-detail-section {
    padding: 12px 16px;
  }

  .fx-detail-section__head {
    margin-bottom: 8px;
  }

  .fx-detail-section__title {
    font-size: 13px;
  }

  .fx-detail-grid {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .fx-detail-grid__row--wide {
    grid-column: 1;
  }

  .fx-detail-grid__label {
    font-size: 10px;
  }

  .fx-detail-grid__value {
    font-size: 14px;
  }

  .fx-detail-footer {
    padding: 12px 16px;
    flex-shrink: 0;
  }
```

- [ ] **Step 2: build 确认**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -5`
Expected: 0 error

- [ ] **Step 3: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
git add falconx-frontend/src/styles/modules/_trading.css
git commit -m "feat(h5-phase2b2-2): _trading.css OrderDetailModal mobile 适配

OrderDetailModal (.fx-modal-mask + .fx-modal--detail 变体) < 768px:
- mask safe-area padding
- modal 100% width max 600px + max-height 100dvh - safe-area
- header / body / footer 紧凑 padding
- close button 32x32 touch
- .fx-detail-grid 桌面 2 列 -> mobile 1 列
- .fx-detail-grid__row--wide grid-column 1 (mobile 不需要跨列)

0 JSX 改动，纯 CSS 改造。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: TablePaginationFooter className 重构 + _trading.css 默认 + mobile

**Files:**
- Modify: `falconx-frontend/src/features/trading/TablePaginationFooter.tsx`
- Modify: `falconx-frontend/src/styles/modules/_trading.css`

- [ ] **Step 1: 改写 TablePaginationFooter.tsx — inline style → className**

替换文件内容（保留 file 顶部 interface Props 注释，只改 return body）：

```tsx
interface Props {
  page: number;
  total: number;
  pageSize: number;
  /** 设为 true 时分页按钮 disabled（避免 fetch 中重复点）。 */
  fetching?: boolean;
  onChange: (next: number) => void;
}

/**
 * Activity 各 tab 表格底部的分页栏。统一文案 / 样式，避免每个 table 各写一份。
 * 总是渲染（即便 totalPages=1）—— 让用户始终能看到「共 N 条」，避免误以为无分页；
 * 单页时上一页 / 下一页按钮自动 disabled。fetching 时也 disabled 防抖动。
 */
export function TablePaginationFooter({ page, total, pageSize, fetching, onChange }: Props) {
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  return (
    <div className="fx-pagination-footer">
      <button
        type="button"
        className="fx-btn-secondary fx-btn-xs fx-pagination-footer__btn"
        disabled={page <= 1 || fetching}
        onClick={() => onChange(Math.max(1, page - 1))}
      >
        上一页
      </button>
      <span className="fx-mono fx-pagination-footer__page">
        {page} / {totalPages}
      </span>
      <button
        type="button"
        className="fx-btn-secondary fx-btn-xs fx-pagination-footer__btn"
        disabled={page >= totalPages || fetching}
        onClick={() => onChange(Math.min(totalPages, page + 1))}
      >
        下一页
      </button>
      <span className="fx-mono fx-pagination-footer__total">
        共 {total} 条
      </span>
    </div>
  );
}
```

- [ ] **Step 2: 在 _trading.css 顶部（@media 之外）加 pagination-footer 桌面默认 + mobile 规则**

读 `_trading.css` 现状（顶部应该是 `@media (max-width: 767px) {` 直接开始）。在文件**顶部**（首行 `/* FalconX H5 ... */` 注释后，`@media` 之前）插入：

```css
/* ============ TablePaginationFooter 桌面默认（无 media query） ============ */
.fx-pagination-footer {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 8px 0;
  justify-content: flex-end;
}

.fx-pagination-footer__page {
  font-size: 12px;
}

.fx-pagination-footer__total {
  font-size: 12px;
  color: var(--fx-subtle);
}

```

然后在 @media block 末尾（Task 2 的 `.fx-detail-footer` 之后、@media `}` 之前）追加 mobile rules：

```css

  /* ============ Pagination footer mobile ============ */
  .fx-pagination-footer {
    justify-content: center;
    padding: 12px 8px;
    gap: 8px;
  }

  .fx-pagination-footer__btn {
    min-height: 40px;
    padding: 0 12px;
    font-size: 13px;
  }

  .fx-pagination-footer__total {
    margin-left: 4px;
    font-size: 11px;
  }
```

- [ ] **Step 3: build 确认**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -5`
Expected: 0 error

- [ ] **Step 4: 跑测试套件确认无 regression**

Run: `cd falconx-frontend && npx vitest run 2>&1 | tail -10`
Expected: 全 PASS（无 TablePaginationFooter 自身的测试，但用它的 Tables 测试如果有应该不受影响）

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
git add falconx-frontend/src/features/trading/TablePaginationFooter.tsx \
        falconx-frontend/src/styles/modules/_trading.css
git commit -m "feat(h5-phase2b2-3): TablePaginationFooter className + mobile 加大 button

TablePaginationFooter:
- inline style object 改 className (.fx-pagination-footer + __btn + __page + __total)
- 逻辑 / props / 行为完全不变

_trading.css:
- 顶部加桌面默认 (.fx-pagination-footer flex right-justify 12px gap)
- @media block 加 mobile 规则:
  - footer center-justify
  - button 40px min-height + 13px font (touch friendly)
  - total label 缩到 11px

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: PendingOrderCard 组件 + 5 测试

**Files:**
- Create: `falconx-frontend/src/features/trading/PendingOrderCard.tsx`
- Create: `falconx-frontend/src/features/trading/PendingOrderCard.test.tsx`

- [ ] **Step 1: 写失败测试**

Create `falconx-frontend/src/features/trading/PendingOrderCard.test.tsx`:

```tsx
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PendingOrderCard } from "./PendingOrderCard";
import type { PendingOrderItem } from "./tradingTypes";

afterEach(() => cleanup());

const basePending: PendingOrderItem = {
  id: "pending-1",
  orderNo: "PO-2026-001",
  symbol: "BTCUSDT",
  orderType: "STOP_LIMIT",
  side: "BUY",
  quantity: "0.01",
  triggerPrice: "67000.00",
  limitPrice: "67200.00",
  leverage: "50",
  marginMode: "ISOLATED",
  frozenMargin: "13.40",
  frozenFee: "0.05",
  status: "PENDING",
  parentPositionId: null,
  triggerKind: null,
  clientOrderId: null,
  triggeredOrderId: null,
  triggeredAt: null,
  cancelledAt: null,
  cancelReason: null,
  createdAt: "2026-05-22T09:00:00Z",
  updatedAt: "2026-05-22T09:00:00Z",
};

describe("PendingOrderCard", () => {
  it("renders symbol, side, orderType in row 1", () => {
    render(
      <PendingOrderCard
        item={basePending}
        onDetail={() => {}}
        onEdit={() => {}}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("多")).toBeInTheDocument();
    expect(screen.getByText(/STOP_LIMIT/)).toBeInTheDocument();
  });

  it("renders trigger price, limit price, quantity, leverage in row 2", () => {
    render(
      <PendingOrderCard
        item={basePending}
        onDetail={() => {}}
        onEdit={() => {}}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    expect(screen.getByText("67000.00")).toBeInTheDocument();
    expect(screen.getByText("67200.00")).toBeInTheDocument();
    expect(screen.getByText("0.01")).toBeInTheDocument();
    expect(screen.getByText("50x")).toBeInTheDocument();
  });

  it("calls onDetail when card body is clicked", async () => {
    const onDetail = vi.fn();
    render(
      <PendingOrderCard
        item={basePending}
        onDetail={onDetail}
        onEdit={() => {}}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    await userEvent.click(screen.getByText("BTCUSDT"));
    expect(onDetail).toHaveBeenCalledWith(basePending);
  });

  it("calls onEdit and onCancel without triggering onDetail", async () => {
    const onDetail = vi.fn();
    const onEdit = vi.fn();
    const onCancel = vi.fn();
    render(
      <PendingOrderCard
        item={basePending}
        onDetail={onDetail}
        onEdit={onEdit}
        onCancel={onCancel}
        cancelDisabled={false}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /修改/ }));
    expect(onEdit).toHaveBeenCalledWith(basePending);
    expect(onDetail).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("button", { name: /撤销/ }));
    expect(onCancel).toHaveBeenCalledWith(basePending);
    expect(onDetail).not.toHaveBeenCalled();
  });

  it("hides edit/cancel buttons when status is not PENDING", () => {
    render(
      <PendingOrderCard
        item={{ ...basePending, status: "TRIGGERED" }}
        onDetail={() => {}}
        onEdit={() => {}}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    expect(screen.queryByRole("button", { name: /修改/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /撤销/ })).toBeNull();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd falconx-frontend && npx vitest run src/features/trading/PendingOrderCard.test.tsx`
Expected: FAIL `Cannot find module './PendingOrderCard'`

- [ ] **Step 3: 实现 PendingOrderCard**

Create `falconx-frontend/src/features/trading/PendingOrderCard.tsx`:

```tsx
import type { PendingOrderItem } from "./tradingTypes";

export interface PendingOrderCardProps {
  item: PendingOrderItem;
  onDetail: (item: PendingOrderItem) => void;
  onEdit: (item: PendingOrderItem) => void;
  onCancel: (item: PendingOrderItem) => void;
  cancelDisabled: boolean;
}

function formatTime(value: string | null): string {
  if (!value) return "—";
  return value.replace("T", " ").slice(0, 19);
}

export function PendingOrderCard({
  item,
  onDetail,
  onEdit,
  onCancel,
  cancelDisabled,
}: PendingOrderCardProps) {
  const sideLabel = item.side === "BUY" ? "多" : "空";
  const sideClass = item.side === "BUY" ? "fx-long" : "fx-short";
  const isPending = item.status === "PENDING";

  return (
    <li className="fx-history-card" onClick={() => onDetail(item)}>
      <div className="fx-history-card__row1">
        <strong className="fx-history-card__symbol">{item.symbol}</strong>
        <span className={`fx-history-card__side ${sideClass}`}>{sideLabel}</span>
        <span className="fx-history-card__type">{item.orderType}</span>
        <span className="fx-history-card__status">{item.status}</span>
      </div>
      <div className="fx-history-card__row2">
        <span className="fx-history-card__label">触发</span>
        <span>{item.triggerPrice}</span>
        <span className="fx-history-card__arrow">→</span>
        <span className="fx-history-card__label">限价</span>
        <span>{item.limitPrice ?? "—"}</span>
      </div>
      <div className="fx-history-card__row3">
        <span className="fx-history-card__label">数量</span>
        <span>{item.quantity}</span>
        <span className="fx-history-card__label">杠杆</span>
        <span>{item.leverage}x</span>
        <span className="fx-history-card__label">冻结</span>
        <span>{item.frozenMargin}</span>
      </div>
      <div className="fx-history-card__footer" onClick={(e) => e.stopPropagation()}>
        <span className="fx-history-card__time">{formatTime(item.createdAt)}</span>
        <div className="fx-history-card__actions">
          {isPending && (
            <>
              <button
                type="button"
                className="fx-history-card__action-btn"
                onClick={() => onEdit(item)}
              >
                修改
              </button>
              <button
                type="button"
                className="fx-history-card__action-btn fx-history-card__action-btn--danger"
                disabled={cancelDisabled}
                onClick={() => onCancel(item)}
              >
                撤销
              </button>
            </>
          )}
        </div>
      </div>
    </li>
  );
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd falconx-frontend && npx vitest run src/features/trading/PendingOrderCard.test.tsx`
Expected: PASS — 5 tests passed

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
git branch --show-current
git add falconx-frontend/src/features/trading/PendingOrderCard.tsx \
        falconx-frontend/src/features/trading/PendingOrderCard.test.tsx
git commit -m "feat(h5-phase2b2-4): PendingOrderCard mobile 挂单卡片 + 5 测试

mobile 挂单卡片:
- row1: symbol + 多/空 + orderType + status
- row2: 触发价 -> 限价
- row3: 数量 + 杠杆 + 冻结保证金
- footer: createdAt + 修改/撤销 (status === PENDING 才显示)
  - 修改: fx-history-card__action-btn
  - 撤销: fx-history-card__action-btn--danger
- 3 callback (onDetail/onEdit/onCancel)
- click body → onDetail, click action 不触发 detail (stopPropagation)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: PendingOrdersTable isMobile 集成

**Files:**
- Modify: `falconx-frontend/src/features/trading/PendingOrdersTable.tsx`

- [ ] **Step 1: 读 PendingOrdersTable.tsx 当前结构**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
sed -n '1,25p' falconx-frontend/src/features/trading/PendingOrdersTable.tsx
sed -n '130,200p' falconx-frontend/src/features/trading/PendingOrdersTable.tsx
```

Note current imports, where `setEditTarget` and `setDetailTarget` are declared, where `cancelMutation.mutate(o.id)` is called, the `<table>` and `<TablePaginationFooter>` JSX block.

- [ ] **Step 2: 添加 imports**

In import block at top of `PendingOrdersTable.tsx`, append:

```tsx
import { useBreakpoint } from "../../lib/responsive";
import { PendingOrderCard } from "./PendingOrderCard";
```

- [ ] **Step 3: 在 PendingOrdersTable function 内部加 isMobile**

In the PendingOrdersTable function body, find the line `const [editTarget, setEditTarget] = useState<PendingOrderItem | null>(null);`. Insert BEFORE it:

```tsx
  const { isMobile } = useBreakpoint();
```

- [ ] **Step 4: JSX 加 isMobile 条件渲染**

Find the existing `<table className="fx-table">...</table>` block (around lines 135-185). Replace ONLY that `<table>` element with conditional rendering. Keep all surrounding code (loading state, modals, TablePaginationFooter) unchanged.

The current code is:

```tsx
{items.length > 0 && (
  <>
    <table className="fx-table">
      <thead>...</thead>
      <tbody>...</tbody>
    </table>
    <TablePaginationFooter ... />
  </>
)}
```

Change to:

```tsx
{items.length > 0 && (
  <>
    {!isMobile && (
      <table className="fx-table">
        {/* EXACT current <thead> + <tbody> contents — byte-identical */}
      </table>
    )}
    {isMobile && (
      <ul className="fx-history-cards" aria-label="挂单列表">
        {items.map((o) => (
          <PendingOrderCard
            key={o.id}
            item={o}
            onDetail={setDetailTarget}
            onEdit={setEditTarget}
            onCancel={(item) => cancelMutation.mutate(item.id)}
            cancelDisabled={cancelMutation.isPending}
          />
        ))}
      </ul>
    )}
    <TablePaginationFooter ... />
  </>
)}
```

**CRITICAL**: The `<table>` content must be byte-identical to current code. The `TablePaginationFooter` element must stay OUTSIDE the conditional split (it should appear on both mobile and desktop).

- [ ] **Step 5: typecheck + build**

Run:
```bash
cd falconx-frontend && npx tsc -p tsconfig.app.json --noEmit 2>&1 | head -10
cd falconx-frontend && npx vite build 2>&1 | tail -5
```

Expected: 0 type errors, 0 build errors

- [ ] **Step 6: 跑测试套件**

Run: `cd falconx-frontend && npx vitest run 2>&1 | tail -10`
Expected: 全 PASS（PendingOrderCard 5 测试 + 现有套件无 regression）

- [ ] **Step 7: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
git add falconx-frontend/src/features/trading/PendingOrdersTable.tsx
git commit -m "feat(h5-phase2b2-5): PendingOrdersTable isMobile 条件渲染 mobile cards

- imports useBreakpoint + PendingOrderCard
- isMobile 时渲染 <ul.fx-history-cards> + <PendingOrderCard /> × N
- 桌面分支 13 列 <table.fx-table> byte-identical 保留
- onDetail = setDetailTarget; onEdit = setEditTarget
- onCancel = cancelMutation.mutate; cancelDisabled = mutation.isPending
- TablePaginationFooter 跨 mobile/desktop 共享

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: OrderCard 组件 + 4 测试

**Files:**
- Create: `falconx-frontend/src/features/trading/OrderCard.tsx`
- Create: `falconx-frontend/src/features/trading/OrderCard.test.tsx`

- [ ] **Step 1: 写失败测试**

Create `falconx-frontend/src/features/trading/OrderCard.test.tsx`:

```tsx
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { OrderCard } from "./OrderCard";
import type { OrderItem } from "./tradingTypes";

afterEach(() => cleanup());

const baseOrder: OrderItem = {
  orderId: "order-1",
  orderNo: "ORD-2026-001",
  symbol: "BTCUSDT",
  side: "BUY",
  orderType: "MARKET",
  quantity: "0.01",
  requestedPrice: null,
  filledPrice: "67432.50",
  leverage: "50",
  margin: "5.50",
  fee: "0.13",
  status: "FILLED",
  rejectReason: null,
  clientOrderId: "c-1",
  createdAt: "2026-05-22T09:00:00Z",
  updatedAt: "2026-05-22T09:00:01Z",
};

describe("OrderCard", () => {
  it("renders symbol, side, orderType, status in row 1", () => {
    render(<OrderCard item={baseOrder} onDetail={() => {}} />);
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("多")).toBeInTheDocument();
    expect(screen.getByText(/MARKET/)).toBeInTheDocument();
    expect(screen.getByText(/FILLED/)).toBeInTheDocument();
  });

  it("renders filled price, quantity, leverage in row 2", () => {
    render(<OrderCard item={baseOrder} onDetail={() => {}} />);
    expect(screen.getByText("67432.50")).toBeInTheDocument();
    expect(screen.getByText("0.01")).toBeInTheDocument();
    expect(screen.getByText("50x")).toBeInTheDocument();
  });

  it("applies fx-long class for BUY side", () => {
    const { container } = render(<OrderCard item={baseOrder} onDetail={() => {}} />);
    expect(container.querySelector(".fx-long")).not.toBeNull();
  });

  it("calls onDetail when card clicked", async () => {
    const onDetail = vi.fn();
    render(<OrderCard item={baseOrder} onDetail={onDetail} />);
    await userEvent.click(screen.getByText("BTCUSDT"));
    expect(onDetail).toHaveBeenCalledWith(baseOrder);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd falconx-frontend && npx vitest run src/features/trading/OrderCard.test.tsx`
Expected: FAIL `Cannot find module './OrderCard'`

- [ ] **Step 3: 实现 OrderCard**

Create `falconx-frontend/src/features/trading/OrderCard.tsx`:

```tsx
import type { OrderItem } from "./tradingTypes";

export interface OrderCardProps {
  item: OrderItem;
  onDetail: (item: OrderItem) => void;
}

function formatTime(value: string | null): string {
  if (!value) return "—";
  return value.replace("T", " ").slice(0, 19);
}

export function OrderCard({ item, onDetail }: OrderCardProps) {
  const sideLabel = item.side === "BUY" ? "多" : "空";
  const sideClass = item.side === "BUY" ? "fx-long" : "fx-short";
  const priceLabel = item.filledPrice ?? item.requestedPrice ?? "—";

  return (
    <li className="fx-history-card" onClick={() => onDetail(item)}>
      <div className="fx-history-card__row1">
        <strong className="fx-history-card__symbol">{item.symbol}</strong>
        <span className={`fx-history-card__side ${sideClass}`}>{sideLabel}</span>
        <span className="fx-history-card__type">{item.orderType}</span>
        <span className="fx-history-card__status">{item.status}</span>
      </div>
      <div className="fx-history-card__row2">
        <span className="fx-history-card__label">价</span>
        <span>{priceLabel}</span>
        <span className="fx-history-card__label">量</span>
        <span>{item.quantity}</span>
        <span className="fx-history-card__label">杠杆</span>
        <span>{item.leverage}x</span>
      </div>
      <div className="fx-history-card__row3">
        <span className="fx-history-card__label">保证金</span>
        <span>{item.margin}</span>
        <span className="fx-history-card__label">手续费</span>
        <span>{item.fee}</span>
        {item.rejectReason && (
          <>
            <span className="fx-history-card__label">原因</span>
            <span className="fx-short">{item.rejectReason}</span>
          </>
        )}
      </div>
      <div className="fx-history-card__footer" onClick={(e) => e.stopPropagation()}>
        <span className="fx-history-card__time">{formatTime(item.createdAt)}</span>
        <div className="fx-history-card__actions">
          <button
            type="button"
            className="fx-history-card__action-btn"
            onClick={() => onDetail(item)}
          >
            详情
          </button>
        </div>
      </div>
    </li>
  );
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd falconx-frontend && npx vitest run src/features/trading/OrderCard.test.tsx`
Expected: PASS — 4 tests passed

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
git add falconx-frontend/src/features/trading/OrderCard.tsx \
        falconx-frontend/src/features/trading/OrderCard.test.tsx
git commit -m "feat(h5-phase2b2-6): OrderCard mobile 已成交/拒绝订单卡片 + 4 测试

mobile 订单卡片:
- row1: symbol + 多/空 + orderType + status
- row2: 价(filled or requested) + 量 + 杠杆
- row3: 保证金 + 手续费 + (rejectReason 如有)
- footer: createdAt + 详情 button
- 1 callback (onDetail)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: OrdersTable isMobile 集成

**Files:**
- Modify: `falconx-frontend/src/features/trading/OrdersTable.tsx`

- [ ] **Step 1: 读 OrdersTable.tsx 当前结构**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
sed -n '1,25p' falconx-frontend/src/features/trading/OrdersTable.tsx
sed -n '105,170p' falconx-frontend/src/features/trading/OrdersTable.tsx
```

Note: current imports, `setDetailTarget` state location, `<table>` + `<TablePaginationFooter>` JSX block.

- [ ] **Step 2: 添加 imports**

Append to import block:

```tsx
import { useBreakpoint } from "../../lib/responsive";
import { OrderCard } from "./OrderCard";
```

- [ ] **Step 3: 在组件函数体加 isMobile**

In OrdersTable function body, find `const [detailTarget, setDetailTarget] = useState<OrderItem | null>(null);` (or similar useState declaration). Insert BEFORE the first useState:

```tsx
  const { isMobile } = useBreakpoint();
```

- [ ] **Step 4: JSX 加 isMobile 条件渲染**

Find the existing `<table className="fx-table">...</table>` block. Replace ONLY that `<table>` element with conditional rendering. Keep `<TablePaginationFooter>` and modals unchanged.

```tsx
{items.length > 0 && (
  <>
    {!isMobile && (
      <table className="fx-table">
        {/* EXACT current <thead> + <tbody> — byte-identical */}
      </table>
    )}
    {isMobile && (
      <ul className="fx-history-cards" aria-label="订单列表">
        {items.map((o) => (
          <OrderCard
            key={o.orderId}
            item={o}
            onDetail={setDetailTarget}
          />
        ))}
      </ul>
    )}
    <TablePaginationFooter ... />
  </>
)}
```

**CRITICAL**: byte-identical desktop branch. TablePaginationFooter outside split.

- [ ] **Step 5: typecheck + build**

Run:
```bash
cd falconx-frontend && npx tsc -p tsconfig.app.json --noEmit 2>&1 | head -10
cd falconx-frontend && npx vite build 2>&1 | tail -5
```

Expected: 0 errors

- [ ] **Step 6: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
git add falconx-frontend/src/features/trading/OrdersTable.tsx
git commit -m "feat(h5-phase2b2-7): OrdersTable isMobile 条件渲染 mobile cards

- imports useBreakpoint + OrderCard
- isMobile 时渲染 <ul.fx-history-cards> + <OrderCard /> × N
- 桌面分支 13 列 <table.fx-table> byte-identical
- onDetail 复用 setDetailTarget setter

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: TradeCard 组件 + 4 测试

**Files:**
- Create: `falconx-frontend/src/features/trading/TradeCard.tsx`
- Create: `falconx-frontend/src/features/trading/TradeCard.test.tsx`

- [ ] **Step 1: 写失败测试**

Create `falconx-frontend/src/features/trading/TradeCard.test.tsx`:

```tsx
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { TradeCard } from "./TradeCard";
import type { TradeItem } from "./tradingTypes";

afterEach(() => cleanup());

const baseTrade: TradeItem = {
  tradeId: "trade-1",
  orderId: "order-1",
  positionId: "pos-1",
  symbol: "BTCUSDT",
  side: "BUY",
  tradeType: "OPEN",
  quantity: "0.01",
  price: "67432.50",
  fee: "0.13",
  realizedPnl: null,
  tradedAt: "2026-05-22T09:00:00Z",
};

describe("TradeCard", () => {
  it("renders symbol, side, tradeType in row 1", () => {
    render(<TradeCard item={baseTrade} onDetail={() => {}} />);
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("多")).toBeInTheDocument();
    expect(screen.getByText(/OPEN/)).toBeInTheDocument();
  });

  it("renders price, quantity, fee in row 2", () => {
    render(<TradeCard item={baseTrade} onDetail={() => {}} />);
    expect(screen.getByText("67432.50")).toBeInTheDocument();
    expect(screen.getByText("0.01")).toBeInTheDocument();
    expect(screen.getByText("0.13")).toBeInTheDocument();
  });

  it("applies fx-long for positive realizedPnl, fx-short for negative", () => {
    const positive = { ...baseTrade, realizedPnl: "25.50" };
    const negative = { ...baseTrade, realizedPnl: "-12.30" };

    const { container: c1 } = render(<TradeCard item={positive} onDetail={() => {}} />);
    expect(c1.querySelector(".fx-long")).not.toBeNull();

    cleanup();

    const { container: c2 } = render(<TradeCard item={negative} onDetail={() => {}} />);
    expect(c2.querySelector(".fx-short")).not.toBeNull();
  });

  it("calls onDetail when card clicked", async () => {
    const onDetail = vi.fn();
    render(<TradeCard item={baseTrade} onDetail={onDetail} />);
    await userEvent.click(screen.getByText("BTCUSDT"));
    expect(onDetail).toHaveBeenCalledWith(baseTrade);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd falconx-frontend && npx vitest run src/features/trading/TradeCard.test.tsx`
Expected: FAIL module not found

- [ ] **Step 3: 实现 TradeCard**

Create `falconx-frontend/src/features/trading/TradeCard.tsx`:

```tsx
import type { TradeItem } from "./tradingTypes";

export interface TradeCardProps {
  item: TradeItem;
  onDetail: (item: TradeItem) => void;
}

function formatTime(value: string | null): string {
  if (!value) return "—";
  return value.replace("T", " ").slice(0, 19);
}

function pnlClass(value: string | null): string {
  if (value == null) return "";
  const n = Number(value);
  if (!Number.isFinite(n)) return "";
  return n > 0 ? "fx-long" : n < 0 ? "fx-short" : "";
}

export function TradeCard({ item, onDetail }: TradeCardProps) {
  const sideLabel = item.side === "BUY" ? "多" : "空";
  const sideClass = item.side === "BUY" ? "fx-long" : "fx-short";

  return (
    <li className="fx-history-card" onClick={() => onDetail(item)}>
      <div className="fx-history-card__row1">
        <strong className="fx-history-card__symbol">{item.symbol}</strong>
        <span className={`fx-history-card__side ${sideClass}`}>{sideLabel}</span>
        <span className="fx-history-card__type">{item.tradeType}</span>
      </div>
      <div className="fx-history-card__row2">
        <span className="fx-history-card__label">价</span>
        <span>{item.price}</span>
        <span className="fx-history-card__label">量</span>
        <span>{item.quantity}</span>
        <span className="fx-history-card__label">费</span>
        <span>{item.fee}</span>
      </div>
      <div className="fx-history-card__row3">
        <span className="fx-history-card__label">已实现盈亏</span>
        <span className={pnlClass(item.realizedPnl)}>{item.realizedPnl ?? "—"}</span>
      </div>
      <div className="fx-history-card__footer" onClick={(e) => e.stopPropagation()}>
        <span className="fx-history-card__time">{formatTime(item.tradedAt)}</span>
        <div className="fx-history-card__actions">
          <button
            type="button"
            className="fx-history-card__action-btn"
            onClick={() => onDetail(item)}
          >
            详情
          </button>
        </div>
      </div>
    </li>
  );
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd falconx-frontend && npx vitest run src/features/trading/TradeCard.test.tsx`
Expected: PASS — 4 tests passed

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
git add falconx-frontend/src/features/trading/TradeCard.tsx \
        falconx-frontend/src/features/trading/TradeCard.test.tsx
git commit -m "feat(h5-phase2b2-8): TradeCard mobile 成交记录卡片 + 4 测试

mobile 成交卡片:
- row1: symbol + 多/空 + tradeType (OPEN/CLOSE)
- row2: 价 + 量 + 费
- row3: 已实现盈亏 (fx-long/short 涨跌色)
- footer: tradedAt + 详情
- 1 callback (onDetail)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 9: TradesTable isMobile 集成

**Files:**
- Modify: `falconx-frontend/src/features/trading/TradesTable.tsx`

- [ ] **Step 1: 读 TradesTable.tsx 当前结构**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
sed -n '1,25p' falconx-frontend/src/features/trading/TradesTable.tsx
sed -n '85,140p' falconx-frontend/src/features/trading/TradesTable.tsx
```

- [ ] **Step 2: 添加 imports**

```tsx
import { useBreakpoint } from "../../lib/responsive";
import { TradeCard } from "./TradeCard";
```

- [ ] **Step 3: 加 isMobile + 条件渲染**

In TradesTable function body, add `const { isMobile } = useBreakpoint();` before first useState (likely `[detailTarget, setDetailTarget]`).

Find existing `<table className="fx-table">...</table>` block and replace ONLY the `<table>` with:

```tsx
{!isMobile && (
  <table className="fx-table">
    {/* EXACT current <thead> + <tbody> — byte-identical */}
  </table>
)}
{isMobile && (
  <ul className="fx-history-cards" aria-label="成交列表">
    {items.map((t) => (
      <TradeCard
        key={t.tradeId}
        item={t}
        onDetail={setDetailTarget}
      />
    ))}
  </ul>
)}
```

**CRITICAL**: byte-identical desktop branch.

- [ ] **Step 4: typecheck + build**

Run:
```bash
cd falconx-frontend && npx tsc -p tsconfig.app.json --noEmit 2>&1 | head -10
cd falconx-frontend && npx vite build 2>&1 | tail -5
```

Expected: 0 errors

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
git add falconx-frontend/src/features/trading/TradesTable.tsx
git commit -m "feat(h5-phase2b2-9): TradesTable isMobile 条件渲染 mobile cards

- imports useBreakpoint + TradeCard
- isMobile 时渲染 <ul.fx-history-cards> + <TradeCard /> × N
- 桌面分支 10 列 <table.fx-table> byte-identical
- onDetail 复用 setDetailTarget setter

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 10: ClosedPositionCard 组件 + 4 测试

**Files:**
- Create: `falconx-frontend/src/features/trading/ClosedPositionCard.tsx`
- Create: `falconx-frontend/src/features/trading/ClosedPositionCard.test.tsx`

- [ ] **Step 1: 写失败测试**

Create `falconx-frontend/src/features/trading/ClosedPositionCard.test.tsx`:

```tsx
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ClosedPositionCard } from "./ClosedPositionCard";
import type { PositionItem } from "./tradingTypes";

afterEach(() => cleanup());

const baseClosed: PositionItem = {
  positionId: "pos-closed-1",
  openingOrderId: "ord-1",
  symbol: "BTCUSDT",
  side: "BUY",
  quantity: "0.01",
  entryPrice: "67432.50",
  leverage: "50",
  margin: "5.50",
  marginMode: "ISOLATED",
  liquidationPrice: null,
  takeProfitPrice: null,
  stopLossPrice: null,
  markPrice: null,
  unrealizedPnl: null,
  closePrice: "67500.00",
  closeReason: "USER_CLOSE",
  realizedPnl: "12.30",
  status: "CLOSED",
  quoteStale: null,
  quoteTs: null,
  quoteSource: null,
  openedAt: "2026-05-22T09:00:00Z",
  closedAt: "2026-05-22T10:00:00Z",
  updatedAt: "2026-05-22T10:00:00Z",
  openFee: "0.13",
  openFeeRate: "0.0002",
  groupCodeAtOpen: "GROUP-1",
  bidExtraAtOpen: "0.0001",
  askExtraAtOpen: "0.0001",
  effectiveMarkPrice: "67450.00",
};

describe("ClosedPositionCard", () => {
  it("renders symbol, side, quantity, leverage in row 1", () => {
    render(<ClosedPositionCard item={baseClosed} onDetail={() => {}} />);
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("多")).toBeInTheDocument();
    expect(screen.getByText("0.01")).toBeInTheDocument();
    expect(screen.getByText("50x")).toBeInTheDocument();
  });

  it("renders entry price → close price in row 2", () => {
    render(<ClosedPositionCard item={baseClosed} onDetail={() => {}} />);
    expect(screen.getByText("67432.50")).toBeInTheDocument();
    expect(screen.getByText("67500.00")).toBeInTheDocument();
  });

  it("applies fx-long class for positive realizedPnl", () => {
    const { container } = render(<ClosedPositionCard item={baseClosed} onDetail={() => {}} />);
    const pnlEl = container.querySelector(".fx-long");
    expect(pnlEl).not.toBeNull();
  });

  it("calls onDetail when card clicked", async () => {
    const onDetail = vi.fn();
    render(<ClosedPositionCard item={baseClosed} onDetail={onDetail} />);
    await userEvent.click(screen.getByText("BTCUSDT"));
    expect(onDetail).toHaveBeenCalledWith(baseClosed);
  });
});
```

⚠️ If PositionItem requires additional fields beyond those above (e.g. via tradingTypes.ts later), append them to `baseClosed` mock with sensible nulls/strings to satisfy the strict type.

- [ ] **Step 2: 跑测试确认失败**

Run: `cd falconx-frontend && npx vitest run src/features/trading/ClosedPositionCard.test.tsx`
Expected: FAIL module not found

- [ ] **Step 3: 实现 ClosedPositionCard**

Create `falconx-frontend/src/features/trading/ClosedPositionCard.tsx`:

```tsx
import type { PositionItem } from "./tradingTypes";

export interface ClosedPositionCardProps {
  item: PositionItem;
  onDetail: (item: PositionItem) => void;
}

function formatTime(value: string | null): string {
  if (!value) return "—";
  return value.replace("T", " ").slice(0, 19);
}

function pnlClass(value: string | null): string {
  if (value == null) return "";
  const n = Number(value);
  if (!Number.isFinite(n)) return "";
  return n > 0 ? "fx-long" : n < 0 ? "fx-short" : "";
}

export function ClosedPositionCard({ item, onDetail }: ClosedPositionCardProps) {
  const sideLabel = item.side === "BUY" ? "多" : "空";
  const sideClass = item.side === "BUY" ? "fx-long" : "fx-short";

  return (
    <li className="fx-history-card" onClick={() => onDetail(item)}>
      <div className="fx-history-card__row1">
        <strong className="fx-history-card__symbol">{item.symbol}</strong>
        <span className={`fx-history-card__side ${sideClass}`}>{sideLabel}</span>
        <span className="fx-history-card__qty">{item.quantity}</span>
        <span className="fx-history-card__type">{item.leverage}x</span>
      </div>
      <div className="fx-history-card__row2">
        <span className="fx-history-card__label">开</span>
        <span>{item.entryPrice}</span>
        <span className="fx-history-card__arrow">→</span>
        <span className="fx-history-card__label">平</span>
        <span>{item.closePrice ?? "—"}</span>
      </div>
      <div className="fx-history-card__row3">
        <span className="fx-history-card__label">已实现</span>
        <span className={pnlClass(item.realizedPnl)}>{item.realizedPnl ?? "—"}</span>
        {item.closeReason && (
          <>
            <span className="fx-history-card__label">原因</span>
            <span>{item.closeReason}</span>
          </>
        )}
      </div>
      <div className="fx-history-card__footer" onClick={(e) => e.stopPropagation()}>
        <span className="fx-history-card__time">
          {formatTime(item.closedAt) || formatTime(item.openedAt)}
        </span>
        <div className="fx-history-card__actions">
          <button
            type="button"
            className="fx-history-card__action-btn"
            onClick={() => onDetail(item)}
          >
            详情
          </button>
        </div>
      </div>
    </li>
  );
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd falconx-frontend && npx vitest run src/features/trading/ClosedPositionCard.test.tsx`
Expected: PASS — 4 tests passed

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
git add falconx-frontend/src/features/trading/ClosedPositionCard.tsx \
        falconx-frontend/src/features/trading/ClosedPositionCard.test.tsx
git commit -m "feat(h5-phase2b2-10): ClosedPositionCard mobile 已平仓卡片 + 4 测试

mobile 已平仓卡片:
- row1: symbol + 多/空 + 数量 + 杠杆
- row2: 开 -> 平 (entry -> close price)
- row3: 已实现盈亏 (fx-long/short 涨跌色) + closeReason
- footer: closedAt + 详情
- 1 callback (onDetail)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 11: ClosedPositionsTable isMobile 集成

**Files:**
- Modify: `falconx-frontend/src/features/trading/ClosedPositionsTable.tsx`

- [ ] **Step 1: 读 ClosedPositionsTable.tsx 当前结构**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
sed -n '1,25p' falconx-frontend/src/features/trading/ClosedPositionsTable.tsx
sed -n '120,180p' falconx-frontend/src/features/trading/ClosedPositionsTable.tsx
```

- [ ] **Step 2: 添加 imports**

```tsx
import { useBreakpoint } from "../../lib/responsive";
import { ClosedPositionCard } from "./ClosedPositionCard";
```

- [ ] **Step 3: 加 isMobile + 条件渲染**

Add `const { isMobile } = useBreakpoint();` before the first useState in function body.

Replace existing `<table className="fx-table">...</table>` block with:

```tsx
{!isMobile && (
  <table className="fx-table">
    {/* EXACT current <thead> + <tbody> — byte-identical */}
  </table>
)}
{isMobile && (
  <ul className="fx-history-cards" aria-label="已平仓列表">
    {items.map((p) => (
      <ClosedPositionCard
        key={p.positionId}
        item={p}
        onDetail={setDetailTarget}
      />
    ))}
  </ul>
)}
```

**CRITICAL**: byte-identical desktop branch.

- [ ] **Step 4: typecheck + build**

Run:
```bash
cd falconx-frontend && npx tsc -p tsconfig.app.json --noEmit 2>&1 | head -10
cd falconx-frontend && npx vite build 2>&1 | tail -5
```

Expected: 0 errors

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b2
git add falconx-frontend/src/features/trading/ClosedPositionsTable.tsx
git commit -m "feat(h5-phase2b2-11): ClosedPositionsTable isMobile 条件渲染 mobile cards

- imports useBreakpoint + ClosedPositionCard
- isMobile 时渲染 <ul.fx-history-cards> + <ClosedPositionCard /> × N
- 桌面分支 12 列 <table.fx-table> byte-identical
- onDetail 复用 setDetailTarget setter

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 12: Final Verification + manual DevTools mobile preview

**Files:** 无文件改动，仅运行验证

- [ ] **Step 1: 完整测试套件**

Run: `cd falconx-frontend && npx vitest run 2>&1 | tail -15`
Expected: 全 PASS（新增 17 测试：PendingOrder 5 + Order 4 + Trade 4 + ClosedPosition 4 = 17；现有 ~97 测试无 regression；1 pre-existing `WithdrawDrawer TC-WD-FE-002` 不变）

- [ ] **Step 2: lint**

Run: `cd falconx-frontend && npm run lint 2>&1 | tail -20`
Expected: 5 problems (3 errors / 2 warnings) — pre-existing baseline 不变

- [ ] **Step 3: production build**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -10`
Expected: 0 error；CSS bundle 增量 < 8KB gzip

- [ ] **Step 4: TypeScript strict check**

Run: `cd falconx-frontend && npx tsc -p tsconfig.app.json --noEmit 2>&1 | tail -10`
Expected: 0 type errors

- [ ] **Step 5: DevTools mobile preview**

Run: `cd falconx-frontend && npx vite --port 5173 &`

Chrome DevTools iPhone SE (375x667) 验证：

- [ ] Activity tab 切到 Pending Orders → 卡片列表（symbol/方向/orderType/status 一行 + 触发价/限价一行 + 数量/杠杆/冻结一行）
- [ ] Pending 卡片 status === PENDING 时显示 "修改" + "撤销"（red border），"撤销" mutate 期间 disabled
- [ ] 点 Pending 卡片 body → OrderDetailModal mobile 弹出
- [ ] 切 Orders → OrderCard 列表，价/量/杠杆/保证金/手续费/原因 显示
- [ ] 切 Trades → TradeCard 列表，已实现盈亏 涨跌色
- [ ] 切 Closed Positions → ClosedPositionCard 列表，开→平 / 已实现盈亏 / 平仓原因
- [ ] 4 个 Card 点 body 或 "详情" 按钮都触发 OrderDetailModal
- [ ] OrderDetailModal mobile：mask + safe-area，title 18px，sections 1 列 grid
- [ ] TablePaginationFooter mobile：center-justify + button 40px

DevTools 1440 桌面：

- [ ] 4 Tables 桌面 13/13/10/12 列 byte-identical
- [ ] OrderDetailModal 桌面 2 列 grid 保留
- [ ] TablePaginationFooter 桌面 right-justify 12px gap 保留
- [ ] 0 regression

杀 dev server：
```bash
kill %1 2>/dev/null; pkill -f "vite --port 5173" 2>/dev/null; true
```

- [ ] **Step 6: Merge to main + push (controller only, NOT subagent)**

> ⚠️ **CRITICAL**: 此 step 只在 controller 执行。

```bash
cd /home/ives/code/FalconX
git checkout main
git merge --no-ff worktree-h5-phase2b2 -m "Merge worktree-h5-phase2b2: H5 自适应 Phase 2B-2 实施完成

trading 历史 6 组件 mobile 适配 + 桌面 byte-identical 0 regression:
- _trading.css history-card 通用外壳 (Task 1)
- _trading.css OrderDetailModal mobile (Task 2)
- TablePaginationFooter className + mobile (Task 3)
- 4 Card 组件: PendingOrder/Order/Trade/ClosedPosition (Task 4/6/8/10)
- 4 Tables isMobile 条件渲染 (Task 5/7/9/11)

完成手机 Activity tab 全部历史可看（挂单/订单/成交/已平仓）+ 挂单可改可撤销。
验证: ~17 新测试 / 0 lint regression / 0 typecheck / 0 build error

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
git push origin main
```

---

## Self-Review Checklist

完成 Task 1-12 后 controller 自查：

- [ ] Spec coverage：6 组件全部对应 task — 4 Card 各 1 组件 task + 1 集成 task = 8 / OrderDetailModal CSS (Task 2) / Pagination (Task 3) / 通用外壳 (Task 1) ✓
- [ ] Visual Design Notes 红线（mono / cyan / hairline / 40-48px touch / 16px input / long-short token）在 _trading.css 体现 ✓
- [ ] 4 新增 Card 组件各 4-5 单元测试，共 ~17 ✓
- [ ] 4 个 Table 桌面 JSX byte-identical (Task 5/7/9/11 严格 copy-paste) ✓
- [ ] OrderDetailModal 0 JSX 改动 ✓
- [ ] TablePaginationFooter JSX 只改 className 不改逻辑 ✓
- [ ] Phase 0+1 / 2A / 2B-1 基础设施引用正确（useBreakpoint）✓
- [ ] 没有引入新 npm dep ✓

---

## Risk Mitigation

| 风险 | 缓解 |
|---|---|
| 4 个 Table 桌面分支 JSX 漂移 regression | subagent 必须 read actual current code，byte-identical copy；plan 留 sed 命令引导 |
| OrderItem / TradeItem / PendingOrderItem / PositionItem 类型字段名跟 plan mock 不符 | subagent 必须 grep tradingTypes.ts 检查；类型错误时调整 mock 不动 component |
| ClosedPositionsTable 跟 PositionsTable 共用 PositionItem 类型 — 字段相同但 status=CLOSED 过滤 | ClosedPositionCard mock 加齐 PositionItem 所有字段（同 Phase 2B-1 fix b06cc90） |
| OrderDetailModal 实际 CSS selector 跟 plan 不一致 | plan 用 `.fx-detail-grid` 是从 OrderDetailModal.tsx 实际 grep 确认的；subagent 不需要再确认 |
| TablePaginationFooter 桌面默认样式从 inline style 改 className 后视觉漂移 | Task 3 _trading.css 顶部添加桌面默认（@media 外），保证桌面 `display:flex; gap:12px; justify-content:flex-end` |
| status === PENDING 文本 enum 不一致（实际可能是 "PENDING" string literal） | PendingOrderItem.status 类型 PendingOrderStatus；plan 测试断言 toEqual 字面值 — subagent 按实际 enum 调整 |

---

## Done Definition

- [ ] 12 tasks 全部 commit 到 `worktree-h5-phase2b2` 分支
- [ ] ~17 新单元测试全 PASS
- [ ] `npm run lint` H5 新增 regression = 0
- [ ] `npx vite build` 0 error，CSS 增量 < 8KB gzip
- [ ] Chrome DevTools iPhone SE 视觉验证全通过（Task 12 step 5）
- [ ] Chrome DevTools 1440 桌面 0 regression
- [ ] worktree merge 到 main 并 push origin
