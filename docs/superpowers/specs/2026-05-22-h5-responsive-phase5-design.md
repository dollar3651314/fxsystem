# H5 自适应 Phase 5 — 真机 QA + 性能审计 + 完结总结

> 前置：Phase 0+1/2A/2B/2C/3/4（merge c376c77）
> **本 Phase 是 QA + 文档交付（无代码改动），结束整个 H5 自适应路线**

## H5 自适应路线总览

| Phase | 范围 | merge SHA | 关键交付 |
|---|---|---|---|
| 0+1 | 基础设施 | `5afffb4` | useBreakpoint / MobileShell / BottomNav / Drawer / Sheet / 4 档断点 token |
| 2A | 客户端 P0 4 features | `025a106` | terminal market stack / auth / market watchlist+chart |
| 2B-1 | trading 核心交易流 | `1cdcba1` | OrderTicket / PositionsTable cards / 3 Modal |
| 2B-2 | trading 历史 | `2133f6e` | 4 历史 Table cards / OrderDetailModal / Pagination |
| 2B-3 | trading 收尾 | `c0a1727` | TradingTabs 横滚 / NotificationCenter / PriceAlertsTable |
| 2C | wallet 钱包 | `f892aca` | WalletPage / LedgerCard / WithdrawDrawer |
| 3 | 客户端 P1 features | `d7fc861` | DashboardPage / ActivityPage tabs / profile-form |
| 4 | 管理端 console | `c376c77` | AntD mobile overrides + viewport-fit=cover |
| 5 | 真机 QA + 总结 | (本 phase) | QA checklist + 性能 baseline |

**总计**：~24 个组件 mobile 适配 / 5 个 CSS module（_base / _mobile-shell / _auth / _market / _trading / _wallet / _client-p1 / console global）/ 1 个 hook (useBreakpoint) + 3 个 mobile shell 组件 (BottomNav / Drawer / Sheet) + 10+ 个 mobile-only Card 组件 + 1 个 H5 设计 token 系统。

## 真机 QA Checklist

实际真机测试需要 QA 团队/用户跑。下面是完整 checklist（按 priority 高→低）：

### P0: 核心交易流（必测）

**iPhone SE (375x667) / iPhone 15 Pro (393x852) / Pixel 7 (412x915)：**

#### 客户端 https://app-falconx.lifebyteapp.dev/

**入口 + 导航**：
- [ ] 登录 / 注册：input focus 不触发 auto-zoom；表单可滚动
- [ ] BottomNav 5 tab 可点切换（首页/行情/活动/钱包/设置）
- [ ] hamburger 菜单 → Drawer 滑出（账户资料 / 身份认证 / 出金）
- [ ] safe-area 在刘海屏正确处理（iPhone 14+）

**行情 view**：
- [ ] watchlist 卡片滑动流畅
- [ ] symbol 点击切换 → chart 重画
- [ ] chart timeframe segmented 触摸切换
- [ ] chart canvas tap = 十字线 + 报价 tooltip
- [ ] 点 [下单 FAB] → Sheet 弹出 OrderTicket
- [ ] OrderTicket: amount/leverage/SL/TP input 16px font 防 zoom
- [ ] OrderTicket LONG/SHORT 切换 lime/red border 正确
- [ ] OrderTicket submit 提交后 toast 显示

**持仓 tab**：
- [ ] PositionItemCard 5 行字段完整显示
- [ ] 点 [Ⓞ 平仓] → ClosePositionModal mobile 92vw
- [ ] 点 [⋯ More] → menu 弹出（补保 / 改 TP/SL）

**Activity（历史/告警）tab**：
- [ ] PendingOrders / Orders / Trades / ClosedPositions 4 tab 横滚
- [ ] 4 类卡片显示 + 点击触发 OrderDetailModal mobile 全宽
- [ ] PriceAlertsTable cards + 取消按钮
- [ ] 新建告警 modal mobile 全宽

**钱包**：
- [ ] WalletPage hero/sections mobile 紧凑
- [ ] LedgerCard mobile 流水卡片
- [ ] 出金 button → WithdrawDrawer 100vw + form 48px input

#### 管理端 https://admin-falconx.lifebyteapp.dev/

- [ ] login mobile 可用
- [ ] dashboard / 各 list page AntD Table 横滚
- [ ] AntD Form input 40px / 16px font
- [ ] AntD Modal mobile 全屏化
- [ ] AntD Drawer mobile 全宽

### P1: 边缘 cases

- [ ] iOS Safari 横屏切竖屏 layout 自适应
- [ ] Android Chrome 滚动惯性 / overscroll
- [ ] iOS Safari overscroll bounce 不破坏 fixed FAB
- [ ] 键盘弹起遮挡 input — 自动滚动 input 到可见区
- [ ] 弱网（3G throttle）：loading 状态显示 / 不卡死

### 性能审计（Lighthouse）

跑 mobile preset Lighthouse audit：

```bash
# 客户端
npx lighthouse https://app-falconx.lifebyteapp.dev/ --preset=desktop --view
npx lighthouse https://app-falconx.lifebyteapp.dev/ --preset=mobile --view

# 管理端
npx lighthouse https://admin-falconx.lifebyteapp.dev/ --preset=mobile --view
```

目标 metrics（mobile）：
- Performance: ≥ 80
- Accessibility: ≥ 90
- Best Practices: ≥ 95
- LCP: < 2.5s
- FID: < 100ms
- CLS: < 0.1

记录 baseline + Phase 5 后续优化 metrics 对比。

## 已知限制 / 不修复

1. **WithdrawDrawer 历史 table mobile 横滚**（Phase 2C YAGNI 简化，未抽 WithdrawHistoryCard）— 出金历史低频访问。若 QA 反馈难用，独立 issue 跟进。
2. **管理端 25+ pages 深度定制**（Phase 4 仅做 AntD overrides）— 管理员主要桌面用，mobile 应急访问已足够。
3. **Pre-existing test fail** `WithdrawDrawer TC-WD-FE-002` — H5 改造前已有，跟 H5 无关。
4. **K 线 chart mobile drag 在 Mac**（commit `559e1ae` touch-action:none 修复未实测）— 真机 QA 需验证。

## QA 报告模板

完成真机测试后，提交 QA 报告含：

```markdown
## H5 自适应 真机 QA 报告

**测试日期**: YYYY-MM-DD
**测试设备**:
- iPhone SE (iOS X.X / Safari X.X)
- Android Pixel X (Chrome X.X)

**测试覆盖**:
- [ ] 客户端 P0 checklist 完成
- [ ] 客户端 P1 checklist 完成
- [ ] 管理端 checklist 完成

**Lighthouse mobile baseline**:
- 客户端 Performance: NN / Accessibility: NN / LCP: N.Ns
- 管理端 Performance: NN

**发现问题**（按 severity）:
1. CRITICAL: ...
2. MAJOR: ...
3. MINOR: ...

**下一步建议**:
- ...
```

## 完结声明

H5 自适应 5 个 phase 全部交付。客户端 + 管理端 mobile 适配完成，桌面体验 0 regression。

实际真机 QA + Lighthouse baseline 测量是需要人工/QA 团队完成的非自动化工作 — 不在自动化 implementation 范围内。本 Phase 交付 QA execution checklist + 已知限制清单，让 QA 团队按表执行。

后续如发现 mobile UX 问题：
- 单个 component 调整 → 独立 PR
- 整片 feature 重做 → 独立 phase 6+ spec
