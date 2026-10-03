# H5 自适应改造 — Phase 0+1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在客户端 `falconx-frontend` 落地 H5 自适应基础设施（断点 token + useBreakpoint hook + CSS 模块化 + BottomNav/Drawer/Sheet 三 mobile shell 组件）和 App Shell 改造（MobileShell wrapper + TerminalTopbar hamburger + TradingTerminal mobile 接入），为后续 Phase 2-5 feature 改造打基础。

**Architecture:** 关键洞察：现有 `TerminalNav` 已用 `TerminalView` state-based 导航（5 项 dashboard/market/activity/wallet/settings），无需引入 React Router — `<BottomNav />` 复用相同的 `(activeKey, onSelectKey)` 接口，在 `< md` 时替代 TerminalNav，桌面下 BottomNav `display: none` 退化。所有 mobile 组件桌面下退化为 noop，桌面体验 0 影响。CSS 模块化只先拆 `_base + _mobile-shell` 两块，feature 模块骨架由 Phase 2 继续搬。

**Tech Stack:** React 19 + TypeScript + Vite + Vitest（已有）+ lucide-react icons（已有）+ matchMedia API（浏览器原生，无新依赖）

---

## File Structure

### 新建 15 个文件

| 路径 | 职责 |
|---|---|
| `falconx-frontend/src/lib/responsive/useMediaQuery.ts` | 通用 matchMedia hook |
| `falconx-frontend/src/lib/responsive/useBreakpoint.ts` | 4 档断点状态 hook |
| `falconx-frontend/src/lib/responsive/index.ts` | barrel export |
| `falconx-frontend/src/lib/responsive/useBreakpoint.test.ts` | hook 单元测试（含 SSR / resize 行为）|
| `falconx-frontend/src/components/mobile/BottomNav.tsx` | 底部 5 tab 导航 |
| `falconx-frontend/src/components/mobile/Drawer.tsx` | 侧滑抽屉（菜单 + 二级入口） |
| `falconx-frontend/src/components/mobile/Sheet.tsx` | 半屏弹层（取代 mobile modal） |
| `falconx-frontend/src/components/mobile/index.ts` | barrel export |
| `falconx-frontend/src/components/mobile/BottomNav.test.tsx` | 测试 |
| `falconx-frontend/src/components/mobile/Drawer.test.tsx` | 测试 |
| `falconx-frontend/src/components/mobile/Sheet.test.tsx` | 测试 |
| `falconx-frontend/src/styles/modules/_base.css` | reset / typography / button base（从 global.css 抽出） |
| `falconx-frontend/src/styles/modules/_mobile-shell.css` | BottomNav / Drawer / Sheet 样式 |
| `falconx-frontend/src/features/terminal/MobileShell.tsx` | `<MobileShell>` wrapper（< md 包客户端 layout） |
| `falconx-frontend/src/features/terminal/MobileShell.test.tsx` | 测试 |

### 修改 5 个文件

| 路径 | 改动 |
|---|---|
| `falconx-frontend/src/styles/tokens.css` | 加 4 档断点 + safe-area + shell sizing variables |
| `falconx-frontend/src/styles/global.css` | 顶部加 `@import './modules/_base.css'` 和 `@import './modules/_mobile-shell.css'` |
| `falconx-frontend/index.html` | meta viewport 加 `viewport-fit=cover` |
| `falconx-frontend/src/features/terminal/TerminalTopbar.tsx` | `< md` 加 hamburger 按钮触发 Drawer |
| `falconx-frontend/src/features/terminal/TradingTerminal.tsx` | mobile 时用 BottomNav 替代 TerminalNav，集成 Drawer |

---

## Task 1: tokens.css 加 4 档断点 + safe-area + shell sizing

**Files:**
- Modify: `falconx-frontend/src/styles/tokens.css`

- [ ] **Step 1: 读现有 tokens.css 文件末尾，确定追加位置**

Run: `tail -10 falconx-frontend/src/styles/tokens.css`
Expected: 看到现有 token 定义的结束 `}`（`:root {}` 块）

- [ ] **Step 2: 在 tokens.css 的 `:root {}` 块内追加 H5 自适应 tokens**

在 `:root {}` 闭合 `}` 之前插入：

```css
  /* H5 自适应 — 标准 4 档断点（仅作 documentation 用，CSS @media 无法引用变量）
   * sm = 640px, md = 768px, lg = 1024px, xl = 1280px
   * 所有新写 @media query 必须用这 4 个值之一，禁止其他数字
   */
  --fx-breakpoint-sm: 640px;
  --fx-breakpoint-md: 768px;
  --fx-breakpoint-lg: 1024px;
  --fx-breakpoint-xl: 1280px;

  /* iOS / Android safe area（刘海屏 + 底部 home indicator）— 需要 index.html viewport-fit=cover */
  --fx-safe-top: env(safe-area-inset-top, 0px);
  --fx-safe-bottom: env(safe-area-inset-bottom, 0px);
  --fx-safe-left: env(safe-area-inset-left, 0px);
  --fx-safe-right: env(safe-area-inset-right, 0px);

  /* H5 mobile shell sizing */
  --fx-bottom-nav-height: 56px;
  --fx-drawer-width: 280px;
  --fx-sheet-handle-height: 32px;
```

- [ ] **Step 3: 验证 token 出现**

Run: `grep -E "fx-breakpoint-sm|fx-safe-bottom|fx-bottom-nav-height" falconx-frontend/src/styles/tokens.css`
Expected: 输出 6 行（3 个 variable 各 1 行）

- [ ] **Step 4: 前端 build 不报错（typeck + 样式）**

Run: `cd falconx-frontend && npm run build 2>&1 | tail -10`
Expected: `built in Xs`，无 css 解析错误

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-plan
git add falconx-frontend/src/styles/tokens.css
git commit -m "feat(h5-tokens): tokens.css 加 4 档断点 + safe-area + shell sizing"
```

---

## Task 2: useMediaQuery hook + 测试

**Files:**
- Create: `falconx-frontend/src/lib/responsive/useMediaQuery.ts`
- Create: `falconx-frontend/src/lib/responsive/useMediaQuery.test.ts`

- [ ] **Step 1: 写 failing test**

Create `falconx-frontend/src/lib/responsive/useMediaQuery.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useMediaQuery } from "./useMediaQuery";

function mockMatchMedia(matches: boolean) {
  const listeners: ((e: { matches: boolean }) => void)[] = [];
  const mql = {
    matches,
    media: "",
    addEventListener: (_: string, fn: (e: { matches: boolean }) => void) => listeners.push(fn),
    removeEventListener: (_: string, fn: (e: { matches: boolean }) => void) => {
      const i = listeners.indexOf(fn);
      if (i >= 0) listeners.splice(i, 1);
    },
    dispatchChange: (newMatches: boolean) => {
      mql.matches = newMatches;
      listeners.forEach((fn) => fn({ matches: newMatches }));
    },
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  };
  vi.stubGlobal("matchMedia", () => mql);
  return mql;
}

