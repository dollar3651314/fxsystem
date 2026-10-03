# H5 自适应 Phase 3 设计 — 客户端 P1 features mobile

> 前置：Phase 0+1/2A/2B/2C（merge f892aca）
> 后置 Phase 4：管理端 console-frontend；Phase 5 真机 QA

## Goal

5 个 P1 features mobile 适配（DashboardPage 主要，其他 4 features 大部分已经被 Phase 2B/2C 副作用 cover）：

| 组件 | 实际改造 |
|---|---|
| **DashboardPage** (511 行) | 自有 `.dashboard-page-*` className，需新 mobile @media rules（hero / stat-grid / sections）|
| **ActivityPage** (234 行) | 用 `.fx-console-*` 已 cover；补 `.activity-tabs / .activity-tab` mobile 横滚 + 隐 __num |
| **SettingsPage** (375 行) | 用 `.fx-console-*` 已 cover；form inputs 48px 补充 |
| **ProfilePanel** (347 行) | 用 `.fx-modal-backdrop + .fx-modal.fx-modal--wide` 已 cover；form inputs 48px 补充 |
| **KycSubmitDrawer** (354 行) | 用 `.fx-modal-backdrop + .fx-modal--wide` 已 cover；`.profile-form` mobile inputs 48px 补充 |

桌面 0 regression。**0 JSX 改动**，纯 CSS @media 改造。

## File Changes

### Create

- `falconx-frontend/src/styles/modules/_client-p1.css` — DashboardPage + ActivityPage tabs + profile-form mobile rules

### Modify

- `falconx-frontend/src/styles/global.css` — `@import "./modules/_client-p1.css";` 加在 `_wallet.css` 之后

## Detailed Feature Changes

### `_client-p1.css` 单文件包含

```css
@media (max-width: 767px) {
  /* DashboardPage layout */
  .dashboard-page {
    padding: 0 16px;
  }

  .dashboard-page__console-route {
    font-size: 13px;
    flex-wrap: wrap;
  }

  .dashboard-page__hero {
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
    padding: 12px 0;
  }

  .dashboard-page__hero-sub {
    font-size: 13px;
  }

  .dashboard-page__hero-actions {
    width: 100%;
    display: flex;
    gap: 8px;
  }

  .dashboard-page__hero-actions .fx-btn-secondary,
  .dashboard-page__hero-actions .fx-btn-primary {
    flex: 1;
    min-height: 40px;
  }

  /* dashboard-stat-grid mobile 1 列 */
  .dashboard-stat-grid {
    grid-template-columns: 1fr !important;
    gap: 12px;
  }

  .dashboard-stat-card {
    padding: 12px;
  }

  .dashboard-stat-card__label {
    font-size: 11px;
  }

  .dashboard-stat-card__value {
    font-size: 18px;
  }

  /* ActivityPage tabs mobile 横滚 */
  .activity-tabs {
    overflow-x: auto;
    scrollbar-width: none;
    flex-wrap: nowrap;
    -webkit-overflow-scrolling: touch;
  }

  .activity-tabs::-webkit-scrollbar {
    display: none;
  }

  .activity-tab {
    flex-shrink: 0;
    padding: 8px 12px;
    font-size: 13px;
  }

  .activity-tab__num {
    display: none;
  }

  /* profile-form mobile (Kyc + Profile 共享) */
  .profile-form input,
  .profile-form select,
  .profile-form textarea {
    min-height: 48px;
    font-size: 16px;
  }

  .profile-form textarea {
    min-height: 80px;
  }
}
```

⚠️ subagent 实施时如发现 className 实际不一致（如 `.activity-tab` 实际是其他名），按现状 grep 修正。

## Testing

无新测试（CSS-only，无新组件）。

## Verification

- [ ] DevTools iPhone SE — Dashboard / Activity / Settings / Profile / Kyc 5 个 features mobile 显示无 layout 错乱
- [ ] DashboardPage hero/stat-grid mobile 单列
- [ ] ActivityPage tabs 横滚
- [ ] form inputs 48px / 16px font（防 iOS auto-zoom）
- [ ] 桌面 0 regression
- [ ] `npm run test` 全过
- [ ] `npm run build` 0 error
- [ ] `npm run lint` 0 H5 regression

## Out-of-Scope（Phase 4 / 5）

- 管理端 console-frontend → Phase 4
- 真机 QA → Phase 5
