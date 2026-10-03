# H5 自适应改造 — Phase 2A 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 客户端 4 个 P0 feature（terminal market 视图 / auth / market watchlist / market chart）落地 mobile 自适应，桌面 0 regression。复用 Phase 0+1 基础设施（useBreakpoint / Sheet / Drawer / MobileShell），新增 2 个 mobile 专用组件（TerminalDrawer 抽取 + OrderTicketFab 新建），新增 2 个 CSS 模块（`_auth.css` + `_market.css`）承载 mobile @media rules。

**Architecture:** 3 层叠加：(1) Phase 0+1 基础设施 (2) 各 feature 组件级 mobile CSS @media @ 768px (3) TradingTerminal market 视图 mobile stack 重排（watchlist + chart + tabs + OrderTicketFab FAB → Sheet 弹 OrderTicket）。所有 mobile 改造遵循 spec Visual Design Notes 红线：JetBrains Mono / tabular-nums / cyan accent / hairline geometry / 无 material ripple。

**Tech Stack:** React 19 + TypeScript + Vite + Vitest（已有）+ lucide-react（已有）+ lightweight-charts 触摸（库自带）+ matchMedia API（Phase 0+1 已封装 useBreakpoint）

---

## File Structure

### 新建 6 个文件

| 路径 | 职责 |
|---|---|
| `falconx-frontend/src/features/terminal/TerminalDrawer.tsx` | TradingTerminal inline drawer 抽出为命名组件 |
| `falconx-frontend/src/features/terminal/TerminalDrawer.test.tsx` | 渲染 3 按钮 + onClick callback 测试 |
| `falconx-frontend/src/components/mobile/OrderTicketFab.tsx` | "下单"FAB（信息条 + CTA 一体），点击打开 OrderTicket Sheet |
| `falconx-frontend/src/components/mobile/OrderTicketFab.test.tsx` | 渲染 / onClick / disabled 测试 |
| `falconx-frontend/src/styles/modules/_auth.css` | auth-gate / auth-form mobile @media rules |
| `falconx-frontend/src/styles/modules/_market.css` | market-list / market-chart / market view stack / order-ticket-fab mobile @media rules |

### 修改 4 个文件

| 路径 | 改动 |
|---|---|
| `falconx-frontend/src/styles/global.css` | 顶部 `@import` 新增 `_auth.css` 和 `_market.css` |
| `falconx-frontend/src/features/terminal/TradingTerminal.tsx` | market 视图 mobile/desktop 三元 + `<TerminalDrawer />` 替换 inline + `<OrderTicketFab />` + Sheet 包 OrderTicket |
| `falconx-frontend/src/components/mobile/index.ts` | barrel export 加 `OrderTicketFab` |
| `falconx-frontend/src/features/auth/AuthForm.tsx` | （可选）确认 `.auth-form__name-row` className 命中（已存在则不动）|

---

## Task 1: TerminalDrawer 组件抽取

**Files:**
- Create: `falconx-frontend/src/features/terminal/TerminalDrawer.tsx`
- Create: `falconx-frontend/src/features/terminal/TerminalDrawer.test.tsx`

- [ ] **Step 1: 写失败测试**

创建 `falconx-frontend/src/features/terminal/TerminalDrawer.test.tsx`：

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { TerminalDrawer } from "./TerminalDrawer";

describe("TerminalDrawer", () => {
  it("renders three primary navigation buttons", () => {
    render(
      <TerminalDrawer
        onOpenProfile={() => {}}
        onOpenKyc={() => {}}
        onOpenWithdraw={() => {}}
      />
    );
    expect(screen.getByRole("button", { name: /账户资料/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /身份认证/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /出金/ })).toBeInTheDocument();
  });

  it("calls onOpenProfile when 账户资料 clicked", async () => {
    const handle = vi.fn();
    render(
      <TerminalDrawer
        onOpenProfile={handle}
        onOpenKyc={() => {}}
        onOpenWithdraw={() => {}}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /账户资料/ }));
    expect(handle).toHaveBeenCalledOnce();
  });

  it("calls onOpenKyc when 身份认证 clicked", async () => {
    const handle = vi.fn();
    render(
      <TerminalDrawer
        onOpenProfile={() => {}}
        onOpenKyc={handle}
        onOpenWithdraw={() => {}}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /身份认证/ }));
    expect(handle).toHaveBeenCalledOnce();
  });

  it("calls onOpenWithdraw when 出金 clicked", async () => {
    const handle = vi.fn();
    render(
      <TerminalDrawer
        onOpenProfile={() => {}}
        onOpenKyc={() => {}}
        onOpenWithdraw={handle}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /出金/ }));
    expect(handle).toHaveBeenCalledOnce();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd falconx-frontend && npx vitest run src/features/terminal/TerminalDrawer.test.tsx`
Expected: FAIL `Cannot find module './TerminalDrawer'` 或类似 module not found

- [ ] **Step 3: 实现 TerminalDrawer 组件**

创建 `falconx-frontend/src/features/terminal/TerminalDrawer.tsx`：

```tsx
import { User, ShieldCheck, ArrowUpFromLine } from "lucide-react";

export interface TerminalDrawerProps {
  onOpenProfile: () => void;
  onOpenKyc: () => void;
  onOpenWithdraw: () => void;
}