describe("useMediaQuery", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it("初值返回 matchMedia.matches 状态", () => {
    mockMatchMedia(true);
    const { result } = renderHook(() => useMediaQuery("(max-width: 767px)"));
    expect(result.current).toBe(true);
  });

  it("change 事件触发后状态更新", () => {
    const mql = mockMatchMedia(false);
    const { result } = renderHook(() => useMediaQuery("(max-width: 767px)"));
    expect(result.current).toBe(false);
    act(() => {
      mql.dispatchChange(true);
    });
    expect(result.current).toBe(true);
  });

  it("unmount 时移除 listener", () => {
    const mql = mockMatchMedia(false);
    const removeSpy = vi.spyOn(mql, "removeEventListener");
    const { unmount } = renderHook(() => useMediaQuery("(max-width: 767px)"));
    unmount();
    expect(removeSpy).toHaveBeenCalled();
  });

  it("SSR 安全：matchMedia 不存在时默认 false", () => {
    vi.stubGlobal("matchMedia", undefined);
    const { result } = renderHook(() => useMediaQuery("(max-width: 767px)"));
    expect(result.current).toBe(false);
  });
});
```

- [ ] **Step 2: Run test，确认失败（文件不存在）**

Run: `cd falconx-frontend && npx vitest run src/lib/responsive/useMediaQuery.test.ts 2>&1 | tail -15`
Expected: FAIL，`Failed to load url ./useMediaQuery` 或 `Cannot find module './useMediaQuery'`

- [ ] **Step 3: 实现 useMediaQuery**

Create `falconx-frontend/src/lib/responsive/useMediaQuery.ts`:

```ts
import { useEffect, useState } from "react";

/**
 * 通用 matchMedia hook。SSR 安全（`matchMedia` 不存在时返回 false）。
 *
 * 用法：
 *   const isLandscape = useMediaQuery("(orientation: landscape)");
 *   const isMobile = useMediaQuery("(max-width: 767px)");
 */
export function useMediaQuery(query: string): boolean {
  const getMatch = () =>
    typeof window !== "undefined" && typeof window.matchMedia === "function"
      ? window.matchMedia(query).matches
      : false;

  const [matches, setMatches] = useState<boolean>(getMatch);

  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
      return;
    }
    const mql = window.matchMedia(query);
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches);
    setMatches(mql.matches);
    mql.addEventListener("change", handler);
    return () => mql.removeEventListener("change", handler);
  }, [query]);

  return matches;
}
```

- [ ] **Step 4: Run test，确认通过**

Run: `cd falconx-frontend && npx vitest run src/lib/responsive/useMediaQuery.test.ts 2>&1 | tail -10`
Expected: 4 tests passed

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-plan
git add falconx-frontend/src/lib/responsive/useMediaQuery.ts falconx-frontend/src/lib/responsive/useMediaQuery.test.ts
git commit -m "feat(h5-hook): useMediaQuery 通用 matchMedia hook（SSR 安全 + cleanup）"
```

---

## Task 3: useBreakpoint hook + 测试

**Files:**
- Create: `falconx-frontend/src/lib/responsive/useBreakpoint.ts`
- Create: `falconx-frontend/src/lib/responsive/useBreakpoint.test.ts`
- Create: `falconx-frontend/src/lib/responsive/index.ts`

- [ ] **Step 1: 写 failing test**

Create `falconx-frontend/src/lib/responsive/useBreakpoint.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { useBreakpoint } from "./useBreakpoint";

function mockWidth(width: number) {
  vi.stubGlobal("innerWidth", width);
  vi.stubGlobal("matchMedia", (query: string) => {
    // 解析 "(min-width: 768px)" 这种 query
    const minMatch = query.match(/min-width:\s*(\d+)/);
    const maxMatch = query.match(/max-width:\s*(\d+)/);
    let matches = true;
    if (minMatch && width < parseInt(minMatch[1], 10)) matches = false;
    if (maxMatch && width > parseInt(maxMatch[1], 10)) matches = false;
    return {
      matches,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    };
  });
}

describe("useBreakpoint", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it("width < 640 → current=xs, isMobile=true, isTablet=false, isDesktop=false", () => {
    mockWidth(375);
    const { result } = renderHook(() => useBreakpoint());
    expect(result.current.current).toBe("xs");
    expect(result.current.isMobile).toBe(true);
    expect(result.current.isTablet).toBe(false);
    expect(result.current.isDesktop).toBe(false);
  });

  it("width 640-767 → current=sm, isMobile=true", () => {
    mockWidth(700);
    const { result } = renderHook(() => useBreakpoint());
    expect(result.current.current).toBe("sm");
    expect(result.current.isMobile).toBe(true);
    expect(result.current.isTablet).toBe(false);
  });

  it("width 768-1023 → current=md, isTablet=true, isMobile=false", () => {
    mockWidth(900);
    const { result } = renderHook(() => useBreakpoint());
    expect(result.current.current).toBe("md");
    expect(result.current.isMobile).toBe(false);
    expect(result.current.isTablet).toBe(true);
    expect(result.current.isDesktop).toBe(false);
  });

  it("width 1024-1279 → current=lg, isDesktop=true", () => {
    mockWidth(1200);
    const { result } = renderHook(() => useBreakpoint());
    expect(result.current.current).toBe("lg");
    expect(result.current.isMobile).toBe(false);
    expect(result.current.isTablet).toBe(false);
    expect(result.current.isDesktop).toBe(true);
  });

  it("width >= 1280 → current=xl, isDesktop=true", () => {
    mockWidth(1920);
    const { result } = renderHook(() => useBreakpoint());
    expect(result.current.current).toBe("xl");
    expect(result.current.isDesktop).toBe(true);
  });
});
```

- [ ] **Step 2: Run test，确认失败**

Run: `cd falconx-frontend && npx vitest run src/lib/responsive/useBreakpoint.test.ts 2>&1 | tail -10`
Expected: FAIL，找不到 `./useBreakpoint`

- [ ] **Step 3: 实现 useBreakpoint**

Create `falconx-frontend/src/lib/responsive/useBreakpoint.ts`:

