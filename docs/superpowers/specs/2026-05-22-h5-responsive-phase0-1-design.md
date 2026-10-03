# FalconX H5 自适应改造 — Phase 0+1 设计

> 状态：**待用户审核**
> 范围：基础设施层（断点 token + hook + CSS 模块化 + 3 个 mobile shell 组件）
> 不含本 spec：具体 feature 页面改造（Phase 2-5）— 各自独立 brainstorm

## 1. 整体路线图（5 个 Phase）

| Phase | 范围 | 工作量 | 何时做 |
|---|---|---|---|
| **0. 基础设施** | 断点 token / hook / CSS 拆模块 / 3 mobile shell 组件 | 1-2 天 | **本 spec** |
| **1. 框架壳子** | 客户端 App shell（BottomNav + AdminTopbar mobile + 顶部 header）改造为 responsive | 1-2 天 | **本 spec** |
| 2. 客户端 P0 features | terminal / market / trading / auth / wallet 五个高频页面 mobile 适配 | 3-5 天 | 单独 spec |
| 3. 客户端 P1 features | withdraw / kyc / activity / dashboard / order-ticket / profile / settings | 2-4 天 | 单独 spec |
| 4. 管理端 H5 | console 25+ 页面 mobile 适配（表格→卡片，drawer 导航） | 3-5 天 | 单独 spec |
| 5. QA + 真机 | iOS Safari / Android Chrome 多尺寸验证 + 性能 + lint | 1-2 天 | 单独 spec |

**Phase 0+1 是所有后续 Phase 的依赖**。它们交付后，每个 feature 改造只是引用基础设施 + 写 mobile 布局，效率倍增。

## 2. Phase 0+1 范围

### Phase 0 — 基础设施层（5 件交付物）

1. **断点 token 系统**：`src/styles/tokens.css` 新增标准 4 档（sm/md/lg/xl）+ 配套 CSS variable
2. **useBreakpoint hook**：`src/lib/responsive/useBreakpoint.ts` + `useMediaQuery.ts`
3. **CSS 模块化拆分**：`global.css`（5500 行 monolithic）拆为 `_base.css / _layout.css / _terminal.css / _trading.css / _auth.css / _mobile.css` 等
4. **Mobile shell 3 组件**：
   - `<BottomNav />` 底部 tab 栏（mobile only）
   - `<Drawer />` 侧边抽屉（hamburger 触发）
   - `<Sheet />` 半屏弹层（下单 / 详情 / 表单）
5. **viewport 元数据 + safe-area-inset 处理**：`index.html` 加 `viewport-fit=cover` + CSS `env(safe-area-inset-*)` token

### Phase 1 — 客户端 App Shell 改造（3 件交付物）

1. **`TerminalTopbar` 改 responsive**：< md 隐藏次要按钮，整合进 Drawer
2. **`<MobileShell />` wrapper**：< md 时包裹页面，提供 BottomNav + Drawer 联动；≥ md 退化为 fragment 不影响桌面
3. **路由配置**：底部 nav 5 tab 路由（行情 / 交易 / 钱包 / 活动 / 我的）

## 3. 设计 — 断点 token 系统

### 3.1 标准 4 档（参照 Tailwind 通用值，与现有 Quant Terminal 视觉契合）

```css
/* src/styles/tokens.css 新增 */
:root {
  /* H5 自适应断点 — Phase 0 定义，所有 media query 引用 */
  --fx-breakpoint-sm: 640px;   /* 手机大屏 / 小屏 phablet */
  --fx-breakpoint-md: 768px;   /* 平板竖屏 */
  --fx-breakpoint-lg: 1024px;  /* 平板横屏 / 小桌面 */
  --fx-breakpoint-xl: 1280px;  /* 桌面 */

  /* safe-area（刘海屏 / 底部 home indicator）*/
  --fx-safe-top: env(safe-area-inset-top, 0);
  --fx-safe-bottom: env(safe-area-inset-bottom, 0);
  --fx-safe-left: env(safe-area-inset-left, 0);
  --fx-safe-right: env(safe-area-inset-right, 0);

  /* H5 容器固定尺寸 */
  --fx-bottom-nav-height: 56px;
  --fx-drawer-width: 280px;
  --fx-sheet-handle-height: 32px;
}
```

