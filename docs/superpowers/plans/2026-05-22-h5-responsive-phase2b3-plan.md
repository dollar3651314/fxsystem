# H5 自适应改造 — Phase 2B-3 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** trading 通知/预警/Tab 收尾 3 组件 mobile 适配（TradingTabs / NotificationCenter / PriceAlertsTable），桌面 0 regression，完成后 trading feature 全量 mobile 交付。

**Architecture:** _trading.css 同一 @media block 末尾追加 (1) `.fx-tab-*` mobile 横滚 (2) `.notification-*` mobile drawer 100vw (3) `.fx-modal-title` mobile (4) PriceAlertCard 共享 `.fx-history-card` 外壳。PriceAlertsTable 加 `isMobile` 分支条件渲染（桌面 byte-identical）。TradingTabs / NotificationCenter 0 JSX 改动。

**Tech Stack:** React 19 + TypeScript + Vite + Vitest（已有）+ lucide-react（icon: `ArrowUp` / `ArrowDown` / `X`）+ Phase 0+1/2A/2B-1/2B-2 基础设施。

---

## File Structure

### Create 2 files

| 路径 | 职责 |
|---|---|
| `falconx-frontend/src/features/trading/PriceAlertCard.tsx` | mobile 价格告警卡片（onCancel callback） |
| `falconx-frontend/src/features/trading/PriceAlertCard.test.tsx` | 5 测试 |

### Modify 2 files

| 路径 | 改动 |
|---|---|
| `falconx-frontend/src/styles/modules/_trading.css` | @media block 末尾追加 TradingTabs + NotificationCenter + PriceAlertCard + create-modal-title mobile rules |
| `falconx-frontend/src/features/trading/PriceAlertsTable.tsx` | useBreakpoint + isMobile 分支渲染 PriceAlertCard，桌面 byte-identical |

---

## Task 1: _trading.css TradingTabs mobile 横滚

**Files:**
- Modify: `falconx-frontend/src/styles/modules/_trading.css` (在 @media block 末尾追加)

- [ ] **Step 1: 读 _trading.css 末尾确认 @media block 闭合状态**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b3
tail -10 falconx-frontend/src/styles/modules/_trading.css
grep -c "@media" falconx-frontend/src/styles/modules/_trading.css
```

Expected: `@media` count = 1（来自前序 phase）；末尾是 `.fx-pagination-footer__total { ... }` 后跟 @media 闭合 `}`

- [ ] **Step 2: 在 @media block 末尾追加 TradingTabs mobile rules**

在 `.fx-pagination-footer__total { ... }` 之后、@media `}` 闭合之前追加：

```css

  /* ============ TradingTabs mobile (6 tab 横滚 + 隐 __num) ============ */
  .fx-tab-bar {
    overflow-x: auto;
    overflow-y: hidden;
    scroll-snap-type: x proximity;
    flex-wrap: nowrap;
    -webkit-overflow-scrolling: touch;
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

  .fx-tab-button__num {
    display: none;
  }
```

- [ ] **Step 3: build 确认 + @media count**

Run:
```bash
cd falconx-frontend && npx vite build 2>&1 | tail -5
grep -c "@media" src/styles/modules/_trading.css
```

Expected: 0 error；@media count still 1

- [ ] **Step 4: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b3
git branch --show-current
# 必须输出 worktree-h5-phase2b3
git add falconx-frontend/src/styles/modules/_trading.css
git commit -m "feat(h5-phase2b3-1): _trading.css TradingTabs mobile 横滚 + 隐 __num

TradingTabs < 768px:
- fx-tab-bar overflow-x auto + scroll-snap (touch friendly)
- fx-tab-button flex-shrink 0 + scroll-snap-align start
- 隐藏 fx-tab-button__num 节省横向空间
- iOS -webkit-overflow-scrolling touch
- 隐藏滚动条 (scrollbar-width none)

桌面 0 改动。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: _trading.css NotificationCenter mobile drawer 100vw

**Files:**
- Modify: `falconx-frontend/src/styles/modules/_trading.css` (在 @media block 末尾追加)

- [ ] **Step 1: 在 @media block 末尾追加 NotificationCenter mobile rules**

在 Task 1 `.fx-tab-button__num { display: none; }` 之后、@media `}` 闭合之前追加：

```css

  /* ============ NotificationCenter mobile (drawer 100vw + safe-area) ============ */
  .notification-drawer {
    width: 100vw;
    max-width: 100vw;
    padding-top: env(safe-area-inset-top);
    padding-bottom: env(safe-area-inset-bottom);
  }

  .notification-drawer__header {
    padding: 12px 16px;
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
    overflow-y: auto;
  }

  .notification-item {
    padding: 12px 16px;
  }

  .notification-item__row {
    gap: 8px;
    align-items: center;
  }

  .notification-item__level {
    font-size: 10px;
    letter-spacing: var(--fx-eyebrow-letter);
    text-transform: uppercase;
    padding: 2px 6px;
  }

  .notification-item__title {
    font-size: 14px;
  }

  .notification-item__body {
    font-size: 12px;
    line-height: 1.5;
  }