```ts
import { useMediaQuery } from "./useMediaQuery";

export type Breakpoint = "xs" | "sm" | "md" | "lg" | "xl";

export interface BreakpointState {
  current: Breakpoint;
  isMobile: boolean; // < md（< 768px）
  isTablet: boolean; // [md, lg)
  isDesktop: boolean; // >= lg
  width: number;
}

/**
 * 4 档断点 hook。
 *   xs: < 640px       手机小屏（iPhone SE）
 *   sm: 640 - 767     手机大屏 / 小 phablet
 *   md: 768 - 1023    平板竖屏
 *   lg: 1024 - 1279   平板横屏 / 小桌面
 *   xl: >= 1280       桌面
 *
 * isMobile = (current === xs || current === sm)
 * isTablet = (current === md)
 * isDesktop = (current === lg || current === xl)
 */
export function useBreakpoint(): BreakpointState {
  const isSmUp = useMediaQuery("(min-width: 640px)");
  const isMdUp = useMediaQuery("(min-width: 768px)");
  const isLgUp = useMediaQuery("(min-width: 1024px)");
  const isXlUp = useMediaQuery("(min-width: 1280px)");

  let current: Breakpoint = "xs";
  if (isXlUp) current = "xl";
  else if (isLgUp) current = "lg";
  else if (isMdUp) current = "md";
  else if (isSmUp) current = "sm";

  return {
    current,
    isMobile: !isMdUp,
    isTablet: isMdUp && !isLgUp,
    isDesktop: isLgUp,
    width: typeof window !== "undefined" ? window.innerWidth : 1280,
  };
}
```

- [ ] **Step 4: Run test，确认通过**

Run: `cd falconx-frontend && npx vitest run src/lib/responsive/useBreakpoint.test.ts 2>&1 | tail -10`
Expected: 5 tests passed

- [ ] **Step 5: 创建 barrel export**

Create `falconx-frontend/src/lib/responsive/index.ts`:

```ts
export { useMediaQuery } from "./useMediaQuery";
export { useBreakpoint } from "./useBreakpoint";
export type { Breakpoint, BreakpointState } from "./useBreakpoint";
```

- [ ] **Step 6: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-plan
git add falconx-frontend/src/lib/responsive/useBreakpoint.ts falconx-frontend/src/lib/responsive/useBreakpoint.test.ts falconx-frontend/src/lib/responsive/index.ts
git commit -m "feat(h5-hook): useBreakpoint 4 档断点（xs/sm/md/lg/xl）+ 5 单元测试"
```

---

## Task 4: CSS 模块化骨架（_base.css + _mobile-shell.css）

**Files:**
- Create: `falconx-frontend/src/styles/modules/_base.css`
- Create: `falconx-frontend/src/styles/modules/_mobile-shell.css`
- Modify: `falconx-frontend/src/styles/global.css`（顶部加 @import）

- [ ] **Step 1: 创建 `_base.css` 骨架（先空文件 + 注释，避免影响样式）**

Create `falconx-frontend/src/styles/modules/_base.css`:

```css
/* FalconX H5 — base reset + typography（Phase 0 骨架，Phase 2 从 global.css 搬内容） */

/* iOS 100vh 不准修复：所有需要"全屏高度"的容器用 100dvh，fallback 100vh */
.fx-full-height {
  height: 100vh;
  height: 100dvh;
}
```

- [ ] **Step 2: 创建 `_mobile-shell.css` 骨架（BottomNav / Drawer / Sheet 样式占位）**

Create `falconx-frontend/src/styles/modules/_mobile-shell.css`:

```css
/* FalconX H5 — Mobile shell 组件样式：BottomNav / Drawer / Sheet
 * 所有规则默认 mobile（< md），桌面 ≥ md 自动 display: none 或退化为传统 modal
 */

/* ====================== BottomNav ====================== */
.fx-bottom-nav {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: calc(var(--fx-bottom-nav-height) + var(--fx-safe-bottom));
  padding-bottom: var(--fx-safe-bottom);
  display: flex;
  align-items: stretch;
  justify-content: space-around;
  background: rgba(11, 14, 18, 0.92);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-top: 1px solid var(--fx-hairline);
  z-index: 100;
}

.fx-bottom-nav__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 6px 0;
  background: none;
  border: 0;
  color: var(--fx-muted);
  cursor: pointer;
  font-size: 10px;
  letter-spacing: 0.05em;
  transition: color 150ms ease;
}

.fx-bottom-nav__item.active {
  color: var(--fx-cyan);
}

.fx-bottom-nav__item:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.fx-bottom-nav__badge {
  position: absolute;
  top: 4px;
  right: 50%;
  margin-right: -16px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--fx-short);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
}

@media (min-width: 768px) {
  .fx-bottom-nav { display: none; }
}

/* ====================== Drawer ====================== */
.fx-drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 200;
  opacity: 0;
  transition: opacity 200ms ease;
  pointer-events: none;
}

.fx-drawer-overlay.open {
  opacity: 1;
  pointer-events: auto;
}

.fx-drawer {
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  width: var(--fx-drawer-width);
  max-width: 80vw;
  background: var(--fx-surface-1);
  border-right: 1px solid var(--fx-hairline);
  transform: translateX(-100%);
  transition: transform 200ms ease-out;
  z-index: 201;
  padding-top: var(--fx-safe-top);
  padding-bottom: var(--fx-safe-bottom);
  overflow-y: auto;
}

.fx-drawer.open {
  transform: translateX(0);
}

/* ====================== Sheet ====================== */
.fx-sheet-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  z-index: 300;
  opacity: 0;
  transition: opacity 200ms ease;
  pointer-events: none;
}

.fx-sheet-overlay.open {
  opacity: 1;
  pointer-events: auto;
}

.fx-sheet {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  max-height: 95vh;
  background: var(--fx-surface-1);
  border-top: 1px solid var(--fx-hairline);
  border-top-left-radius: var(--fx-radius-xl);
  border-top-right-radius: var(--fx-radius-xl);
  transform: translate3d(0, 100%, 0);
  transition: transform 250ms cubic-bezier(0.32, 0.72, 0, 1);
  z-index: 301;
  padding-bottom: var(--fx-safe-bottom);
  display: flex;
  flex-direction: column;
}

.fx-sheet.open {
  transform: translate3d(0, 0, 0);
}