### 3.2 CSS media query 约定（**禁止再硬编码数字**）

CSS variable 不能用在 media query 内部。本 spec 简化策略：

```css
/* 所有新写 CSS 必须用下面 4 个值之一 */
@media (max-width: 639px) { /* < sm，纯 mobile */ }
@media (max-width: 767px) { /* < md，mobile + 小 phablet */ }
@media (max-width: 1023px) { /* < lg，含平板 */ }
@media (min-width: 1024px) { /* ≥ lg，桌面 */ }
```

未来可引入 PostCSS `@custom-media` 让断点 token 化（不在本 spec）。

### 3.3 旧断点迁移

现有 6 个断点 `1400 / 1240 / 980 / 880 / 720 / 560` 在 Phase 0 内：
- 980 → 768（md）
- 720 → 640（sm）
- 560 → 暂保留（边缘 case）
- 1400 / 1240 / 880 / 720 → 评估后归并到 4 档

> 此迁移不在本 spec 强制完成（Phase 2 各 feature 改造时顺手迁），但**新 CSS 一律用 4 档**。

## 4. 设计 — useBreakpoint hook

```ts
// src/lib/responsive/useBreakpoint.ts
export type Breakpoint = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

export function useBreakpoint(): {
  current: Breakpoint;            // 'xs'=<640, 'sm'=640-767, 'md'=768-1023, 'lg'=1024-1279, 'xl'=>=1280
  isMobile: boolean;              // < md（< 768）
  isTablet: boolean;              // [md, lg)
  isDesktop: boolean;             // ≥ lg
  width: number;
}

export function useMediaQuery(query: string): boolean;
// 用法：const isLandscape = useMediaQuery('(orientation: landscape)')
```

### 4.1 SSR 安全 + 性能

- 用 `window.matchMedia` + `addEventListener('change')`
- 初值在 SSR 默认 `desktop`（前端是 CSR，影响小）
- 单次 mount 注册 4 个 mq listener，change 节流到 RAF
- 提供 default export `useBreakpoint()` 让所有组件复用一份 state（避免每个组件单独 matchMedia）

## 5. 设计 — CSS 模块化拆分

`global.css` 5500 行 monolithic → 拆成下面结构：

```
src/styles/
├── tokens.css                  # 颜色 / 字号 / 间距 / 断点 / safe-area
├── global.css                  # 仅 @import + body/html base + 全局 reset
├── modules/
│   ├── _base.css               # reset / typography / button / input 基础
│   ├── _auth.css               # 登录注册页（约 400 行）
│   ├── _terminal.css           # 交易终端主屏（约 1500 行）
│   ├── _trading.css            # 持仓 / 订单 / 下单 modal（约 800 行）
│   ├── _market.css             # 行情列表 / K 线（约 600 行）
│   ├── _wallet.css             # 钱包 / 入金 / 出金（约 500 行）
│   ├── _kyc.css                # KYC（约 300 行）
│   ├── _profile.css            # profile / settings / activity（约 400 行）
│   └── _mobile-shell.css       # BottomNav / Drawer / Sheet（约 500 行）
```

**拆分原则**：
- 按 feature 拆，不按"组件类型"拆（更容易 grep 找到）
- 每个 module 内自己写 `@media` 处理 responsive，不依赖外部
- `global.css` 通过 `@import './modules/_xxx.css'` 串联（Vite 处理 + 合并）

**Phase 0 拆分范围**：完成 `_base.css` + `_mobile-shell.css` + 提取出剩余 feature 文件的骨架（内容慢慢从 global.css 搬，**搬完即可在 Phase 2 各 feature 改 mobile**）。

## 6. 设计 — Mobile shell 3 组件

### 6.1 `<BottomNav />`

5 个 tab，固定底部，仅 `< md` 显示：

```
┌─────────────────────────┐
│   主内容滚动区          │
│                         │
├─────────────────────────┤
│ 行情  交易  钱包 活动 我│  ← BottomNav (height: 56px + safe-area)
└─────────────────────────┘
```

