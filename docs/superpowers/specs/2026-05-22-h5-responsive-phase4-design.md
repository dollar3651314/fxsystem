# H5 自适应 Phase 4 设计 — 管理端 console-frontend mobile

> 前置：Phase 0+1/2A/2B/2C/3（merge d7fc861）
> 后置 Phase 5：iOS Safari / Android Chrome 真机 QA

## Goal

falconx-console-frontend（管理端）mobile 适配。**关键差异**：管理端用 **AntD (Ant Design)** 组件库，不是自有 fx-* CSS 系统。AntD 内置响应式，主要工作是：
1. viewport-fit=cover（safe-area）
2. AntD Table / Form / Modal / Drawer mobile 友好 CSS overrides
3. 整体 layout 紧凑 padding

## File Changes

### Modify

- `falconx-console-frontend/index.html` — viewport 加 `viewport-fit=cover`
- `falconx-console-frontend/src/styles/global.css` — 加 mobile @media block（AntD overrides + safe-area + touch friendly）

桌面 0 regression。0 JSX 改动。

## Detailed

### viewport-fit=cover

```html
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
```

### global.css mobile rules

在 console-frontend `global.css` 末尾追加：

```css
/* ============ FalconX H5 — console mobile 适配 ============
 * AntD 组件 mobile 友好 overrides + safe-area。
 */

@media (max-width: 767px) {
  /* AntD Layout safe-area */
  .ant-layout {
    padding-top: env(safe-area-inset-top);
    padding-bottom: env(safe-area-inset-bottom);
  }

  /* AntD Form labels + inputs mobile */
  .ant-form-item-label {
    padding-bottom: 4px !important;
  }

  .ant-input,
  .ant-input-password,
  .ant-select-selector,
  .ant-input-number-input,
  .ant-picker-input > input {
    min-height: 40px !important;
    font-size: 16px !important; /* 防 iOS auto-zoom */
  }

  /* AntD Table 横滚 (mobile 不可能全列显示) */
  .ant-table-wrapper,
  .ant-table {
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
  }

  .ant-table-cell {
    padding: 8px !important;
    font-size: 13px;
  }

  /* AntD Modal mobile 全屏化 */
  .ant-modal {
    max-width: calc(100vw - 16px);
    margin: 8px auto;
  }

  .ant-modal-body {
    max-height: 70vh;
    overflow-y: auto;
  }

  /* AntD Drawer mobile 全宽 */
  .ant-drawer-right .ant-drawer-content-wrapper,
  .ant-drawer-left .ant-drawer-content-wrapper {
    width: 100vw !important;
    max-width: 100vw;
  }

  /* AntD Button touch target */
  .ant-btn {
    min-height: 36px;
  }

  .ant-btn-lg {
    min-height: 44px;
  }

  /* AntD Pagination mobile */
  .ant-pagination {
    text-align: center;
  }

  .ant-pagination-item,
  .ant-pagination-prev,
  .ant-pagination-next {
    min-width: 32px;
    min-height: 32px;
    line-height: 30px;
  }

  /* AntD Tabs mobile 横滚 */
  .ant-tabs-nav-list {
    overflow-x: auto;
    flex-wrap: nowrap;
  }

  /* Sider mobile 收纳 */
  .ant-layout-sider {
    position: fixed !important;
    z-index: 100;
  }
}
```

## Verification

- [ ] index.html viewport-fit=cover 已加
- [ ] DevTools iPhone SE — 管理端 login / dashboard / 各 list page mobile 可用
- [ ] AntD Form input 40px / font 16px
- [ ] AntD Table 横滚
- [ ] AntD Modal / Drawer mobile 全宽
- [ ] DevTools 1440 桌面 0 regression
- [ ] console-frontend build / test 全过

## Out-of-Scope（Phase 5）

- 真机 QA
- 深度定制每个 page（YAGNI — AntD 默认 mobile 体验已经过得去）