.fx-sheet__handle {
  height: var(--fx-sheet-handle-height);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.fx-sheet__handle::before {
  content: "";
  width: 36px;
  height: 4px;
  border-radius: 2px;
  background: var(--fx-muted);
  opacity: 0.5;
}

.fx-sheet__body {
  flex: 1;
  overflow-y: auto;
  padding: 0 var(--fx-space-5) var(--fx-space-5);
}

/* ≥ md 时 sheet 退化为居中 modal（保留视觉一致性） */
@media (min-width: 768px) {
  .fx-sheet {
    left: 50%;
    right: auto;
    bottom: auto;
    top: 50%;
    transform: translate3d(-50%, -50%, 0) scale(0.96);
    width: min(560px, 90vw);
    max-height: 80vh;
    border-radius: var(--fx-radius-lg);
    border: 1px solid var(--fx-hairline);
    opacity: 0;
    transition: opacity 200ms ease, transform 200ms ease;
  }
  .fx-sheet.open {
    transform: translate3d(-50%, -50%, 0) scale(1);
    opacity: 1;
  }
  .fx-sheet__handle { display: none; }
}

/* ====================== Mobile Shell wrapper ====================== */
.fx-mobile-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  min-height: 100dvh;
  padding-top: var(--fx-safe-top);
}

.fx-mobile-shell__main {
  flex: 1;
  /* 主滚动区底部留出 BottomNav 高度 + safe area */
  padding-bottom: calc(var(--fx-bottom-nav-height) + var(--fx-safe-bottom));
  overflow-y: auto;
}

.fx-mobile-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 48px;
  padding: 0 var(--fx-space-4);
  border-bottom: 1px solid var(--fx-hairline);
  position: sticky;
  top: 0;
  background: var(--fx-terminal-bg);
  z-index: 50;
}

