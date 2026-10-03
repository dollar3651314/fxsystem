# H5 自适应改造 — Phase 2C 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** wallet feature 3 组件 mobile 适配（WalletPage / LedgerCard / WithdrawDrawer modal），桌面 0 regression。

**Architecture:** 新增 `_wallet.css` module 收 wallet/withdraw mobile rules。WalletPage 加 `isMobile` 分支 ledger table → LedgerCard。WithdrawDrawer (实际是 `.fx-modal--wide` modal 含 withdraw-tabs) 0 JSX 改动，纯 CSS。

**Tech Stack:** React 19 + TypeScript + Vite + Vitest + lucide-react + Phase 0+1/2A/2B 基础设施。

---

## File Structure

### Create

- `falconx-frontend/src/styles/modules/_wallet.css`
- `falconx-frontend/src/features/wallet/LedgerCard.tsx`
- `falconx-frontend/src/features/wallet/LedgerCard.test.tsx`

### Modify

- `falconx-frontend/src/styles/global.css` — @import _wallet.css after _trading.css
- `falconx-frontend/src/features/wallet/WalletPage.tsx` — useBreakpoint + LedgerCard

---

## Task 1: _wallet.css 新建 + global.css @import + WalletPage / WithdrawDrawer mobile rules + LedgerCard CSS

**Files:**
- Create: `falconx-frontend/src/styles/modules/_wallet.css`
- Modify: `falconx-frontend/src/styles/global.css`

- [ ] **Step 1: 创建 _wallet.css 含全部 mobile rules**

Create `falconx-frontend/src/styles/modules/_wallet.css`:

```css
/* FalconX H5 — wallet 钱包 / withdraw 出金 mobile 适配
 * 桌面 (≥ 768px) 保持现有样式不变；mobile (< 768px) 改紧凑 + 卡片 + drawer 100vw。
 */

@media (max-width: 767px) {
  /* ============ WalletPage console layout ============ */
  .fx-console-page {
    padding: 0;
  }

  .fx-console-header {
    padding: 12px 16px;
    gap: 12px;
  }

  .fx-console-header__row {
    flex-wrap: wrap;
    gap: 8px;
  }

  .fx-console-route {
    font-size: 13px;
    flex-wrap: wrap;
  }

  .fx-console-meta {
    width: 100%;
    display: flex;
    gap: 8px;
  }

  .fx-console-meta .fx-ghost-btn,
  .fx-console-meta .fx-ghost-btn--primary {
    flex: 1;
    min-height: 40px;
    justify-content: center;
  }

  .fx-console-page__body {
    padding: 0 16px;
  }

  .fx-console-section {
    padding: 16px 0;
  }

  .fx-console-section__title {
    font-size: 16px;
  }

  /* ============ Ledger filter row (mobile stack) ============ */
  .ledger-filter {
    flex-direction: column;
    gap: 8px;
    align-items: stretch;
  }

  .ledger-filter__field {
    width: 100%;
  }

  .ledger-filter__select,
  .ledger-filter__input {
    width: 100%;
    min-height: 44px;
    font-size: 16px;
  }

  /* ============ Wallet ledger cards (mobile only) ============ */
  .wallet-ledger-cards {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  .wallet-ledger-card {
    padding: 12px 0;
    border-bottom: 1px solid var(--fx-hairline);
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .wallet-ledger-card__row1 {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
    font-family: var(--fx-mono-stack);
    font-variant-numeric: tabular-nums;
    font-size: 14px;
  }

  .wallet-ledger-card__type {
    font-size: 11px;
    letter-spacing: var(--fx-eyebrow-letter);
    text-transform: uppercase;
    color: var(--fx-muted);
    font-family: Inter, ui-sans-serif, system-ui;
  }

  .wallet-ledger-card__amount {
    font-weight: 600;
  }

  .wallet-ledger-card__row2 {
    display: flex;
    align-items: baseline;
    gap: 8px;
    font-size: 11px;
    color: var(--fx-muted);
    flex-wrap: wrap;
    font-family: var(--fx-mono-stack);
  }

  .wallet-ledger-card__time {
    color: var(--fx-muted);
  }

  .wallet-ledger-card__ref {
    color: var(--fx-subtle);
  }

  .wallet-ledger-card__balance {
    margin-left: auto;
    color: var(--fx-text);
  }

  /* ============ WithdrawDrawer modal mobile (.fx-modal--wide variant) ============ */
  .fx-modal-mask--withdraw {
    padding: env(safe-area-inset-top) 0 env(safe-area-inset-bottom);
  }

  .fx-modal--wide {
    width: 100vw;
    max-width: 100vw;
    max-height: calc(100vh - env(safe-area-inset-top) - env(safe-area-inset-bottom));
    max-height: calc(100dvh - env(safe-area-inset-top) - env(safe-area-inset-bottom));
    border-radius: 0;
    display: flex;
    flex-direction: column;
  }

  .fx-modal-title--row {
    padding: 12px 16px;
    flex-wrap: wrap;
    gap: 8px;
  }

  .withdraw-tabs {
    overflow-x: auto;
    scrollbar-width: none;
    flex-wrap: nowrap;
  }

  .withdraw-tabs::-webkit-scrollbar {
    display: none;
  }

  .withdraw-tab {
    flex-shrink: 0;
    padding: 8px 12px;
    font-size: 13px;
  }

  .withdraw-tab__num {
    display: none;
  }

  /* WithdrawDrawer form inputs */
  .fx-modal--wide input,
  .fx-modal--wide select,
  .fx-modal--wide textarea {
    min-height: 48px;
    font-size: 16px;
  }

  .fx-modal--wide textarea {
    min-height: 80px;
  }

  /* WithdrawDrawer 内部 tables 横滚（暂不抽卡片 YAGNI） */
  .fx-modal--wide table.fx-table {
    display: block;
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
    white-space: nowrap;
  }
}
```