接口：
```ts
type BottomNavItem = {
  key: string;
  path: string;
  label: string;     // "行情"
  icon: ReactNode;
  badge?: number;    // 红点
};

<BottomNav items={...} active={pathname} onChange={navigate} />
```

样式：
- 固定 `position: fixed; bottom: 0`
- 高度 56px + `padding-bottom: env(safe-area-inset-bottom)`
- 半透明背景 + backdrop-filter（沿用 Quant Terminal 玻璃感）
- active 颜色 `var(--fx-cyan)`
- ≥ md 自动 `display: none`

### 6.2 `<Drawer />`

侧边抽屉，左侧滑入：

```
┌──────┬──────────────────┐
│      │   主内容          │
│ Drawer │                 │
│ 设置  │                 │
│ KYC   │                 │
│ 出金  │                 │
│ 登出  │                 │
└──────┴──────────────────┘
```

接口：
```ts
<Drawer open={boolean} onClose={() => void} position="left" width={280}>
  {/* 子内容 */}
</Drawer>
```

行为：
- 触发：TerminalTopbar 上的 hamburger 图标（仅 `< md` 显示 hamburger）
- 关闭：背景蒙层点击 / 内部 close 按钮 / ESC
- 滑入动画 200ms ease-out
- focus trap + ESC 关闭（a11y）

### 6.3 `<Sheet />`

半屏弹层，从下方滑入，用于下单 / 详情 / 表单：

```
┌─────────────────────────┐
│   主内容（遮罩）         │
├─────────────────────────┤
│   ───                   │ ← drag handle
│   下单表单 / 持仓详情    │
└─────────────────────────┘
```

接口：
```ts
<Sheet open={boolean} onClose={...} height="60vh" snapPoints={['30vh','60vh','95vh']}>
  {children}
</Sheet>
```

行为：
- 拖拽 handle 改变 snap point
- 向下拖关闭（velocity 触发）
- 仅 `< md` 显示为 sheet；`≥ md` 自动退化为传统 Modal

> 不引入新依赖，纯手写。可参考 vaul/sonner 思路。

## 7. 设计 — Phase 1 App Shell 改造

### 7.1 `<MobileShell />` wrapper

```tsx
// src/features/terminal/MobileShell.tsx
export function MobileShell({ children }: { children: ReactNode }) {
  const { isMobile } = useBreakpoint();
  const [drawerOpen, setDrawerOpen] = useState(false);
  
  if (!isMobile) return <>{children}</>;   // 桌面退化
  
  return (
    <div className="fx-mobile-shell">
      <header className="fx-mobile-header">
        <button onClick={() => setDrawerOpen(true)} aria-label="menu">☰</button>
        <Logo />
        <UserAvatar />
      </header>
      <main className="fx-mobile-main">{children}</main>
      <BottomNav items={NAV_ITEMS} active={pathname} onChange={navigate} />
      <Drawer open={drawerOpen} onClose={() => setDrawerOpen(false)}>
        <DrawerMenu />
      </Drawer>
    </div>
  );
}
```

### 7.2 路由层挂载

`App.tsx` 在 `<Route element={<TerminalLayout />}>` 外层包 `<MobileShell />`，让所有需要 nav 的页面自动得到 mobile shell（auth 页除外）。

### 7.3 5 个底部 tab 路由

| Tab | Path | Icon |
|---|---|---|
| 行情 | `/market` | line-chart |
| 交易 | `/trading`（默认 → terminal） | candle-stick |
| 钱包 | `/wallet` | wallet |
| 活动 | `/activity` | history |
| 我的 | `/profile` | user |

## 8. 关键交互模式（指导 Phase 2+）

| 桌面模式 | Mobile 对应模式 | 例子 |
|---|---|---|
| 数据表格（多列） | 卡片堆叠（垂直） | 持仓列表、订单列表 |
| 侧边导航 | Drawer + hamburger | TerminalTopbar 菜单 |
| 多列 grid 布局 | 单列 flex 堆叠 | terminal 主屏 |
| 弹窗 Modal | Sheet 半屏 | 下单、持仓详情 |
| 多 tab 横向 | 横向滚动 / 折叠 | 行情 tab、订单 tab |
| 悬浮 tooltip | 长按 popover | 数据 label 解释 |

