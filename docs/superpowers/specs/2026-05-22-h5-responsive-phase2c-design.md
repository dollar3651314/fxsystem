# H5 自适应 Phase 2C 设计 — wallet 钱包 mobile

> 前置：Phase 0+1 / 2A / 2B-1/2B-2/2B-3（merge c0a1727）— trading 13 组件全量交付
> 后置 Phase 3：客户端 P1 features（withdraw drawer 历史 / kyc / activity / dashboard / profile / settings）

## Goal

3 个 wallet 相关组件 mobile 适配：

| 组件 | 当前桌面形态 | mobile 改造 |
|---|---|---|
| **WalletPage** | console 风格长滚动页（hero + 3 sections，hero/metrics 已有 880px breakpoint）| CSS-only 全局 mobile @media + ledger table → LedgerCard cards |
| **WithdrawDrawer** | 683 行 drawer 含出金表单 + 历史 table | CSS-only mobile drawer 100vw + form input 48px + history table 横滚（不抽卡片，YAGNI）|
| **LedgerCard** | 新组件 — mobile 资金流水卡片 | 新建 + 4 测试 |

桌面 0 regression。JSX 改动仅限 WalletPage 加 isMobile 分支条件渲染。

## Architecture

```
┌─ Layer 3 (feature view)  ────────────────────────────────────┐
│  WalletPage isMobile 分支：ledger <table> → <LedgerCard />    │
│  WithdrawDrawer / 其他 WalletPage section 0 JSX 改动          │
└────────────────────────────────────────────────────────────────┘
┌─ Layer 2 (新 CSS module)  ─────────────────────────────────┐
│  styles/modules/_wallet.css 含：                             │
│  - WalletPage mobile (.fx-console-header / .fx-console-section)│
│  - 资金流水 filter row mobile stack                          │
│  - LedgerCard mobile 卡片样式                                │
│  - WithdrawDrawer mobile drawer 100vw + form                 │
└────────────────────────────────────────────────────────────────┘
┌─ Layer 1 (基础设施)  ───────────────────────────────────────┐
│  useBreakpoint / .fx-history-card 通用外壳 (复用 Phase 2B-2)  │
└────────────────────────────────────────────────────────────────┘
```

**关键设计选择：**
- WithdrawDrawer 历史 table mobile 不抽 LedgerCard / WithdrawHistoryCard — **暂时允许横滚**（出金历史低频访问，YAGNI 简化）
- WalletPage ledger table mobile **抽 LedgerCard**（资金流水高频，跟 Phase 2B-2 4 Tables 模式一致）
- 新增 `_wallet.css` module（不污染 `_trading.css`）

## File Changes

### Create

- `falconx-frontend/src/styles/modules/_wallet.css`
- `falconx-frontend/src/features/wallet/LedgerCard.tsx`
- `falconx-frontend/src/features/wallet/LedgerCard.test.tsx`

### Modify

- `falconx-frontend/src/styles/global.css` — `@import "./modules/_wallet.css";` 加在 `_trading.css` 之后
- `falconx-frontend/src/features/wallet/WalletPage.tsx` — useBreakpoint + isMobile 分支渲染 LedgerCard，桌面 byte-identical

## Detailed Feature Changes

### 1. `_wallet.css` mobile rules

```css
/* FalconX H5 — wallet 钱包 mobile 适配 */

@media (max-width: 767px) {
  /* WalletPage console layout */
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

  /* 资金流水 filter row mobile stack */
  .wallet-ledger-filter {
    flex-direction: column;
    gap: 8px;
    align-items: stretch;
  }

  .wallet-ledger-filter select,
  .wallet-ledger-filter input {
    width: 100%;
    min-height: 44px;
    font-size: 16px;
  }

  /* Wallet ledger cards (mobile only) */
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
  }

  .wallet-ledger-card__time {
    font-family: var(--fx-mono-stack);
  }

  .wallet-ledger-card__ref {
    font-family: var(--fx-mono-stack);
    color: var(--fx-subtle);
  }

  /* WithdrawDrawer mobile (drawer 100vw + form inputs) */
  .withdraw-drawer .fx-drawer {
    width: 100vw;
    max-width: 100vw;
  }

  .withdraw-drawer__form input,
  .withdraw-drawer__form select,
  .withdraw-drawer__form textarea {
    min-height: 48px;
    font-size: 16px;
  }

  .withdraw-drawer__form textarea {
    min-height: 80px;
  }

  /* WithdrawDrawer 历史 table mobile 横滚（暂不抽卡片 - YAGNI）*/
  .withdraw-drawer__history-wrap {
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
  }
}
```