```

- [ ] **Step 2: build 确认**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -5`
Expected: 0 error

- [ ] **Step 3: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b3
git add falconx-frontend/src/styles/modules/_trading.css
git commit -m "feat(h5-phase2b3-2): _trading.css NotificationCenter mobile drawer 100vw

NotificationCenter < 768px:
- drawer width 100vw + safe-area padding
- header / body 紧凑 padding
- header h3 16px
- close button 32x32 touch
- item padding 12 16 + level eyebrow 10px
- title 14px / body 12px line-height 1.5

0 JSX 改动，level 颜色保留桌面定义。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: _trading.css fx-modal-title mobile (PriceAlerts create modal)

**Files:**
- Modify: `falconx-frontend/src/styles/modules/_trading.css` (在 @media block 末尾追加)

- [ ] **Step 1: 在 @media block 末尾追加 fx-modal-title rule**

在 Task 2 `.notification-item__body { ... }` 之后、@media `}` 闭合之前追加：

```css

  /* ============ PriceAlerts create modal title (variant) ============ */
  .fx-modal-title {
    padding: 12px 16px;
    font-size: 16px;
    border-bottom: 1px solid var(--fx-hairline);
  }
```

- [ ] **Step 2: build 确认**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -5`
Expected: 0 error

- [ ] **Step 3: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b3
git add falconx-frontend/src/styles/modules/_trading.css
git commit -m "feat(h5-phase2b3-3): _trading.css fx-modal-title mobile (PriceAlerts create modal)

PriceAlerts 创建告警 modal 用 .fx-modal-title 变体（不同于 Phase 2B-1
的 .fx-modal-header h3 也不同于 OrderDetailModal 的 .fx-detail-header）。

mobile 补 12/16 padding + 16px font + hairline border-bottom。
.fx-modal-mask 和 .fx-modal 已有 mobile 规则 (Phase 2B-1/2B-2)，不重复。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: PriceAlertCard 组件 + 5 测试

**Files:**
- Create: `falconx-frontend/src/features/trading/PriceAlertCard.tsx`
- Create: `falconx-frontend/src/features/trading/PriceAlertCard.test.tsx`

- [ ] **Step 1: 写失败测试**

Create `falconx-frontend/src/features/trading/PriceAlertCard.test.tsx`:

```tsx
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PriceAlertCard } from "./PriceAlertCard";
import type { PriceAlertItem } from "./tradingTypes";

afterEach(() => cleanup());

const baseAlert: PriceAlertItem = {
  id: "alert-1",
  symbol: "BTCUSDT",
  direction: "ABOVE",
  targetPrice: "68000.00",
  status: "ACTIVE",
  note: "BTC 突破",
  basePrice: "67432.50",
  triggerCount: 1,
  remainingTriggers: 2,
  lastTriggeredAt: "2026-05-22T09:00:00Z",
  lastTriggeredPrice: "68010.00",
  cancelledAt: null,
  cancelSource: null,
  createdAt: "2026-05-22T08:00:00Z",
  updatedAt: "2026-05-22T09:00:00Z",
};