- [ ] **Step 2: global.css @import**

Read top 6 lines:
```bash
head -8 falconx-frontend/src/styles/global.css
```

Add after `@import "./modules/_trading.css";`:

```css
@import "./modules/_wallet.css";
```

- [ ] **Step 3: build verify**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -5`
Expected: 0 error

- [ ] **Step 4: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2c
git branch --show-current
git add falconx-frontend/src/styles/modules/_wallet.css \
        falconx-frontend/src/styles/global.css
git commit -m "feat(h5-phase2c-1): _wallet.css 新建 + WalletPage/WithdrawDrawer mobile rules

mobile (< 768px):
- WalletPage: fx-console-* layout 紧凑 + meta button 全宽
- ledger-filter row: vertical stack + select/input 44px + font 16px
- wallet-ledger-cards/__card: mobile 资金流水卡片样式
- WithdrawDrawer (.fx-modal--wide): 100vw + safe-area + withdraw-tabs 横滚 + 隐 __num
- WithdrawDrawer form inputs 48px + 16px font
- WithdrawDrawer 内部 fx-table 横滚 (history table 不抽卡片 YAGNI)

桌面 0 改动。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: LedgerCard 组件 + 4 测试

**Files:**
- Create: `falconx-frontend/src/features/wallet/LedgerCard.tsx`
- Create: `falconx-frontend/src/features/wallet/LedgerCard.test.tsx`

- [ ] **Step 1: 写失败测试**

Create `falconx-frontend/src/features/wallet/LedgerCard.test.tsx`:

```tsx
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { LedgerCard } from "./LedgerCard";
import type { LedgerEntry } from "./walletApi";

afterEach(() => cleanup());

const baseEntry: LedgerEntry = {
  ledgerId: "led-1",
  bizType: "DEPOSIT_CREDIT",
  amount: "100.00",
  idempotencyKey: null,
  referenceNo: "ref-001",
  balanceBefore: "1000.00",
  balanceAfter: "1100.00",
  frozenBefore: "0",
  frozenAfter: "0",
  marginUsedBefore: "0",
  marginUsedAfter: "0",
  createdAt: "2026-05-22T09:00:00Z",
};

describe("LedgerCard", () => {
  it("renders typeLabel + amount in row 1", () => {
    render(<LedgerCard item={baseEntry} typeLabel="入金到账" />);
    expect(screen.getByText("入金到账")).toBeInTheDocument();
    expect(screen.getByText(/100\.00/)).toBeInTheDocument();
  });

  it("renders createdAt + referenceNo + balanceAfter in row 2", () => {
    render(<LedgerCard item={baseEntry} typeLabel="入金到账" />);
    expect(screen.getByText(/2026-05-22 09:00:00/)).toBeInTheDocument();
    expect(screen.getByText(/ref-001/)).toBeInTheDocument();
    expect(screen.getByText(/1100\.00/)).toBeInTheDocument();
  });

  it("applies fx-long class for positive amount", () => {
    const { container } = render(<LedgerCard item={baseEntry} typeLabel="入金到账" />);
    expect(container.querySelector(".fx-long")).not.toBeNull();
  });

  it("applies fx-short class for negative amount", () => {
    const neg = { ...baseEntry, amount: "-50.00" };
    const { container } = render(<LedgerCard item={neg} typeLabel="提现" />);
    expect(container.querySelector(".fx-short")).not.toBeNull();
  });
});
```

- [ ] **Step 2: Verify test fails**

Run: `cd falconx-frontend && npx vitest run src/features/wallet/LedgerCard.test.tsx`
Expected: FAIL module not found

- [ ] **Step 3: 实现 LedgerCard**

Create `falconx-frontend/src/features/wallet/LedgerCard.tsx`:

```tsx
import type { LedgerEntry } from "./walletApi";

export interface LedgerCardProps {
  item: LedgerEntry;
  typeLabel: string;
}

function formatTime(value: string): string {
  return value.replace("T", " ").slice(0, 19);
}

function amountClass(value: string): string {
  const n = Number(value);
  if (!Number.isFinite(n)) return "";
  return n > 0 ? "fx-long" : n < 0 ? "fx-short" : "";
}