⚠️ subagent 实施时如发现 `.wallet-ledger-filter` / `.withdraw-drawer` 等 className 实际不存在，按现状 grep 调整 selector 名。

### 2. LedgerCard 组件

LedgerEntry 类型字段（待 grep walletApi.ts 确认）：通常含 `id / bizType / amount / balanceAfter / relatedId / createdAt` 等。

```tsx
export interface LedgerCardProps {
  item: LedgerEntry;
  typeLabel: string; // 父级传入 LEDGER_BIZ_TYPE_LABEL[item.bizType] 或 fallback
}

// 2 行卡片：
// row1: typeLabel + amount (color pos/neg)
// row2: time + relatedId (短 hash) + balanceAfter
```

### 3. WalletPage isMobile 集成

模式跟 Phase 2B-2 同：

```tsx
const { isMobile } = useBreakpoint();

// ledger table 段：
{!isMobile && (
  <table className="fx-table">
    {/* 现有 byte-identical */}
  </table>
)}
{isMobile && (
  <ul className="wallet-ledger-cards" aria-label="资金流水列表">
    {items.map((entry) => (
      <LedgerCard
        key={entry.id}
        item={entry}
        typeLabel={LEDGER_BIZ_TYPE_LABEL[entry.bizType] ?? entry.bizType}
      />
    ))}
  </ul>
)}
```

### 4. WithdrawDrawer mobile（CSS-only）

WithdrawDrawer 用 root drawer (推断 `.withdraw-drawer` 或类似)。**0 JSX 改动**，仅 _wallet.css @media 处理 drawer 全宽 + form inputs。

实际 selector 由 subagent 在 step 1 通过 grep 确认。

## Testing

| Test | 覆盖 |
|---|---|
| `LedgerCard.test.tsx` | render typeLabel + amount / balance after / time / relatedId / pos vs neg amount class | 4 测试 |

## Verification 检查清单

- [ ] DevTools iPhone SE — Wallet tab 切换可见 hero/sections/ledger
- [ ] hero metrics mobile stack（已经在 880px 适配）
- [ ] ledger filter mobile 改 vertical stack + input 44px
- [ ] ledger table mobile 改 LedgerCard 卡片显示
- [ ] 出金按钮点 → WithdrawDrawer mobile 100vw 滑入
- [ ] WithdrawDrawer form inputs 48px / font-size 16px
- [ ] WithdrawDrawer 历史 table mobile 横滚可用
- [ ] DevTools 1440 桌面 0 regression
- [ ] `npm run test` 全过（+4 新测试）
- [ ] `npm run build` 0 error
- [ ] `npm run lint` 0 H5 regression

## Risk

| 风险 | 缓解 |
|---|---|
| `.wallet-ledger-filter` className 实际不存在 | subagent grep 现状选择正确 className（filter 在 WalletPage.tsx line ~234） |
| WithdrawDrawer `.withdraw-drawer` root className 不确定 | subagent grep 实际 className，spec 不锁死 |
| LedgerEntry 字段名 | subagent 必须 grep walletApi.ts 确认实际字段 |

## Out-of-Scope（Phase 3 / 4 / 5）

- KycSubmitDrawer / ProfilePanel mobile → Phase 3
- WithdrawDrawer 历史 table → 卡片转换 → Phase 3 优化或独立
- Activity / Dashboard / Settings page mobile → Phase 3
- 管理端 → Phase 4
- 真机 QA → Phase 5