describe("PriceAlertCard", () => {
  it("renders symbol, direction (上穿) and status in row 1", () => {
    render(
      <PriceAlertCard
        item={baseAlert}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText(/上穿/)).toBeInTheDocument();
    expect(screen.getByText(/ACTIVE/)).toBeInTheDocument();
  });

  it("renders 下穿 label and fx-short class for BELOW direction", () => {
    const below = { ...baseAlert, direction: "BELOW" as const };
    const { container } = render(
      <PriceAlertCard item={below} onCancel={() => {}} cancelDisabled={false} />
    );
    expect(screen.getByText(/下穿/)).toBeInTheDocument();
    expect(container.querySelector(".fx-short")).not.toBeNull();
  });

  it("renders targetPrice + basePrice + trigger counts", () => {
    render(
      <PriceAlertCard
        item={baseAlert}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    expect(screen.getByText("68000.00")).toBeInTheDocument();
    expect(screen.getByText("67432.50")).toBeInTheDocument();
    expect(screen.getByText(/1.*3/)).toBeInTheDocument();
  });

  it("calls onCancel when cancel clicked (status ACTIVE)", async () => {
    const onCancel = vi.fn();
    render(
      <PriceAlertCard
        item={baseAlert}
        onCancel={onCancel}
        cancelDisabled={false}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /取消/ }));
    expect(onCancel).toHaveBeenCalledWith(baseAlert);
  });

  it("hides cancel button when status is not ACTIVE", () => {
    render(
      <PriceAlertCard
        item={{ ...baseAlert, status: "EXHAUSTED" }}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    expect(screen.queryByRole("button", { name: /取消/ })).toBeNull();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd falconx-frontend && npx vitest run src/features/trading/PriceAlertCard.test.tsx`
Expected: FAIL `Cannot find module './PriceAlertCard'`

- [ ] **Step 3: 实现 PriceAlertCard**

Create `falconx-frontend/src/features/trading/PriceAlertCard.tsx`:

```tsx
import { ArrowDown, ArrowUp } from "lucide-react";
import type { PriceAlertItem } from "./tradingTypes";

export interface PriceAlertCardProps {
  item: PriceAlertItem;
  onCancel: (item: PriceAlertItem) => void;
  cancelDisabled: boolean;
}

function formatTime(value: string | null): string {
  if (!value) return "—";
  return value.replace("T", " ").slice(0, 19);
}

export function PriceAlertCard({ item, onCancel, cancelDisabled }: PriceAlertCardProps) {
  const isAbove = item.direction === "ABOVE";
  const directionLabel = isAbove ? "上穿" : "下穿";
  const directionClass = isAbove ? "fx-long" : "fx-short";
  const DirectionIcon = isAbove ? ArrowUp : ArrowDown;
  const isActive = item.status === "ACTIVE";

  return (
    <li className="fx-history-card">
      <div className="fx-history-card__row1">
        <strong className="fx-history-card__symbol">{item.symbol}</strong>
        <span className={`fx-history-card__side ${directionClass}`}>
          <DirectionIcon size={12} strokeWidth={2.2} aria-hidden="true" />
          {directionLabel}
        </span>
        <span className="fx-history-card__status">{item.status}</span>
      </div>
      <div className="fx-history-card__row2">
        <span className="fx-history-card__label">触发</span>
        <span>{item.targetPrice}</span>
        <span className="fx-history-card__label">基准</span>
        <span>{item.basePrice ?? "—"}</span>
      </div>
      <div className="fx-history-card__row3">
        <span className="fx-history-card__label">触发</span>
        <span>{item.triggerCount} / 3</span>
        <span className="fx-history-card__label">剩</span>
        <span>{item.remainingTriggers}</span>
        <span className="fx-history-card__label">最近</span>
        <span>{formatTime(item.lastTriggeredAt)}</span>
      </div>
      {item.note && (
        <div className="fx-history-card__note">「{item.note}」</div>
      )}
      <div className="fx-history-card__footer">
        <span className="fx-history-card__time">{formatTime(item.createdAt)}</span>
        <div className="fx-history-card__actions">
          {isActive && (
            <button
              type="button"
              className="fx-history-card__action-btn fx-history-card__action-btn--danger"
              disabled={cancelDisabled}
              onClick={() => onCancel(item)}
            >
              取消
            </button>
          )}
        </div>
      </div>
    </li>
  );
}
```

- [ ] **Step 4: 在 _trading.css 加 `.fx-history-card__note` 规则**

PriceAlertCard 用了一个新 className `.fx-history-card__note`（其他 history cards 没有）。在 _trading.css @media block 末尾追加：

```css

  /* PriceAlertCard 备注（note）专用 row（其他 history cards 不用）*/
  .fx-history-card__note {
    font-size: 12px;
    color: var(--fx-muted);
    font-style: italic;
    padding: 4px 0;
  }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd falconx-frontend && npx vitest run src/features/trading/PriceAlertCard.test.tsx`
Expected: PASS — 5 tests passed

- [ ] **Step 6: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b3
git branch --show-current
git add falconx-frontend/src/features/trading/PriceAlertCard.tsx \
        falconx-frontend/src/features/trading/PriceAlertCard.test.tsx \
        falconx-frontend/src/styles/modules/_trading.css
git commit -m "feat(h5-phase2b3-4): PriceAlertCard mobile 价格告警卡片 + 5 测试

mobile 价格告警卡片:
- row1: symbol + 方向(▲上穿/▼下穿 fx-long/fx-short) + status
- row2: 触发价 + 基准价 (mono tabular)
- row3: 触发次数 + 剩余 + 最近触发
- note (有则显示): 灰色斜体备注
- footer: createdAt + 取消(danger, 仅 ACTIVE 显示)
- onCancel callback (PriceAlertItem 参数)
- click body 不触发 detail (PriceAlerts 无 detail modal)

+ _trading.css 加 .fx-history-card__note 规则。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: PriceAlertsTable isMobile 集成

**Files:**
- Modify: `falconx-frontend/src/features/trading/PriceAlertsTable.tsx`

- [ ] **Step 1: 读 PriceAlertsTable.tsx 当前结构**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b3
sed -n '1,30p' falconx-frontend/src/features/trading/PriceAlertsTable.tsx
sed -n '40,110p' falconx-frontend/src/features/trading/PriceAlertsTable.tsx
```

Note: imports, where `cancelMutation` is declared, where `items` is from query, the `<table className="fx-table">` block (byte-identical reference).

- [ ] **Step 2: 添加 imports**

Append to import block:

```tsx
import { useBreakpoint } from "../../lib/responsive";
import { PriceAlertCard } from "./PriceAlertCard";
```

- [ ] **Step 3: 加 isMobile**

In PriceAlertsTable function body, find first useState. Insert BEFORE:

```tsx
  const { isMobile } = useBreakpoint();
```

- [ ] **Step 4: JSX 加 isMobile 条件渲染**

Find the existing `<table className="fx-table">...</table>` block (wrapped by `{!query.isLoading && items.length > 0 && (...)}`). Replace ONLY the `<table>` element with conditional rendering:

```tsx
{!query.isLoading && items.length > 0 && !isMobile && (
  <table className="fx-table">
    {/* EXACT current <thead> + <tbody> — byte-identical */}
  </table>
)}
{!query.isLoading && items.length > 0 && isMobile && (
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
```

**CRITICAL**: byte-identical desktop branch. Keep all other JSX (顶部 新建告警 button + loading / empty 状态 + create modal) untouched.

- [ ] **Step 5: typecheck + build**

Run:
```bash
cd falconx-frontend && npx tsc -p tsconfig.app.json --noEmit 2>&1 | head -10
cd falconx-frontend && npx vite build 2>&1 | tail -5
```

Expected: 0 errors

- [ ] **Step 6: 跑测试套件**

Run: `cd falconx-frontend && npx vitest run 2>&1 | tail -10`
Expected: 全 PASS（新 5 测试 + 现有 114 测试 + 1 pre-existing fail）

- [ ] **Step 7: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2b3
git branch --show-current
git add falconx-frontend/src/features/trading/PriceAlertsTable.tsx
git commit -m "feat(h5-phase2b3-5): PriceAlertsTable isMobile 条件渲染 mobile cards

- imports useBreakpoint + PriceAlertCard
- isMobile 时渲染 <ul.fx-history-cards> + <PriceAlertCard /> × N
- 桌面分支 11 列 <table.fx-table> byte-identical
- onCancel = (item) => cancelMutation.mutate(item.id)
- cancelDisabled = cancelMutation.isPending
- 顶部 新建告警 button + create modal 保留

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Final Verification + Merge

**Files:** 无文件改动，仅运行验证 + merge

- [ ] **Step 1: 完整测试套件**

Run: `cd falconx-frontend && npx vitest run 2>&1 | tail -15`
Expected: 全 PASS（PriceAlertCard 5 新测试 + 现有 ~114 + 1 pre-existing fail）

- [ ] **Step 2: lint**

Run: `cd falconx-frontend && npm run lint 2>&1 | tail -20`
Expected: 5 problems (3 errors / 2 warnings) pre-existing baseline 不变

- [ ] **Step 3: production build**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -10`
Expected: 0 error；CSS 增量 < 3KB gzip

- [ ] **Step 4: TypeScript strict**

Run: `cd falconx-frontend && npx tsc -p tsconfig.app.json --noEmit 2>&1 | tail -10`
Expected: 0 type errors

- [ ] **Step 5: Merge to main (controller only, NOT subagent)**

```bash
cd /home/ives/code/FalconX
git checkout main
git merge --no-ff worktree-h5-phase2b3 -m "Merge worktree-h5-phase2b3: H5 自适应 Phase 2B-3 实施完成

trading 通知/预警/Tab 收尾 3 组件 mobile + 桌面 byte-identical:
- _trading.css TradingTabs mobile 横滚 + 隐 __num
- _trading.css NotificationCenter mobile drawer 100vw
- _trading.css fx-modal-title (PriceAlerts create modal)
- PriceAlertCard + 5 测试
- PriceAlertsTable isMobile 集成

完成后 trading feature 全量 mobile 交付（13 组件总计）。
验证: 5 新测试 / 0 lint regression / 0 typecheck / 0 build error

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
git push origin main
```

---

## Done Definition

- [ ] 5 tasks 全部 commit 到 worktree-h5-phase2b3
- [ ] 5 新测试 PASS
- [ ] lint 0 regression
- [ ] build 0 error
- [ ] worktree merge 到 main 并 push