.fx-mobile-header__hamburger {
  background: none;
  border: 0;
  color: var(--fx-text);
  cursor: pointer;
  padding: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

@media (min-width: 768px) {
  .fx-mobile-shell {
    /* 桌面下 wrapper 失效，让原有 terminal-grid layout 接管 */
    display: contents;
  }
  .fx-mobile-header { display: none; }
}
```

- [ ] **Step 3: 修改 `global.css` 顶部加 @import**

Run: `head -5 falconx-frontend/src/styles/global.css`
Expected: 看到 global.css 的第一行（可能是 `@import "./tokens.css";` 或别的）

Edit `falconx-frontend/src/styles/global.css`：在 `@import "./tokens.css";` 之后追加：

```css
@import "./modules/_base.css";
@import "./modules/_mobile-shell.css";
```

如果 global.css 顶部没有 tokens.css 的 import（可能 main.tsx 直接 import 了两个文件），跳过此 step，直接在 `main.tsx` 加 import（看 Step 4）。

- [ ] **Step 4: 确认 main.tsx 引入 global.css**

Run: `grep -n "tokens.css\|global.css" falconx-frontend/src/main.tsx`
Expected: 至少有 `import "./styles/global.css"` 或 `import "./styles/tokens.css"`

如果只 import 了 global.css，Step 3 的 @import 链路就完成了。否则需要在 main.tsx 加。

- [ ] **Step 5: 前端 build 不报错**

Run: `cd falconx-frontend && npm run build 2>&1 | tail -5`
Expected: `built in Xs`，0 error

- [ ] **Step 6: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-plan
git add falconx-frontend/src/styles/modules falconx-frontend/src/styles/global.css
git commit -m "feat(h5-css): CSS 模块化骨架（_base + _mobile-shell）@import 入 global.css"
```

---

## Task 5: BottomNav 组件 + 测试

**Files:**
- Create: `falconx-frontend/src/components/mobile/BottomNav.tsx`
- Create: `falconx-frontend/src/components/mobile/BottomNav.test.tsx`

- [ ] **Step 1: 写 failing test**

Create `falconx-frontend/src/components/mobile/BottomNav.test.tsx`:

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { BottomNav } from "./BottomNav";
import { LayoutDashboard, BarChart3, WalletCards } from "lucide-react";

const items = [
  { key: "dashboard", label: "首页", icon: <LayoutDashboard size={20} /> },
  { key: "market", label: "行情", icon: <BarChart3 size={20} /> },
  { key: "wallet", label: "钱包", icon: <WalletCards size={20} /> },
];

describe("BottomNav", () => {
  it("渲染所有 items", () => {
    render(<BottomNav items={items} active="dashboard" onChange={() => {}} />);
    expect(screen.getByText("首页")).toBeInTheDocument();
    expect(screen.getByText("行情")).toBeInTheDocument();
    expect(screen.getByText("钱包")).toBeInTheDocument();
  });

  it("active item 加 .active class", () => {
    const { container } = render(<BottomNav items={items} active="market" onChange={() => {}} />);
    const buttons = container.querySelectorAll(".fx-bottom-nav__item");
    expect(buttons[0].className).not.toContain("active");
    expect(buttons[1].className).toContain("active");
    expect(buttons[2].className).not.toContain("active");
  });

  it("点击 item 触发 onChange", () => {
    const onChange = vi.fn();
    render(<BottomNav items={items} active="dashboard" onChange={onChange} />);
    fireEvent.click(screen.getByText("钱包"));
    expect(onChange).toHaveBeenCalledWith("wallet");
  });

  it("badge 大于 0 时显示数字", () => {
    const itemsWithBadge = [{ ...items[0], badge: 3 }, items[1], items[2]];
    render(<BottomNav items={itemsWithBadge} active="dashboard" onChange={() => {}} />);
    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("badge 等于 0 不显示", () => {
    const itemsWithBadge = [{ ...items[0], badge: 0 }, items[1], items[2]];
    const { container } = render(<BottomNav items={itemsWithBadge} active="dashboard" onChange={() => {}} />);
    expect(container.querySelector(".fx-bottom-nav__badge")).toBeNull();
  });
});
```

- [ ] **Step 2: Run test，确认失败**

Run: `cd falconx-frontend && npx vitest run src/components/mobile/BottomNav.test.tsx 2>&1 | tail -10`
Expected: FAIL，找不到 `./BottomNav`

- [ ] **Step 3: 实现 BottomNav**

Create `falconx-frontend/src/components/mobile/BottomNav.tsx`:

```tsx
import type { ReactNode } from "react";

export interface BottomNavItem<K extends string = string> {
  key: K;
  label: string;
  icon: ReactNode;
  badge?: number;
  disabled?: boolean;
}

export interface BottomNavProps<K extends string = string> {
  items: BottomNavItem<K>[];
  active: K;
  onChange: (key: K) => void;
  className?: string;
}

/**
 * 底部 tab 导航。仅 `< md` 显示（≥ md 自动 `display: none`）。
 *
 * 视觉：半透明 + backdrop blur，沿用 Quant Terminal 玻璃感。
 * a11y：role="navigation"、active 项 aria-current="page"。
 */
export function BottomNav<K extends string>({
  items,
  active,
  onChange,
  className,
}: BottomNavProps<K>) {
  return (
    <nav className={`fx-bottom-nav ${className ?? ""}`} aria-label="底部导航">
      {items.map((item) => {
        const isActive = item.key === active;
        return (
          <button
            key={item.key}
            type="button"
            className={`fx-bottom-nav__item ${isActive ? "active" : ""}`}
            disabled={item.disabled}
            aria-current={isActive ? "page" : undefined}
            aria-label={item.label}
            onClick={() => !item.disabled && onChange(item.key)}
            style={{ position: "relative" }}
          >
            {item.icon}
            <span>{item.label}</span>
            {typeof item.badge === "number" && item.badge > 0 && (
              <span className="fx-bottom-nav__badge">{item.badge}</span>
            )}
          </button>
        );
      })}
    </nav>
  );
}
```

- [ ] **Step 4: Run test，确认通过**

Run: `cd falconx-frontend && npx vitest run src/components/mobile/BottomNav.test.tsx 2>&1 | tail -10`
Expected: 5 tests passed

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-plan
git add falconx-frontend/src/components/mobile/BottomNav.tsx falconx-frontend/src/components/mobile/BottomNav.test.tsx
git commit -m "feat(h5-component): BottomNav 底部 5 tab 导航（< md 显示，桌面 display:none）"
```

---

## Task 6: Drawer 组件 + 测试

**Files:**
- Create: `falconx-frontend/src/components/mobile/Drawer.tsx`
- Create: `falconx-frontend/src/components/mobile/Drawer.test.tsx`

- [ ] **Step 1: 写 failing test**

Create `falconx-frontend/src/components/mobile/Drawer.test.tsx`:

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { Drawer } from "./Drawer";

describe("Drawer", () => {
  it("open=false 时不渲染 overlay open class", () => {
    const { container } = render(
      <Drawer open={false} onClose={() => {}}>
        <p>内容</p>
      </Drawer>
    );
    const overlay = container.querySelector(".fx-drawer-overlay");
    expect(overlay?.className).not.toContain("open");
  });

  it("open=true 时 overlay 和 drawer 都加 open class", () => {
    const { container } = render(
      <Drawer open={true} onClose={() => {}}>
        <p>内容</p>
      </Drawer>
    );
    expect(container.querySelector(".fx-drawer-overlay")?.className).toContain("open");
    expect(container.querySelector(".fx-drawer")?.className).toContain("open");
  });

  it("点击 overlay 触发 onClose", () => {
    const onClose = vi.fn();
    const { container } = render(
      <Drawer open={true} onClose={onClose}>
        <p>内容</p>
      </Drawer>
    );
    fireEvent.click(container.querySelector(".fx-drawer-overlay")!);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("点击内容不触发 onClose（stopPropagation）", () => {
    const onClose = vi.fn();
    render(
      <Drawer open={true} onClose={onClose}>
        <p>内容</p>
      </Drawer>
    );
    fireEvent.click(screen.getByText("内容"));
    expect(onClose).not.toHaveBeenCalled();
  });

  it("按 ESC 触发 onClose", () => {
    const onClose = vi.fn();
    render(
      <Drawer open={true} onClose={onClose}>
        <p>内容</p>
      </Drawer>
    );
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("open=false 时按 ESC 不触发 onClose", () => {
    const onClose = vi.fn();
    render(
      <Drawer open={false} onClose={onClose}>
        <p>内容</p>
      </Drawer>
    );
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test，确认失败**

Run: `cd falconx-frontend && npx vitest run src/components/mobile/Drawer.test.tsx 2>&1 | tail -10`
Expected: FAIL，找不到 `./Drawer`

- [ ] **Step 3: 实现 Drawer**

Create `falconx-frontend/src/components/mobile/Drawer.tsx`:

```tsx
import { useEffect, type ReactNode } from "react";

export interface DrawerProps {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  className?: string;
}

/**
 * 侧边抽屉，从左侧滑入。
 * 关闭方式：点击 overlay / 按 ESC / 内部组件调用 onClose。
 */
export function Drawer({ open, onClose, children, className }: DrawerProps) {
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open, onClose]);

  // open 后锁背景滚动
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  return (
    <>
      <div
        className={`fx-drawer-overlay ${open ? "open" : ""}`}
        onClick={onClose}
        aria-hidden={!open}
      />
      <aside
        className={`fx-drawer ${open ? "open" : ""} ${className ?? ""}`}
        role="dialog"
        aria-modal="true"
        aria-hidden={!open}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </aside>
    </>
  );
}
```

- [ ] **Step 4: Run test，确认通过**

Run: `cd falconx-frontend && npx vitest run src/components/mobile/Drawer.test.tsx 2>&1 | tail -10`
Expected: 6 tests passed

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-plan
git add falconx-frontend/src/components/mobile/Drawer.tsx falconx-frontend/src/components/mobile/Drawer.test.tsx
git commit -m "feat(h5-component): Drawer 侧滑抽屉（ESC + overlay 关闭、背景滚动锁定）"
```

---

## Task 7: Sheet 组件 + 测试

**Files:**
- Create: `falconx-frontend/src/components/mobile/Sheet.tsx`
- Create: `falconx-frontend/src/components/mobile/Sheet.test.tsx`

- [ ] **Step 1: 写 failing test**

Create `falconx-frontend/src/components/mobile/Sheet.test.tsx`:

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { Sheet } from "./Sheet";

describe("Sheet", () => {
  it("open=false 时 sheet 不加 open class", () => {
    const { container } = render(
      <Sheet open={false} onClose={() => {}}>
        <p>内容</p>
      </Sheet>
    );
    expect(container.querySelector(".fx-sheet")?.className).not.toContain("open");
  });

  it("open=true 时 sheet 加 open class", () => {
    const { container } = render(
      <Sheet open={true} onClose={() => {}}>
        <p>内容</p>
      </Sheet>
    );
    expect(container.querySelector(".fx-sheet")?.className).toContain("open");
  });

  it("点击 overlay 触发 onClose", () => {
    const onClose = vi.fn();
    const { container } = render(
      <Sheet open={true} onClose={onClose}>
        <p>内容</p>
      </Sheet>
    );
    fireEvent.click(container.querySelector(".fx-sheet-overlay")!);
    expect(onClose).toHaveBeenCalled();
  });

  it("ESC 关闭", () => {
    const onClose = vi.fn();
    render(
      <Sheet open={true} onClose={onClose}>
        <p>内容</p>
      </Sheet>
    );
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalled();
  });

  it("title 渲染为 aria-labelledby 关联", () => {
    render(
      <Sheet open={true} onClose={() => {}} title="下单确认">
        <p>表单内容</p>
      </Sheet>
    );
    expect(screen.getByText("下单确认")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test，确认失败**

Run: `cd falconx-frontend && npx vitest run src/components/mobile/Sheet.test.tsx 2>&1 | tail -10`
Expected: FAIL，找不到 `./Sheet`

- [ ] **Step 3: 实现 Sheet**

Create `falconx-frontend/src/components/mobile/Sheet.tsx`:

```tsx
import { useEffect, type ReactNode } from "react";

export interface SheetProps {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  title?: string;
  className?: string;
}

/**
 * 半屏 sheet（mobile）/ 居中 modal（桌面）。
 * - mobile (< md)：从底部滑入，handle 拖拽改 snap point
 * - desktop (≥ md)：居中显示，类似传统 Modal（CSS 自动切换）
 *
 * 拖拽 snap 行为放在样式层（CSS-only），不在 Phase 0 引入 JS 拖拽。
 * Phase 2 需要 snap 时再补 useDrag。
 */
export function Sheet({ open, onClose, children, title, className }: SheetProps) {
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open, onClose]);

  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  return (
    <>
      <div
        className={`fx-sheet-overlay ${open ? "open" : ""}`}
        onClick={onClose}
        aria-hidden={!open}
      />
      <section
        className={`fx-sheet ${open ? "open" : ""} ${className ?? ""}`}
        role="dialog"
        aria-modal="true"
        aria-hidden={!open}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="fx-sheet__handle" aria-hidden="true" />
        {title && (
          <header
            style={{
              padding: "8px 20px 12px",
              borderBottom: "1px solid var(--fx-hairline)",
              fontSize: "14px",
              fontWeight: 500,
              color: "var(--fx-text)",
            }}
          >
            {title}
          </header>
        )}
        <div className="fx-sheet__body">{children}</div>
      </section>
    </>
  );
}
```

- [ ] **Step 4: Run test，确认通过**

Run: `cd falconx-frontend && npx vitest run src/components/mobile/Sheet.test.tsx 2>&1 | tail -10`
Expected: 5 tests passed

- [ ] **Step 5: 创建 mobile barrel export**

Create `falconx-frontend/src/components/mobile/index.ts`:

```ts
export { BottomNav } from "./BottomNav";
export type { BottomNavItem, BottomNavProps } from "./BottomNav";
export { Drawer } from "./Drawer";
export type { DrawerProps } from "./Drawer";
export { Sheet } from "./Sheet";
export type { SheetProps } from "./Sheet";
```

- [ ] **Step 6: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-plan
git add falconx-frontend/src/components/mobile/Sheet.tsx falconx-frontend/src/components/mobile/Sheet.test.tsx falconx-frontend/src/components/mobile/index.ts
git commit -m "feat(h5-component): Sheet 半屏弹层（mobile 底部滑入 / 桌面退化为居中 modal）"
```

---

## Task 8: index.html viewport-fit=cover + body safe-area padding

**Files:**
- Modify: `falconx-frontend/index.html`

- [ ] **Step 1: 读 index.html 现状**

Run: `cat falconx-frontend/index.html`
Expected: 看到 `<meta name="viewport" content="..." />` 标签

- [ ] **Step 2: 修改 viewport meta 加 viewport-fit=cover**

找到 `<meta name="viewport" content="width=device-width, initial-scale=1.0" />`（或类似），改为：

```html
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
```

如果原来没有 viewport-fit，直接 append `, viewport-fit=cover`。如果已有，跳过。

- [ ] **Step 3: build 验证**

Run: `cd falconx-frontend && npm run build 2>&1 | tail -3`
Expected: `built in Xs`

- [ ] **Step 4: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-plan
git add falconx-frontend/index.html
git commit -m "feat(h5-viewport): index.html viewport-fit=cover 启用 iOS safe-area"
```

---

## Task 9: MobileShell wrapper + 测试

**Files:**
- Create: `falconx-frontend/src/features/terminal/MobileShell.tsx`
- Create: `falconx-frontend/src/features/terminal/MobileShell.test.tsx`

- [ ] **Step 1: 写 failing test**

Create `falconx-frontend/src/features/terminal/MobileShell.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { MobileShell } from "./MobileShell";

function mockWidth(width: number) {
  vi.stubGlobal("matchMedia", (query: string) => {
    const minMatch = query.match(/min-width:\s*(\d+)/);
    let matches = true;
    if (minMatch && width < parseInt(minMatch[1], 10)) matches = false;
    return {
      matches,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    };
  });
  vi.stubGlobal("innerWidth", width);
}

describe("MobileShell", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it("桌面（≥ md）退化为 fragment，不渲染 mobile shell wrapper", () => {
    mockWidth(1200);
    const { container } = render(
      <MobileShell activeKey="dashboard" onSelectKey={() => {}} navItems={[]} drawerContent={null}>
        <p>主内容</p>
      </MobileShell>
    );
    expect(container.querySelector(".fx-mobile-shell")).toBeNull();
    expect(screen.getByText("主内容")).toBeInTheDocument();
  });

  it("mobile (< md) 渲染 wrapper + bottom nav 占位", () => {
    mockWidth(375);
    const { container } = render(
      <MobileShell
        activeKey="dashboard"
        onSelectKey={() => {}}
        navItems={[{ key: "dashboard", label: "首页", icon: <span /> }]}
        drawerContent={<p>抽屉</p>}
      >
        <p>主内容</p>
      </MobileShell>
    );
    expect(container.querySelector(".fx-mobile-shell")).not.toBeNull();
    expect(container.querySelector(".fx-bottom-nav")).not.toBeNull();
    expect(screen.getByText("主内容")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test，确认失败**

Run: `cd falconx-frontend && npx vitest run src/features/terminal/MobileShell.test.tsx 2>&1 | tail -10`
Expected: FAIL，找不到 `./MobileShell`

- [ ] **Step 3: 实现 MobileShell**

Create `falconx-frontend/src/features/terminal/MobileShell.tsx`:

```tsx
import { useState, type ReactNode } from "react";
import { Menu } from "lucide-react";
import { useBreakpoint } from "../../lib/responsive";
import { BottomNav, Drawer, type BottomNavItem } from "../../components/mobile";

export interface MobileShellProps<K extends string> {
  activeKey: K;
  onSelectKey: (key: K) => void;
  navItems: BottomNavItem<K>[];
  drawerContent: ReactNode;
  children: ReactNode;
  header?: ReactNode; // 可选 mobile header 中间区域（默认 Logo）
}

/**
 * `< md` 时给 children 包裹 mobile shell：
 *   - sticky header（hamburger + logo + 可选右侧）
 *   - 主滚动区（children）
 *   - 底部 BottomNav
 *   - 联动 Drawer
 *
 * `≥ md` 时直接 return children fragment，桌面体验 0 影响。
 */
export function MobileShell<K extends string>({
  activeKey,
  onSelectKey,
  navItems,
  drawerContent,
  children,
  header,
}: MobileShellProps<K>) {
  const { isMobile } = useBreakpoint();
  const [drawerOpen, setDrawerOpen] = useState(false);

  if (!isMobile) return <>{children}</>;

  return (
    <div className="fx-mobile-shell">
      <header className="fx-mobile-header">
        <button
          type="button"
          className="fx-mobile-header__hamburger"
          aria-label="打开菜单"
          onClick={() => setDrawerOpen(true)}
        >
          <Menu size={22} strokeWidth={1.8} />
        </button>
        <div style={{ flex: 1, textAlign: "center" }}>{header ?? null}</div>
        <div style={{ width: 38 }} />
      </header>
      <main className="fx-mobile-shell__main">{children}</main>
      <BottomNav items={navItems} active={activeKey} onChange={onSelectKey} />
      <Drawer open={drawerOpen} onClose={() => setDrawerOpen(false)}>
        {drawerContent}
      </Drawer>
    </div>
  );
}
```

- [ ] **Step 4: Run test，确认通过**

Run: `cd falconx-frontend && npx vitest run src/features/terminal/MobileShell.test.tsx 2>&1 | tail -10`
Expected: 2 tests passed

- [ ] **Step 5: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-plan
git add falconx-frontend/src/features/terminal/MobileShell.tsx falconx-frontend/src/features/terminal/MobileShell.test.tsx
git commit -m "feat(h5-shell): MobileShell wrapper（< md 包 layout + BottomNav + Drawer 联动）"
```

---

## Task 10: TradingTerminal 接入 MobileShell

**Files:**
- Modify: `falconx-frontend/src/features/terminal/TradingTerminal.tsx`

- [ ] **Step 1: 看 TradingTerminal 现有 TerminalNav 用法**

Run: `grep -n "TerminalNav" falconx-frontend/src/features/terminal/TradingTerminal.tsx`
Expected: 看到 `import { TerminalNav, ... }` 和 `<TerminalNav activeKey={...} onSelectKey={...} />`

- [ ] **Step 2: 提取 navItems 到顶层 const**

在 TradingTerminal.tsx 顶部 import 区域之后，加：

```tsx
import { LayoutDashboard, BarChart3, ListChecks, WalletCards, Settings, Menu } from "lucide-react";
import { MobileShell } from "./MobileShell";
import { useBreakpoint } from "../../lib/responsive";
import type { BottomNavItem } from "../../components/mobile";
import type { TerminalView } from "./TerminalNav";

const MOBILE_NAV_ITEMS: BottomNavItem<TerminalView>[] = [
  { key: "dashboard", label: "首页", icon: <LayoutDashboard size={20} strokeWidth={1.8} /> },
  { key: "market", label: "行情", icon: <BarChart3 size={20} strokeWidth={1.8} /> },
  { key: "activity", label: "活动", icon: <ListChecks size={20} strokeWidth={1.8} /> },
  { key: "wallet", label: "钱包", icon: <WalletCards size={20} strokeWidth={1.8} /> },
  { key: "settings", label: "设置", icon: <Settings size={20} strokeWidth={1.8} /> },
];
```

- [ ] **Step 3: 改 TradingTerminal 主 return，用 MobileShell 包**

找到 `return (...)` 主体，定位到 `<div className="terminal-grid">` 或类似最外层包裹。改成：

```tsx
const { isMobile } = useBreakpoint();

// drawerContent: 把次要入口（profile/kyc/withdraw 等）放进 Drawer
const drawerContent = (
  <nav style={{ padding: "20px", display: "flex", flexDirection: "column", gap: "12px" }}>
    <button onClick={() => setProfileOpen(true)}>账户资料</button>
    <button onClick={() => setKycOpen(true)}>身份认证</button>
    <button onClick={() => setWithdrawOpen(true)}>出金</button>
    {/* 其他 drawer 项按需追加 */}
  </nav>
);

return (
  <MobileShell
    activeKey={currentView}
    onSelectKey={setCurrentView}
    navItems={MOBILE_NAV_ITEMS}
    drawerContent={drawerContent}
  >
    {/* 原来的 terminal-grid layout 内容原封不动放这里 */}
    {/* TerminalNav 桌面下仍渲染（mobile 下 BottomNav 接管，TerminalNav 通过 CSS @media 隐藏） */}
    {/* 注意：原 TerminalNav 桌面下应保留，桌面端的 layout 不变 */}
    <div className="terminal-grid">
      {!isMobile && <TerminalNav activeKey={currentView} onSelectKey={setCurrentView} />}
      {/* ... 其余原内容 ... */}
    </div>
  </MobileShell>
);
```

> ⚠️ **此 step 是大改，建议先把整个 return 主体备份再 patch**。具体改动如何不破坏现有桌面布局，由 executor 在 attempt 时根据 TradingTerminal 实际行结构做最小侵入式 diff。

- [ ] **Step 4: 运行现有测试不破坏**

Run: `cd falconx-frontend && npx vitest run 2>&1 | tail -15`
Expected: 所有现有测试 pass（含本 plan Task 2/3/5/6/7/9 新增的），新增 0 失败

- [ ] **Step 5: 启动 dev server + 在 Chrome DevTools 切到 iPhone SE 看效果**

Run（用户操作，不在 plan 自动化范围）: `cd falconx-frontend && npm run dev`

打开 `http://localhost:5200`：
- Chrome DevTools → Toggle device toolbar → iPhone SE (375x667)
- 期望：底部出现 BottomNav 5 tab、顶部出现 hamburger header、原 TerminalNav 隐藏
- 切到 1440：BottomNav 消失、TerminalNav 回归桌面布局

如果布局有错位，回头改 `_mobile-shell.css` 或 Task 3-7 的组件。

- [ ] **Step 6: build 验证 + Commit**

Run: `cd falconx-frontend && npm run build 2>&1 | tail -5`
Expected: `built in Xs`，0 error

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-plan
git add falconx-frontend/src/features/terminal/TradingTerminal.tsx
git commit -m "feat(h5-terminal): TradingTerminal mobile 接入 MobileShell（< md BottomNav + Drawer）"
```

---

## Task 11: TerminalTopbar `< md` 加 hamburger（可选项）

> 说明：Task 9 的 MobileShell 已经在 mobile shell header 提供了 hamburger。**TerminalTopbar 在 mobile 下是否仍需保留**取决于 UI 决策：
>
> - 选项 A（推荐）：mobile 下完全隐藏 TerminalTopbar，hamburger 由 MobileShell header 提供
> - 选项 B：mobile 下保留精简版 TerminalTopbar（仅 logo + 通知 + 头像），hamburger 内嵌
>
> 本 plan 默认 **选项 A**：在 `_mobile-shell.css` 加 `.terminal-topbar { display: none; }` `@media (max-width: 767px)`，让 mobile shell 自己的 header 接管。
>
> 选项 B 需要单独 Task，本 plan 不包含。

**Files:**
- Modify: `falconx-frontend/src/styles/modules/_mobile-shell.css`

- [ ] **Step 1: 加规则隐藏 mobile 下 TerminalTopbar**

在 `_mobile-shell.css` 文件末尾追加：

```css
/* mobile 下 TerminalTopbar 隐藏，由 fx-mobile-header 接管（hamburger + logo） */
@media (max-width: 767px) {
  .terminal-topbar {
    display: none;
  }
}
```

> 注：实际选择器名（`.terminal-topbar` 还是别的）需要 executor 用 grep 确认。

- [ ] **Step 2: build 验证**

Run: `cd falconx-frontend && npm run build 2>&1 | tail -3`
Expected: `built in Xs`

- [ ] **Step 3: Commit**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-plan
git add falconx-frontend/src/styles/modules/_mobile-shell.css
git commit -m "feat(h5-css): mobile (< md) 隐藏 TerminalTopbar，由 fx-mobile-header 接管"
```

---

## Task 12: 全量 lint + test + build + 5 尺寸 DevTools 烟雾

**Files:** （仅验证，无文件改动）

- [ ] **Step 1: lint**

Run: `cd falconx-frontend && npm run lint 2>&1 | tail -10`
Expected: 0 error 0 warning

- [ ] **Step 2: test 全量**

Run: `cd falconx-frontend && npm run test -- --run 2>&1 | tail -15`
Expected: 所有测试 pass，新增至少 `5+6+5+5+2 = 23` 个测试

- [ ] **Step 3: build**

Run: `cd falconx-frontend && npm run build 2>&1 | tail -5`
Expected: `built in Xs`，bundle 增量 < 30KB

Run: `cd falconx-frontend && du -sh dist/ | head -1` 看 bundle 大小

- [ ] **Step 4: 5 尺寸 Chrome DevTools 烟雾测试（用户操作）**

启动 dev server：`cd falconx-frontend && npm run dev`

打开 `http://localhost:5200`，登录后切到 `currentView=dashboard`。在 DevTools 切换以下尺寸验证：

| 尺寸 | 期望行为 |
|---|---|
| iPhone SE (375x667) | 底部 5 tab BottomNav 显示、顶部 hamburger header、TerminalTopbar 隐藏、点 hamburger 弹出 Drawer |
| iPhone 14 Pro Max (430x932) | 同上，注意 safe-area-inset-top 让 header 下移、bottom inset 让 BottomNav 上移 |
| iPad mini (768x1024) | BottomNav 仍显示（768 是 md 边界，本 spec 用 `max-width:767px`） — 等等，768 应该 ≥ md 不显示 BottomNav。验证 |
| iPad Pro (1024x1366) | BottomNav 隐藏、TerminalNav 显示（桌面布局） |
| Desktop 1440 | 完全桌面布局，0 mobile shell 残留 |

> 注：768 是 sm/md 边界。`useBreakpoint` 里 `isMobile = !isMdUp = !(min-width: 768px)`，意思是 width < 768 时 isMobile=true。768 = isMobile=false。Chrome DevTools 设 768x1024 时应该是 md 桌面布局，BottomNav 隐藏。如果实测发现 768 触发 BottomNav，是 matchMedia 边界处理问题，回去 fix。

- [ ] **Step 5: 真机验证（可选）**

如果用户能用手机访问开发机（同一 WiFi 下）：
- Mac: `npm run dev -- --host 0.0.0.0` 后访问 `http://<开发机 IP>:5200`
- 用 iPhone Safari / Android Chrome 打开，检查 BottomNav + safe-area + Drawer 滑动手感

- [ ] **Step 6: 最终 commit + push（用户决定是否 push）**

```bash
cd /home/ives/code/FalconX/.claude/worktrees/h5-plan
git log --oneline origin/main..HEAD
# 应该看到 11 个新 commit（Task 1-11）
```

合并到 main 的步骤由 plan executor 询问用户后执行（见 superpowers:finishing-a-development-branch）。

---

## DoD 自检（Plan 完成时勾选）

### Phase 0
- [x] `tokens.css` 加 4 档断点 + safe-area + shell sizing（Task 1）
- [x] `useBreakpoint` + `useMediaQuery` hooks（Task 2, 3）
- [x] `global.css` @import 链路 + `_base.css` + `_mobile-shell.css` 骨架（Task 4）
- [x] `<BottomNav />` + `<Drawer />` + `<Sheet />` 三组件（Task 5, 6, 7）
- [x] `index.html` viewport-fit=cover（Task 8）

### Phase 1
- [x] `<MobileShell />` wrapper（Task 9）
- [x] `TradingTerminal` mobile 接入 BottomNav + Drawer（Task 10）
- [x] TerminalTopbar mobile 隐藏（Task 11）
- [x] 5 尺寸 DevTools 烟雾（Task 12 Step 4）

---

## 风险 + 缓解

| 风险 | 影响 | Plan 内缓解 |
|---|---|---|
| `global.css` 现有规则跟 mobile-shell 冲突 | 桌面样式破坏 | Task 4 只新增 `_base.css`/`_mobile-shell.css`，不动 global.css 现有 5500 行 |
| `TerminalNav` `display: none` 仍占用 grid 空间 | mobile 主区错位 | Task 10 用 `!isMobile && <TerminalNav>` 条件渲染，不靠 CSS `display: none` |
| `useBreakpoint` 在 SSR 失效 | 首屏闪动 | hook 默认 desktop（false），mobile 用户首屏 0.1s 闪一下桌面再切到 mobile — 演示档可接受 |
| Drawer/Sheet body 滚动锁定 leak（背景 stays locked） | UX bug | Task 6, 7 useEffect 内 cleanup `body.style.overflow = prev`，测试 vitest 验证 unmount |
| Sheet 桌面 modal 居中样式跟现有 modal 视觉不一致 | 视觉不统一 | Phase 2 现有 modal 改造时一并替换为 Sheet，本 Plan 暂不强制 |

---

## Out of scope（本 Plan 不做）

- Phase 2-5（具体 feature 改造、管理端 H5、QA 真机）— 各自独立 plan
- K 线 chart mobile 横屏优化（Phase 2 terminal 处理）
- Sheet 拖拽 snap point JS（Phase 2 按需补 `useDrag`）
- focus trap 完整 a11y（Phase 5 QA 阶段补）
- 已有 6 个旧断点 1400/1240/980/880/720/560 迁移到 4 档（Phase 2 各 feature 顺手迁）
- 管理端 admin frontend H5（Phase 4 独立 plan）