export function TerminalDrawer({ onOpenProfile, onOpenKyc, onOpenWithdraw }: TerminalDrawerProps) {
  return (
    <nav className="terminal-drawer" aria-label="账户菜单">
      <button type="button" className="terminal-drawer__item" onClick={onOpenProfile}>
        <User size={16} strokeWidth={1.8} aria-hidden="true" />
        <span>账户资料</span>
      </button>
      <button type="button" className="terminal-drawer__item" onClick={onOpenKyc}>
        <ShieldCheck size={16} strokeWidth={1.8} aria-hidden="true" />
        <span>身份认证</span>
      </button>
      <button type="button" className="terminal-drawer__item" onClick={onOpenWithdraw}>
        <ArrowUpFromLine size={16} strokeWidth={1.8} aria-hidden="true" />
        <span>出金</span>
      </button>
    </nav>
  );
}
```

- [ ] **Step 4: 加 CSS（_mobile-shell.css 末尾）**

读 `falconx-frontend/src/styles/modules/_mobile-shell.css` 末尾，在最末加：

```css
/* ====================== TerminalDrawer 内容样式 ====================== */
.terminal-drawer {
  padding: var(--fx-space-5);
  display: flex;
  flex-direction: column;
  gap: var(--fx-space-3);
}

.terminal-drawer__item {
  display: flex;
  align-items: center;
  gap: var(--fx-space-3);
  padding: var(--fx-space-3) var(--fx-space-4);
  border: 1px solid var(--fx-hairline);
  border-radius: var(--fx-radius-md);
  background: transparent;
  color: var(--fx-text);
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  transition: border-color 150ms ease, background 150ms ease;
}