这些模式在 Phase 2-4 各 feature 改造时**作为指导原则**，不在 Phase 0 强制实施。

## 9. 测试矩阵

### 9.1 Phase 0+1 验证

| 维度 | 标准 |
|---|---|
| `npm run lint` | 0 warning |
| `npm run test` | 现有测试不破坏（仅 hook 测试新增 ≥ 3） |
| `npm run build` | 0 error，bundle 增量 < 30KB |
| Chrome DevTools 5 尺寸 | 360 / 414 / 768 / 1024 / 1440 — BottomNav/Drawer/Sheet 行为正常 |
| 真机测试 | iPhone Safari 1 台 + Android Chrome 1 台烟雾 |

### 9.2 后续 Phase 2-5 各自验证

每个 feature 改造 Phase 在自己的 spec 中定义测试矩阵。

## 10. DoD（完成定义）

### Phase 0
- [ ] `tokens.css` 新增 4 档 + safe-area + mobile shell sizing variables
- [ ] `useBreakpoint.ts` + `useMediaQuery.ts` 实现 + 单元测试
- [ ] `global.css` 重组：拆 `_base.css` + `_mobile-shell.css` + 各 feature 文件骨架（内容未必全搬）
- [ ] `<BottomNav />` + `<Drawer />` + `<Sheet />` 三组件 + demo 页面（Storybook 风格）
- [ ] `index.html` viewport-fit=cover + safe-area 处理

### Phase 1
- [ ] `<MobileShell />` wrapper 在 App.tsx 接入
- [ ] TerminalTopbar 加 hamburger（`< md` 显示）
- [ ] 5 个 tab 路由验证可点
- [ ] `< md` 时桌面 layout 自动消失，mobile shell 接管
- [ ] `≥ md` 时 mobile shell 退化为 fragment，桌面体验 0 影响

## 11. 风险 + 缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| `global.css` 拆分时遗漏选择器 | 桌面端样式破坏 | Phase 0 拆 base + shell，feature 样式留原位（Phase 2 再搬） |
| BottomNav 在交易终端遮挡持仓列表 | 信息可见性 | `padding-bottom: calc(56px + safe-area)` 给主滚动区，列表底部不被压 |
| Sheet 拖拽性能差（动画 jank） | UX 差 | 用 `transform: translate3d` 而非 top/height，触发 GPU 合成 |
| Drawer focus trap 不全 | a11y 失败 | 用 `inert` attribute + `aria-modal` + 显式 tabindex 管理 |
| K 线 chart 在 mobile 横屏才好用 | 体验 | mobile 默认竖屏 chart 减小 height，提示横屏；不在 Phase 0 解决，Phase 2 terminal 处理 |
| iOS Safari `100vh` 不准 | 布局错位 | 改用 `100dvh` (dynamic viewport)，fallback `100vh` |

## 12. 实施顺序

1. tokens.css 4 档断点 + safe-area
2. useBreakpoint + useMediaQuery + 测试
3. `_base.css` + `_mobile-shell.css` 拆出
4. BottomNav 组件 + demo
5. Drawer 组件 + demo
6. Sheet 组件 + demo
7. MobileShell wrapper
8. App.tsx 接入 + 5 tab 路由
9. TerminalTopbar hamburger 改造
10. 5 尺寸 Chrome DevTools + 1 真机烟雾

---

## 后续 Phase 路线（不在本 spec 实施，仅记录）

- Phase 2: `2026-MM-DD-h5-phase2-client-core-design.md`（5 个 P0 features）
- Phase 3: `2026-MM-DD-h5-phase3-client-secondary-design.md`（其他 7 features）
- Phase 4: `2026-MM-DD-h5-phase4-admin-design.md`（管理端 25+ 页面）
- Phase 5: `2026-MM-DD-h5-phase5-qa-design.md`（QA + 真机 + 性能）