export function LedgerCard({ item, typeLabel }: LedgerCardProps) {
  return (
    <li className="wallet-ledger-card">
      <div className="wallet-ledger-card__row1">
        <span className="wallet-ledger-card__type">{typeLabel}</span>
        <span className={`wallet-ledger-card__amount ${amountClass(item.amount)}`}>
          {item.amount}
        </span>
      </div>
      <div className="wallet-ledger-card__row2">
        <span className="wallet-ledger-card__time">{formatTime(item.createdAt)}</span>
        {item.referenceNo && (
          <span className="wallet-ledger-card__ref">{item.referenceNo}</span>
        )}
        <span className="wallet-ledger-card__balance">余 {item.balanceAfter}</span>
      </div>
    </li>
  );
}
```

- [ ] **Step 4: Verify tests pass**

Run: `cd falconx-frontend && npx vitest run src/features/wallet/LedgerCard.test.tsx`
Expected: PASS — 4 tests

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2c
git branch --show-current
git add falconx-frontend/src/features/wallet/LedgerCard.tsx \
        falconx-frontend/src/features/wallet/LedgerCard.test.tsx
git commit -m "feat(h5-phase2c-2): LedgerCard mobile 资金流水卡片 + 4 测试

mobile 资金流水卡片:
- row1: typeLabel + amount (fx-long/short 涨跌色)
- row2: createdAt + referenceNo (如有) + balanceAfter
- 字段 mono tabular，eyebrow 11px

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: WalletPage isMobile 集成

**Files:**
- Modify: `falconx-frontend/src/features/wallet/WalletPage.tsx`

- [ ] **Step 1: 读 WalletPage ledger 渲染位置**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2c
sed -n '1,20p' falconx-frontend/src/features/wallet/WalletPage.tsx
grep -n "fx-table\|LedgerEntry\|<table" falconx-frontend/src/features/wallet/WalletPage.tsx | head -10
sed -n '335,400p' falconx-frontend/src/features/wallet/WalletPage.tsx
```

Note: ledger rendering location (likely a child component or inline), state setup, items source.

- [ ] **Step 2: 添加 imports**

Append to import block:

```tsx
import { useBreakpoint } from "../../lib/responsive";
import { LedgerCard } from "./LedgerCard";
```

- [ ] **Step 3: 加 isMobile 在 ledger 渲染组件**

Find the function that renders the ledger table (could be inline or a sub-component like `LedgerList`). Add `const { isMobile } = useBreakpoint();` to function body and wrap `<table>` block:

```tsx
{!isMobile && (
  <table className="fx-table">
    {/* EXACT current <thead> + <tbody> — byte-identical */}
  </table>
)}
{isMobile && (
  <ul className="wallet-ledger-cards" aria-label="资金流水列表">
    {items.map((entry) => (
      <LedgerCard
        key={entry.ledgerId}
        item={entry}
        typeLabel={LEDGER_BIZ_TYPE_LABEL[entry.bizType] ?? entry.bizType}
      />
    ))}
  </ul>
)}
```

**CRITICAL**: byte-identical desktop `<table>`. If ledger rendering is inside a sub-component (e.g., LedgerList function), apply changes there.

- [ ] **Step 4: typecheck + build + test**

```bash
cd falconx-frontend && npx tsc -p tsconfig.app.json --noEmit 2>&1 | head -10
cd falconx-frontend && npx vite build 2>&1 | tail -5
cd falconx-frontend && npx vitest run 2>&1 | tail -10
```

Expected: 0 errors, all tests pass

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2c
git add falconx-frontend/src/features/wallet/WalletPage.tsx
git commit -m "feat(h5-phase2c-3): WalletPage isMobile 集成 LedgerCard

- imports useBreakpoint + LedgerCard
- isMobile 时渲染 <ul.wallet-ledger-cards> + <LedgerCard /> × N
- 桌面分支 5 列 ledger fx-table byte-identical 保留
- typeLabel = LEDGER_BIZ_TYPE_LABEL[bizType] ?? bizType

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: Final verification + Merge

- [ ] **Step 1: 完整验证**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2c/falconx-frontend
npx vitest run 2>&1 | tail -5
npm run lint 2>&1 | tail -8
npx vite build 2>&1 | tail -5
npx tsc -p tsconfig.app.json --noEmit 2>&1 | tail -3
```

Expected: 全 PASS / 5 lint baseline / 0 build / 0 typecheck error

- [ ] **Step 2: Merge to main (controller only)**

```bash
cd /home/ives/code/FalconX
git checkout main
git merge --no-ff worktree-h5-phase2c -m "Merge worktree-h5-phase2c: H5 自适应 Phase 2C 完成

wallet 3 组件 mobile 适配 + 桌面 byte-identical:
- _wallet.css 新建 (WalletPage console/ledger filter/cards + WithdrawDrawer modal)
- LedgerCard + 4 测试
- WalletPage isMobile 集成

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
git push origin main
```