.terminal-drawer__item:hover,
.terminal-drawer__item:active {
  border-color: var(--fx-border-strong);
  background: rgba(84, 230, 255, 0.04);
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd falconx-frontend && npx vitest run src/features/terminal/TerminalDrawer.test.tsx`
Expected: PASS — 4 tests passed

- [ ] **Step 6: Commit**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2a
git add falconx-frontend/src/features/terminal/TerminalDrawer.tsx \
        falconx-frontend/src/features/terminal/TerminalDrawer.test.tsx \
        falconx-frontend/src/styles/modules/_mobile-shell.css
git commit -m "feat(h5-phase2a-1): TerminalDrawer 抽取为命名组件 + 4 测试

TradingTerminal inline drawer (~50 行 inline style) 抽成命名组件，
单一职责 + 可独立单元测试 + 减少 TradingTerminal.tsx 行数。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: OrderTicketFab 组件

**Files:**
- Create: `falconx-frontend/src/components/mobile/OrderTicketFab.tsx`
- Create: `falconx-frontend/src/components/mobile/OrderTicketFab.test.tsx`
- Modify: `falconx-frontend/src/components/mobile/index.ts`（barrel export）

- [ ] **Step 1: 写失败测试**

创建 `falconx-frontend/src/components/mobile/OrderTicketFab.test.tsx`：

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { OrderTicketFab } from "./OrderTicketFab";

describe("OrderTicketFab", () => {
  it("renders symbol and price when provided", () => {
    render(
      <OrderTicketFab
        symbol="BTCUSDT"
        priceLabel="67,432.50"
        onClick={() => {}}
      />
    );
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("67,432.50")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /下单/ })).toBeEnabled();
  });

  it("renders disabled state when symbol is null", () => {
    render(
      <OrderTicketFab
        symbol={null}
        priceLabel={null}
        onClick={() => {}}
      />
    );
    const button = screen.getByRole("button");
    expect(button).toBeDisabled();
    expect(screen.getByText(/选择品种/)).toBeInTheDocument();
  });

  it("calls onClick when clicked with enabled state", async () => {
    const handle = vi.fn();
    render(
      <OrderTicketFab
        symbol="BTCUSDT"
        priceLabel="67,432.50"
        onClick={handle}
      />
    );
    await userEvent.click(screen.getByRole("button"));
    expect(handle).toHaveBeenCalledOnce();
  });

  it("does not call onClick when disabled", async () => {
    const handle = vi.fn();
    render(
      <OrderTicketFab
        symbol={null}
        priceLabel={null}
        onClick={handle}
      />
    );
    await userEvent.click(screen.getByRole("button"));
    expect(handle).not.toHaveBeenCalled();
  });

  it("applies aria-label with symbol context", () => {
    render(
      <OrderTicketFab
        symbol="BTCUSDT"
        priceLabel="67,432.50"
        onClick={() => {}}
      />
    );
    expect(screen.getByRole("button", { name: /BTCUSDT.*下单/ })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd falconx-frontend && npx vitest run src/components/mobile/OrderTicketFab.test.tsx`
Expected: FAIL `Cannot find module './OrderTicketFab'`

- [ ] **Step 3: 实现 OrderTicketFab 组件**

创建 `falconx-frontend/src/components/mobile/OrderTicketFab.tsx`：

```tsx
import { ArrowRight } from "lucide-react";

export interface OrderTicketFabProps {
  symbol: string | null;
  priceLabel: string | null;
  onClick: () => void;
}

export function OrderTicketFab({ symbol, priceLabel, onClick }: OrderTicketFabProps) {
  const disabled = !symbol;
  const ariaLabel = symbol ? `${symbol} 下单` : "选择品种后下单";
  return (
    <button
      type="button"
      className="order-ticket-fab"
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
    >
      <span className="order-ticket-fab__info">
        {symbol ? (
          <>
            <strong className="order-ticket-fab__symbol">{symbol}</strong>
            {priceLabel && (
              <span className="order-ticket-fab__price">{priceLabel}</span>
            )}
          </>
        ) : (
          <span className="order-ticket-fab__empty">选择品种下单</span>
        )}
      </span>
      <span className="order-ticket-fab__cta">
        下单
        <ArrowRight size={14} strokeWidth={2} aria-hidden="true" />
      </span>
    </button>
  );
}
```

- [ ] **Step 4: 更新 barrel export**

修改 `falconx-frontend/src/components/mobile/index.ts`，在文件末尾追加：

```ts
export { OrderTicketFab } from "./OrderTicketFab";
export type { OrderTicketFabProps } from "./OrderTicketFab";
```

（若文件已有其他 export，加在末尾即可，不删除现有内容）

- [ ] **Step 5: 跑测试确认通过**

Run: `cd falconx-frontend && npx vitest run src/components/mobile/OrderTicketFab.test.tsx`
Expected: PASS — 5 tests passed

- [ ] **Step 6: Commit**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2a
git add falconx-frontend/src/components/mobile/OrderTicketFab.tsx \
        falconx-frontend/src/components/mobile/OrderTicketFab.test.tsx \
        falconx-frontend/src/components/mobile/index.ts
git commit -m "feat(h5-phase2a-2): OrderTicketFab mobile 下单 FAB + 5 测试

symbol + price 信息条 + 下单 CTA 一体的 mobile 浮按钮，
disabled state 防误触（未选品种）。Visual 详见 spec。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: _auth.css 模块新建 + global.css @import

**Files:**
- Create: `falconx-frontend/src/styles/modules/_auth.css`
- Modify: `falconx-frontend/src/styles/global.css`（顶部 @import）

- [ ] **Step 1: 创建 _auth.css**

创建 `falconx-frontend/src/styles/modules/_auth.css`：

```css
/* FalconX H5 — auth-gate / auth-form mobile 适配规则
 * 桌面（≥ 768px）保持原 global.css 中的 .auth-gate / .auth-form 样式不变。
 * 仅在 < 768px 重排为全屏 + 单页滚动 + 48px touch target。
 */

@media (max-width: 767px) {
  .auth-gate {
    padding: calc(var(--fx-safe-top) + 12px) 20px calc(var(--fx-safe-bottom) + 20px);
    min-height: 100vh;
    min-height: 100dvh;
    display: flex;
    flex-direction: column;
    justify-content: center;
  }

  .auth-gate__brand {
    width: 100%;
    max-width: 480px;
    margin: 0 auto;
    padding-top: 8vh;
  }

  .auth-gate__brand .falcon-mark,
  .auth-gate__brand .falcon-wordmark {
    transform: scale(0.85);
    transform-origin: left center;
  }

  .auth-gate .falcon-backdrop {
    opacity: 0.4;
    filter: blur(2px);
  }

  .language-select {
    position: fixed;
    top: calc(var(--fx-safe-top) + 8px);
    right: 12px;
    width: auto;
    font-size: 12px;
    padding: 4px 8px;
    z-index: 10;
  }

  .auth-actions {
    width: 100%;
    display: flex;
    flex-direction: column;
    gap: var(--fx-space-3);
    margin-top: var(--fx-space-6);
  }

  .auth-button {
    width: 100%;
    min-height: 48px;
    font-size: 14px;
  }

  .auth-form {
    width: 100%;
  }

  .auth-form input,
  .auth-form select {
    min-height: 48px;
    font-size: 16px; /* 防 iOS focus 时自动 zoom */
  }

  .auth-form__name-row {
    display: grid;
    grid-template-columns: 1fr;
    gap: var(--fx-space-3);
  }

  .auth-form__submit {
    width: 100%;
    min-height: 48px;
    font-size: 15px;
  }
}
```

- [ ] **Step 2: 在 global.css 顶部 @import**

读 `falconx-frontend/src/styles/global.css` 顶 5 行，确认现有 @import 顺序。

修改 `falconx-frontend/src/styles/global.css` 顶部 — 在 `@import "./modules/_mobile-shell.css";` 后追加一行：

```css
@import "./modules/_auth.css";
```

最终顶部应为：

```css
@import "./tokens.css";
@import "./modules/_base.css";
@import "./modules/_mobile-shell.css";
@import "./modules/_auth.css";
```

- [ ] **Step 3: 跑 build 确认 CSS 加载无错**

Run: `cd falconx-frontend && npx vite build 2>&1 | head -30`
Expected: build 完成，无 CSS @import 错误，无 missing file 错误

- [ ] **Step 4: 跑 lint 确认无 CSS module 引入 regression**

Run: `cd falconx-frontend && npm run lint 2>&1 | tail -15`
Expected: 没有新增 error（H5 新增 regression = 0；现有 pre-existing 3 errors / 2 warnings 不变）

- [ ] **Step 5: Commit**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2a
git add falconx-frontend/src/styles/modules/_auth.css \
        falconx-frontend/src/styles/global.css
git commit -m "feat(h5-phase2a-3): _auth.css mobile 适配 + global.css @import

AuthGate / AuthForm < 768px 重排：
- 全屏 + safe-area padding
- input/select/button 48px touch target
- input 16px font-size 防 iOS auto-zoom
- name-row 2 列改 1 列
- FalconBackdrop opacity 0.4 + blur 2px（不抢主体）
- language-select 缩到顶部右上角

桌面体验 0 改动（仅 @media (max-width: 767px) 规则）。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: _market.css 模块新建 — watchlist mobile + global.css @import

**Files:**
- Create: `falconx-frontend/src/styles/modules/_market.css`
- Modify: `falconx-frontend/src/styles/global.css`（顶部再 @import 一行）

- [ ] **Step 1: 创建 _market.css 含 watchlist + market view stack 部分**

创建 `falconx-frontend/src/styles/modules/_market.css`：

```css
/* FalconX H5 — market 视图 / watchlist / chart mobile 适配
 * 桌面（≥ 768px）保持原 global.css 中 .market-list / .market-row / .market-chart 样式不变。
 * 仅在 < 768px 改为垂直 stack + 紧凑卡片 + 触摸 target。
 */

@media (max-width: 767px) {
  /* ============ market 视图 stack 布局 ============ */
  .terminal-workspace--market {
    display: flex;
    flex-direction: column;
    gap: 0;
    padding: 0;
  }

  /* ============ MarketWatchlist mobile 紧凑卡片 ============ */
  .market-list {
    border-right: 0;
    border-bottom: 1px solid var(--fx-hairline);
    min-height: 0;
    flex-shrink: 0;
    max-height: 38vh;
  }

  .market-list__rows {
    max-height: calc(38vh - 120px);
    overflow-y: auto;
  }

  .market-row {
    flex-direction: column;
    align-items: stretch;
    padding: 12px 16px;
    min-height: 64px;
    gap: 4px;
    position: relative;
    border-bottom: 1px solid var(--fx-hairline);
    transition: transform 80ms ease, background 80ms ease;
  }

  .market-row:active {
    transform: scale(0.985);
    background: rgba(84, 230, 255, 0.04);
  }

  .market-row.selected::before {
    content: "";
    position: absolute;
    left: 0;
    top: 0;
    bottom: 0;
    width: 3px;
    background: var(--fx-cyan);
  }

  .market-row > span:first-child {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: var(--fx-space-2);
  }

  .market-row > span:first-child strong {
    font-size: 15px;
    letter-spacing: 0.02em;
  }

  .market-row > span:first-child small {
    color: var(--fx-muted);
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: var(--fx-eyebrow-letter);
  }

  .market-row__quote {
    display: grid;
    grid-template-columns: auto auto 1fr auto;
    align-items: center;
    gap: var(--fx-space-2);
    font-family: var(--fx-mono-stack);
    font-variant-numeric: tabular-nums;
    font-size: 13px;
  }

  .market-row__quote b {
    display: inline-flex;
    align-items: baseline;
    gap: 2px;
  }

  .market-row__quote b small {
    color: var(--fx-subtle);
    font-size: 9px;
    text-transform: uppercase;
    letter-spacing: var(--fx-eyebrow-letter);
  }

  .market-row__quote em {
    font-style: normal;
    font-size: 10px;
    letter-spacing: var(--fx-eyebrow-letter);
    color: var(--fx-cyan);
  }

  .market-row__quote time {
    display: none; /* mobile 紧凑：隐藏 timestamp，watchlist 顶部已有 conn-dot 状态 */
  }
}
```

- [ ] **Step 2: 在 global.css 顶部 @import _market.css**

修改 `falconx-frontend/src/styles/global.css` 顶部 — 在 `@import "./modules/_auth.css";` 后追加一行：

```css
@import "./modules/_market.css";
```

最终顶部应为：

```css
@import "./tokens.css";
@import "./modules/_base.css";
@import "./modules/_mobile-shell.css";
@import "./modules/_auth.css";
@import "./modules/_market.css";
```

- [ ] **Step 3: 跑 build 确认 CSS 加载无错**

Run: `cd falconx-frontend && npx vite build 2>&1 | head -30`
Expected: build 完成，无 CSS @import 错误，无 missing file 错误

- [ ] **Step 4: Commit**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2a
git add falconx-frontend/src/styles/modules/_market.css \
        falconx-frontend/src/styles/global.css
git commit -m "feat(h5-phase2a-4): _market.css watchlist mobile 紧凑卡片 + global.css @import

market 视图 < 768px：
- terminal-workspace--market 改 vertical stack
- market-list 占顶 38vh + 内部滚动
- market-row 卡片化：2 行（name+chg / mono price grid）
- selected 左侧 3px cyan 竖条
- :active scale 0.985 + cyan bg
- timestamp 隐藏（密度优先，状态走 conn-dot）

数字字体 JetBrains Mono + tabular-nums，涨跌幅 fx-long/short token。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: _market.css 追加 chart mobile + OrderTicketFab 样式

**Files:**
- Modify: `falconx-frontend/src/styles/modules/_market.css`（追加 chart + fab 部分）

- [ ] **Step 1: 在 _market.css 末尾追加 chart + fab 样式**

读 `falconx-frontend/src/styles/modules/_market.css` 末尾，在 `@media (max-width: 767px) { ... }` 块的 `}` 内**追加**以下规则（仍在 @media block 内）：

```css

  /* ============ MarketChart mobile ============ */
  .market-chart {
    height: 40vh;
    max-height: 400px;
    min-height: 280px;
    border-top: 1px solid var(--fx-border-strong);
    border-bottom: 1px solid var(--fx-border-strong);
    flex-shrink: 0;
  }

  .market-chart .timeframe-row,
  .market-chart .chart-timeframe-row {
    display: grid;
    grid-auto-flow: column;
    grid-auto-columns: 1fr;
    gap: 0;
    padding: 6px 8px;
    border-bottom: 1px solid var(--fx-hairline);
  }

  .market-chart .timeframe-row button,
  .market-chart .chart-timeframe-row button {
    min-height: 32px;
    min-width: 44px;
    padding: 6px 8px;
    font-size: 12px;
    font-family: var(--fx-mono-stack);
    letter-spacing: 0.02em;
    color: var(--fx-muted);
    background: transparent;
    border: 0;
    border-radius: 0;
    cursor: pointer;
    transition: color 120ms ease, background 120ms ease;
    position: relative;
  }

  .market-chart .timeframe-row button:active,
  .market-chart .chart-timeframe-row button:active {
    background: rgba(84, 230, 255, 0.12);
  }

  .market-chart .timeframe-row button.active,
  .market-chart .chart-timeframe-row button.active,
  .market-chart .timeframe-row button[aria-pressed="true"],
  .market-chart .chart-timeframe-row button[aria-pressed="true"] {
    color: var(--fx-cyan);
    background: var(--fx-surface-3);
  }

  .market-chart .timeframe-row button.active::after,
  .market-chart .chart-timeframe-row button.active::after,
  .market-chart .timeframe-row button[aria-pressed="true"]::after,
  .market-chart .chart-timeframe-row button[aria-pressed="true"]::after {
    content: "";
    position: absolute;
    left: 8px;
    right: 8px;
    bottom: 0;
    height: 1px;
    background: var(--fx-cyan);
  }

  /* ============ TradingTabs mobile ============ */
  .trading-tabs {
    flex: 1;
    min-height: 0;
  }

  /* ============ OrderTicketFab ============ */
  .order-ticket-fab {
    position: fixed;
    left: 16px;
    right: 16px;
    bottom: calc(var(--fx-bottom-nav-height) + var(--fx-safe-bottom) + 12px);
    height: 56px;
    padding: 0 var(--fx-space-4);
    display: flex;
    align-items: center;
    justify-content: space-between;
    background: var(--fx-surface-2);
    border: 1px solid var(--fx-border-strong);
    border-radius: var(--fx-radius-md);
    color: var(--fx-text);
    cursor: pointer;
    z-index: 90;
    box-shadow: 0 6px 20px -8px rgba(0, 0, 0, 0.6);
    transition: box-shadow 120ms ease, transform 80ms ease;
  }

  .order-ticket-fab:active {
    box-shadow: inset 0 0 0 2px var(--fx-cyan), 0 6px 20px -8px rgba(0, 0, 0, 0.6);
    transform: scale(0.99);
  }

  .order-ticket-fab:disabled {
    opacity: 0.5;
    cursor: not-allowed;
    box-shadow: none;
  }

  .order-ticket-fab__info {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
    overflow: hidden;
  }

  .order-ticket-fab__symbol {
    font-size: 13px;
    letter-spacing: 0.02em;
  }

  .order-ticket-fab__price {
    font-family: var(--fx-mono-stack);
    font-variant-numeric: tabular-nums;
    font-size: 12px;
    color: var(--fx-muted);
  }

  .order-ticket-fab__empty {
    font-size: 12px;
    color: var(--fx-muted);
    letter-spacing: var(--fx-eyebrow-letter);
    text-transform: uppercase;
  }

  .order-ticket-fab__cta {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--fx-cyan);
    font-size: 13px;
    font-family: var(--fx-mono-stack);
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }
```

- [ ] **Step 2: 跑 build 确认 CSS 加载无错**

Run: `cd falconx-frontend && npx vite build 2>&1 | head -30`
Expected: build 完成，无 CSS @import 错误

- [ ] **Step 3: Commit**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2a
git add falconx-frontend/src/styles/modules/_market.css
git commit -m "feat(h5-phase2a-5): _market.css chart timeframe segmented + OrderTicketFab mobile 样式

MarketChart < 768px：
- 容器 40vh / 280-400px / cyan border-top+bottom
- timeframe row segmented control (grid 1fr 等宽)
- 按钮 32px min-height + JetBrains Mono + cyan active state + 底部 1px cyan
- 兼容 .timeframe-row 和 .chart-timeframe-row 两种 className

OrderTicketFab：
- fixed left/right 16px / bottom = BottomNav + safe + 12
- cyan border-strong + surface-2 bg + soft shadow
- :active 内 cyan glow + scale 0.99
- disabled 时 opacity 0.5
- info 左 + cta 右（mono + uppercase）

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: TradingTerminal market 视图 mobile stack 接入

**Files:**
- Modify: `falconx-frontend/src/features/terminal/TradingTerminal.tsx`

这是集成 task — 把 Task 1（TerminalDrawer）+ Task 2（OrderTicketFab）+ Task 4/5（CSS）拼到 TradingTerminal market 视图。

- [ ] **Step 1: 读 TradingTerminal.tsx market 视图分支当前实现**

Run: `sed -n '440,495p' falconx-frontend/src/features/terminal/TradingTerminal.tsx`
Expected: 看到当前 `currentView === 'market'` 的 3 列 JSX（MarketWatchlist + terminal-center 含 MarketChart + TradingTabs + OrderTicket）

- [ ] **Step 2: 修改 TradingTerminal.tsx 顶部 import**

在 `TradingTerminal.tsx` 现有 import 块的合适位置（在 MobileShell import 附近）追加：

```tsx
import { Sheet, type BottomNavItem, OrderTicketFab } from "../../components/mobile";
import { TerminalDrawer } from "./TerminalDrawer";
```

注意：`BottomNav` 已经通过 `BottomNavItem` 类型在使用；若 `Sheet` 之前未 import 就加上；`OrderTicketFab` 是 Task 2 新加的；`TerminalDrawer` 是 Task 1 新加的。

读现有 import：

Run: `sed -n '1,50p' falconx-frontend/src/features/terminal/TradingTerminal.tsx | grep -n "import"`

如果已经有 `import type { BottomNavItem } from "../../components/mobile"`，把它改成：

```tsx
import { OrderTicketFab, type BottomNavItem } from "../../components/mobile";
```

并在 MobileShell 旁边加：

```tsx
import { Sheet } from "../../components/mobile";
import { TerminalDrawer } from "./TerminalDrawer";
```

- [ ] **Step 3: 在 TradingTerminal function 内部新增 ticketOpen state**

找到 TradingTerminal 函数体内 `const [withdrawOpen, setWithdrawOpen] = useState(false);` 那一行（约 line 80），下方新增：

```tsx
  const [ticketOpen, setTicketOpen] = useState(false);
```

- [ ] **Step 4: 用 TerminalDrawer 替换 inline drawerContent**

在 `<MobileShell ... drawerContent={...}>` 的 `drawerContent` prop 处，把现有 inline `<nav style={...}>...</nav>`（约 line 370-418，50 行）替换为：

```tsx
      drawerContent={
        <TerminalDrawer
          onOpenProfile={() => setProfileOpen(true)}
          onOpenKyc={() => setKycOpen(true)}
          onOpenWithdraw={() => setWithdrawOpen(true)}
        />
      }
```

- [ ] **Step 5: market 视图 mobile/desktop 三元 + Sheet + Fab**

找到 `currentView === 'market'` 的 else 分支（约 line 444-490 的 `<>...</>` fragment），用以下结构替换该 fragment（保留 MarketWatchlist / MarketChart / TradingTabs / OrderTicket 各自 props 透传不变）：

```tsx
          <>
            {isMobile ? (
              <>
                <MarketWatchlist
                  groups={symbolsQuery.data ? symbolGroups : undefined}
                  activeGroupKey={effectiveActiveMarketGroup}
                  quotes={quotes}
                  selectedSymbol={selectedSymbol}
                  connectionState={connectionState}
                  isLoading={symbolsQuery.isLoading}
                  isError={symbolsQuery.isError}
                  errorMessage={symbolsQuery.error instanceof Error ? symbolsQuery.error.message : undefined}
                  onRetry={() => void symbolsQuery.refetch()}
                  onSelectSymbol={setSelectedSymbol}
                  onSelectGroup={handleMarketGroupChange}
                  onVisibleSymbolsChange={handleVisibleSymbolsChange}
                />
                <MarketChart
                  symbol={selectedSymbolMeta}
                  quote={selectedQuote}
                  quoteHistory={selectedQuoteHistory}
                  klineHistory={klineHistoryQuery.data ?? []}
                  liveKline={
                    selectedSymbol
                      ? klines[selectedSymbol]?.[chartKlineInterval]
                      : undefined
                  }
                  selectedSymbol={selectedSymbol}
                  connectionState={connectionState}
                  isLoading={
                    symbolsQuery.isLoading ||
                    Boolean(klineHistoryQuery.isLoading && !klineHistoryQuery.data) ||
                    Boolean(quoteHistoryQuery.isLoading && !quoteHistoryQuery.data)
                  }
                  timeframe={timeframe}
                  onTimeframeChange={setTimeframe}
                />
                <TradingTabs
                  pnlMap={pnlMap}
                  socketState={tradingSocketState}
                  forceActiveTab={forceTradingTab}
                  serverTotalUnrealizedPnl={totalUnrealizedPnl}
                />
                <OrderTicketFab
                  symbol={selectedSymbol}
                  priceLabel={selectedQuote?.askPrice ?? selectedQuote?.bidPrice ?? null}
                  onClick={() => setTicketOpen(true)}
                />
                <Sheet
                  open={ticketOpen}
                  onClose={() => setTicketOpen(false)}
                  title={selectedSymbol ? `${selectedSymbol} 下单` : "下单"}
                >
                  <OrderTicket symbolMeta={selectedSymbolMeta ?? null} />
                </Sheet>
              </>
            ) : (
              <>
                <MarketWatchlist
                  groups={symbolsQuery.data ? symbolGroups : undefined}
                  activeGroupKey={effectiveActiveMarketGroup}
                  quotes={quotes}
                  selectedSymbol={selectedSymbol}
                  connectionState={connectionState}
                  isLoading={symbolsQuery.isLoading}
                  isError={symbolsQuery.isError}
                  errorMessage={symbolsQuery.error instanceof Error ? symbolsQuery.error.message : undefined}
                  onRetry={() => void symbolsQuery.refetch()}
                  onSelectSymbol={setSelectedSymbol}
                  onSelectGroup={handleMarketGroupChange}
                  onVisibleSymbolsChange={handleVisibleSymbolsChange}
                />
                <div className="terminal-center">
                  <MarketChart
                    symbol={selectedSymbolMeta}
                    quote={selectedQuote}
                    quoteHistory={selectedQuoteHistory}
                    klineHistory={klineHistoryQuery.data ?? []}
                    liveKline={
                      selectedSymbol
                        ? klines[selectedSymbol]?.[chartKlineInterval]
                        : undefined
                    }
                    selectedSymbol={selectedSymbol}
                    connectionState={connectionState}
                    isLoading={
                      symbolsQuery.isLoading ||
                      Boolean(klineHistoryQuery.isLoading && !klineHistoryQuery.data) ||
                      Boolean(quoteHistoryQuery.isLoading && !quoteHistoryQuery.data)
                    }
                    timeframe={timeframe}
                    onTimeframeChange={setTimeframe}
                  />
                  <TradingTabs
                    pnlMap={pnlMap}
                    socketState={tradingSocketState}
                    forceActiveTab={forceTradingTab}
                    serverTotalUnrealizedPnl={totalUnrealizedPnl}
                  />
                </div>
                <OrderTicket symbolMeta={selectedSymbolMeta ?? null} />
              </>
            )}
          </>
```

注意：桌面分支的 JSX（`isMobile === false`）必须跟当前实现**完全相同**，确保 0 regression。

- [ ] **Step 6: 跑 typecheck 确认无类型错误**

Run: `cd falconx-frontend && npx tsc -p tsconfig.app.json --noEmit 2>&1 | head -20`
Expected: 0 error。如果报 `selectedQuote.askPrice` 不存在，根据 Quote 类型实际字段调整（可能是 `ask` 而非 `askPrice`）。

如果 typescript 报 Quote 字段名问题，回到第 5 步把 `selectedQuote?.askPrice ?? selectedQuote?.bidPrice ?? null` 改为读取 Quote 实际定义的字段名。先用 `cat falconx-frontend/src/features/market/marketTypes.ts | grep -A 20 "Quote"` 看实际定义。

- [ ] **Step 7: 跑 build 确认通过**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -20`
Expected: build success，0 error

- [ ] **Step 8: 跑测试套件确认无 regression**

Run: `cd falconx-frontend && npx vitest run 2>&1 | tail -30`
Expected: 全部 PASS（除原有 pre-existing `WithdrawDrawer TC-WD-FE-002` 已知失败外）

- [ ] **Step 9: Commit**

Run:
```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2a
git add falconx-frontend/src/features/terminal/TradingTerminal.tsx
git commit -m "feat(h5-phase2a-6): TradingTerminal market 视图 mobile stack 接入

集成 Task 1+2+4+5：
- inline drawer (~50 行 inline style) -> <TerminalDrawer />
- market 视图按 isMobile 分两套 JSX
  - mobile: watchlist + chart + tabs + OrderTicketFab + Sheet(OrderTicket)
  - desktop: 原 3 列布局完全不变（0 regression）
- ticketOpen state 控制 Sheet 弹出
- OrderTicketFab 读 selectedSymbol + askPrice/bidPrice

桌面 JSX 1:1 保留，仅 mobile 分支重排。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: Final Verification + manual mobile preview

**Files:** 无文件改动，仅运行验证 + 视觉 sanity check

- [ ] **Step 1: 跑完整测试套件**

Run: `cd falconx-frontend && npx vitest run 2>&1 | tail -10`
Expected: 所有新增测试 PASS（TerminalDrawer 4 + OrderTicketFab 5 = 9）；现有 pre-existing 失败（如 `WithdrawDrawer TC-WD-FE-002`）保持原状不增加

- [ ] **Step 2: 跑 lint 确认无 H5 新增 regression**

Run: `cd falconx-frontend && npm run lint 2>&1 | tail -20`
Expected: H5 Phase 2A 新增文件 0 error 0 warning；pre-existing 3 errors / 2 warnings 不变（DashboardPage / PositionsTable / TradingTabs / OrderTicket）

- [ ] **Step 3: 跑 production build**

Run: `cd falconx-frontend && npx vite build 2>&1 | tail -10`
Expected: build 完成，0 error；bundle 增量 < 5KB gzip（OrderTicketFab + TerminalDrawer 2 小组件 + 2 CSS module）

- [ ] **Step 4: 跑 dev server，DevTools mobile preview 验证**

Run: `cd falconx-frontend && npx vite --port 5173 &`
等待启动后访问 `http://localhost:5173`。

在 Chrome DevTools 切到 iPhone SE (375x667) 验证以下：

- [ ] AuthGate 全屏，brand 缩 0.85，backdrop opacity 0.4
- [ ] AuthGate login 表单 input 48px 高 + 16px 字
- [ ] AuthGate register name-row 改 1 列（last/first 各占一行）
- [ ] 登录后 TerminalView default = dashboard，BottomNav 5 tab 可见
- [ ] 点 hamburger header → TerminalDrawer 滑出含 3 按钮（账户资料 / 身份认证 / 出金）
- [ ] BottomNav 切到 market → 看到 stack：watchlist（顶 38vh 滚动）+ chart（40vh）+ tabs + OrderTicketFab（浮在 BottomNav 上方 12px）
- [ ] 点击 OrderTicketFab → Sheet 从底部弹出 OrderTicket
- [ ] watchlist row 点击切换 selectedSymbol，chart 重画
- [ ] timeframe row 5 按钮等宽，点击切换流畅
- [ ] watchlist row 卡片 2 行：name+pct / mono price grid

切到 1440 桌面：
- [ ] market 视图 3 列布局完整（watchlist + center + OrderTicket 右）— 0 regression
- [ ] TerminalNav 桌面侧栏可见
- [ ] BottomNav / OrderTicketFab / mobile header 全部 display: none

杀 dev server：

Run: `kill %1 2>/dev/null; pkill -f "vite --port 5173" 2>/dev/null; true`

- [ ] **Step 5: Final commit（如果有 verification 中发现的小修复）**

如果 Step 4 视觉验证完全通过，无新改动 — 跳过此 step。

如果有 CSS 微调（如某个 padding 略大、某 token 用错），在此 step 一并修。

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-phase2a
git status --short
# 如有改动：
git add -p && git commit -m "fix(h5-phase2a-7): manual mobile preview 修复 CSS 细节

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 6: Merge worktree 到 main + push**

> ⚠️ **CRITICAL**: 此步骤只在 controller（不是 subagent）执行。subagent 完成 Task 7 step 1-5 后，把 worktree 状态报告给 controller，由 controller 操作 merge。

controller 流程：
```bash
cd /home/ives/code/FalconX
git checkout main
git merge --no-ff worktree-h5-phase2a -m "Merge worktree-h5-phase2a: H5 自适应 Phase 2A 实施完成

4 P0 feature mobile 适配 + 桌面 0 regression：
- TerminalDrawer 抽取 (Task 1)
- OrderTicketFab 新增 (Task 2)
- _auth.css mobile rules (Task 3)
- _market.css watchlist + chart + fab (Task 4+5)
- TradingTerminal market 视图 mobile stack (Task 6)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
git push origin main
```

---

## Self-Review Checklist

完成 Task 1-7 后，controller 必须自查：

- [ ] Spec coverage：4 features 全部对应 task — terminal (Task 1, 6) / auth (Task 3) / watchlist (Task 4) / chart (Task 5) ✓
- [ ] Visual Design Notes 红线全部在 CSS 中体现（mono / cyan / hairline / 32-48px touch / 16px input）✓
- [ ] 新增 2 组件都有 ≥ 4 单元测试 ✓
- [ ] 桌面 JSX 1:1 保留 (Task 6 step 5 desktop 分支)✓
- [ ] Phase 0+1 基础设施引用正确（Sheet / Drawer / MobileShell / useBreakpoint / BottomNav）✓
- [ ] 没有引入新 npm dep（仅 lucide-react 已有 icons + 现有 mobile 组件）✓

---

## Risk Mitigation

| 风险 | 缓解 |
|---|---|
| Task 6 step 5 桌面分支 JSX 漂移导致 regression | 严格 copy-paste 当前 `currentView === 'market'` JSX；diff 验证 |
| `selectedQuote.askPrice` 字段名错 | Step 6 typecheck 即捕获，按 marketTypes.ts 实际定义调整 |
| AuthGate 现有 `.auth-button.primary` 类名是否存在 | Task 3 实施前确认；不存在则改用 `.auth-button` 通用类 |
| `.timeframe-row` className 不存在 | Task 5 用 `.timeframe-row, .chart-timeframe-row` 双 selector 兜底 |
| dev server 启动后 OrderTicket 在 Sheet 内键盘弹起遮挡 | OrderTicket native input 已有 scrollIntoView，Sheet max-height 95vh 留空间 |
| TradingTerminal.tsx 文件继续膨胀 | Task 1 抽 TerminalDrawer 后 -50 行；Task 6 mobile/desktop 三元 +50 行；净持平 |

---

## Done Definition

完成后必须满足：

- [ ] 7 个 task 全部 commit 到 `worktree-h5-phase2a` 分支
- [ ] 2 新增组件 9 测试全 PASS（TerminalDrawer 4 + OrderTicketFab 5）
- [ ] `npm run lint` H5 新增 regression = 0
- [ ] `npx vite build` 0 error，bundle 增量 < 5KB gzip
- [ ] Chrome DevTools iPhone SE (375x667) 视觉验证全通过（Task 7 step 4）
- [ ] Chrome DevTools 1440 桌面切换 — market 3 列 / TerminalNav / 桌面 OrderTicket 全部完整，0 regression
- [ ] worktree merge 到 main 并 push origin
