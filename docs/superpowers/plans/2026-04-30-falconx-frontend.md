# FalconX Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前仓库新增 `falconx-frontend`，实现首版 FalconX 认证入口、深色交易终端壳、市场列表、选中报价和市场 WebSocket 行情接入。

**Architecture:** 前端作为独立 Vite React 应用存在于 `falconx-frontend/`，不修改后端契约。首版只接入认证接口、市场 REST 接口和 `/ws/v1/market` 行情 WebSocket；账户、订单、持仓、成交、账本、强平、Swap、钱包等用户侧实时能力只显示非实时结构占位，不接入、不轮询、不暗示已具备实时推送。

**Tech Stack:** Vite, React, TypeScript, Vitest, Testing Library, React Query, Zustand, Framer Motion, lucide-react, lightweight-charts.

---

## Source Documents

实施前必须读取：

- `AGENTS.md`
- `SKILLS.md`
- `DESIGN.md`
- `docs/design/falconx-frontend-react-implementation-spec.md`
- `docs/api/REST接口规范.md`
- `docs/api/WebSocket接口规范.md`
- `docs/api/FalconX统一接口文档.md`
- `docs/security/安全规范.md`
- `docs/process/完成定义.md`

## File Structure

Create:

- `falconx-frontend/package.json`：前端 npm 脚本和依赖。
- `falconx-frontend/index.html`：Vite HTML entry。
- `falconx-frontend/vite.config.ts`：Vite 与 Vitest 配置。
- `falconx-frontend/tsconfig.json`：TypeScript 配置。
- `falconx-frontend/tsconfig.node.json`：Vite 配置编译配置。
- `falconx-frontend/src/main.tsx`：React entry。
- `falconx-frontend/src/App.tsx`：根据认证状态切换 `AuthGate` 与 `TradingTerminal`。
- `falconx-frontend/src/providers/AppProviders.tsx`：React Query provider。
- `falconx-frontend/src/styles/tokens.css`：`DESIGN.md` token。
- `falconx-frontend/src/styles/global.css`：全局样式、focus、reduced motion。
- `falconx-frontend/src/lib/api.ts`：统一 API response 解析、REST request helper。
- `falconx-frontend/src/lib/config.ts`：环境变量读取。
- `falconx-frontend/src/features/auth/authApi.ts`：认证接口。
- `falconx-frontend/src/features/auth/authStore.ts`：认证 session store。
- `falconx-frontend/src/features/auth/AuthGate.tsx`：浅色极简认证入口。
- `falconx-frontend/src/features/auth/AuthForm.tsx`：登录 / 注册表单。
- `falconx-frontend/src/components/brand/FalconMark.tsx`：飞行鹰隼 SVG。
- `falconx-frontend/src/components/brand/FalconWordmark.tsx`：字标。
- `falconx-frontend/src/components/brand/FalconBackdrop.tsx`：认证入口底部裁切鹰隼图形。
- `falconx-frontend/src/features/market/marketApi.ts`：市场 REST 接口。
- `falconx-frontend/src/features/market/marketTypes.ts`：市场类型。
- `falconx-frontend/src/features/market/marketStore.ts`：选中 symbol 与行情状态。
- `falconx-frontend/src/features/market/marketSocket.ts`：纯函数 reducer 与订阅消息构造。
- `falconx-frontend/src/features/market/useMarketSocket.ts`：WebSocket hook。
- `falconx-frontend/src/features/market/MarketWatchlist.tsx`：市场列表。
- `falconx-frontend/src/features/market/QuoteHeader.tsx`：报价头。
- `falconx-frontend/src/features/market/MarketChart.tsx`：首版图表。
- `falconx-frontend/src/features/terminal/TradingTerminal.tsx`：终端布局。
- `falconx-frontend/src/features/terminal/TerminalNav.tsx`：侧边导航。
- `falconx-frontend/src/features/terminal/TerminalTopbar.tsx`：顶部状态栏。
- `falconx-frontend/src/features/terminal/DeferredPanel.tsx`：非实时占位面板。
- `falconx-frontend/src/features/order-ticket/OrderTicketPreview.tsx`：不可提交的下单面板壳。
- `falconx-frontend/src/features/activity/ActivityDock.tsx`：非实时活动区。
- `falconx-frontend/src/test/setup.ts`：测试环境配置。
- `falconx-frontend/src/lib/api.test.ts`：API response 解析测试。
- `falconx-frontend/src/features/auth/authStore.test.ts`：token 过期转换测试。
- `falconx-frontend/src/features/market/marketSocket.test.ts`：WebSocket reducer 与订阅恢复测试。
- `falconx-frontend/src/features/terminal/DeferredPanel.test.tsx`：非实时文案测试。

Modify:

- `.gitignore`：补充 `falconx-frontend/node_modules`、`falconx-frontend/dist`、`falconx-frontend/coverage`。

Do not modify:

- 后端 Java 源码。
- 后端 REST / WebSocket / Kafka 契约。
- 现有 Maven 模块。
- 当前仓库中与本任务无关的未提交改动。

## Task 1: Create Frontend Shell

**Files:**

- Create: `falconx-frontend/package.json`
- Create: `falconx-frontend/index.html`
- Create: `falconx-frontend/vite.config.ts`
- Create: `falconx-frontend/tsconfig.json`
- Create: `falconx-frontend/tsconfig.node.json`
- Create: `falconx-frontend/src/main.tsx`
- Create: `falconx-frontend/src/App.tsx`
- Create: `falconx-frontend/src/providers/AppProviders.tsx`
- Create: `falconx-frontend/src/styles/tokens.css`
- Create: `falconx-frontend/src/styles/global.css`
- Create: `falconx-frontend/src/test/setup.ts`
- Modify: `.gitignore`

- [ ] **Step 1: Scaffold Vite React app**

Run:

```bash
npm create vite@latest falconx-frontend -- --template react-ts
```

Expected:

- `falconx-frontend/package.json` exists.
- `falconx-frontend/src/main.tsx` exists.

- [ ] **Step 2: Install required dependencies**

Run:

```bash
cd falconx-frontend
npm install
npm install @tanstack/react-query zustand framer-motion lucide-react lightweight-charts
npm install -D vitest jsdom @testing-library/react @testing-library/jest-dom @testing-library/user-event
```

Expected:

- `falconx-frontend/package-lock.json` is created.
- `falconx-frontend/node_modules` exists but is ignored by git.

- [ ] **Step 3: Configure scripts and Vitest**

Ensure `falconx-frontend/package.json` contains:

```json
{
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "lint": "eslint .",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  }
}
```

Ensure `falconx-frontend/vite.config.ts` configures React and Vitest `jsdom` environment:

```ts
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts"
  }
});
```

- [ ] **Step 4: Add design tokens and global styles**

Create `src/styles/tokens.css` from `DESIGN.md` color, radius, and spacing tokens. Create `src/styles/global.css` with:

- `box-sizing: border-box`
- `body` using `Inter` and system fallback
- dark terminal background default
- `font-variant-numeric: tabular-nums`
- `:focus-visible` outline
- `@media (prefers-reduced-motion: reduce)` disabling transitions and animations

- [ ] **Step 5: Wire app provider**

Create `src/providers/AppProviders.tsx`:

```tsx
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode, useState } from "react";

export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => new QueryClient());
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
```

Ensure `src/main.tsx` imports `tokens.css`, `global.css`, and wraps `App` with `AppProviders`.

- [ ] **Step 6: Verify shell build**

Run:

```bash
cd falconx-frontend
npm run build
```

Expected:

- Exit code `0`.
- `dist/` is created.

## Task 2: API Types, Auth State, and TDD Baseline

**Files:**

- Create: `falconx-frontend/src/lib/config.ts`
- Create: `falconx-frontend/src/lib/api.ts`
- Create: `falconx-frontend/src/lib/api.test.ts`
- Create: `falconx-frontend/src/features/auth/authApi.ts`
- Create: `falconx-frontend/src/features/auth/authStore.ts`
- Create: `falconx-frontend/src/features/auth/authStore.test.ts`

- [ ] **Step 1: Write failing API parser test**

Create `src/lib/api.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { unwrapApiResponse } from "./api";

describe("unwrapApiResponse", () => {
  it("returns data when backend code is success", () => {
    expect(
      unwrapApiResponse({
        code: "0",
        message: "success",
        data: { symbol: "BTCUSD.p" },
        timestamp: "2026-04-30T12:00:00Z",
        traceId: "abc"
      })
    ).toEqual({ symbol: "BTCUSD.p" });
  });

  it("throws a typed error with code and traceId when backend code fails", () => {
    expect(() =>
      unwrapApiResponse({
        code: "10005",
        message: "Invalid Credentials",
        data: null,
        timestamp: "2026-04-30T12:00:00Z",
        traceId: "trace-1"
      })
    ).toThrow("Invalid Credentials");
  });
});
```

Run:

```bash
cd falconx-frontend
npm run test -- src/lib/api.test.ts
```

Expected:

- Fails because `src/lib/api.ts` does not exist.

- [ ] **Step 2: Implement API parser and config**

Create `src/lib/config.ts`:

```ts
export const apiBaseUrl = import.meta.env.VITE_FALCONX_API_BASE_URL ?? "http://localhost:18080";
export const wsBaseUrl = import.meta.env.VITE_FALCONX_WS_BASE_URL ?? "ws://localhost:18080";
```

Create `src/lib/api.ts`:

```ts
export type ApiResponse<T> = {
  code: string;
  message: string;
  data: T | null;
  timestamp: string;
  traceId: string;
};

export class FalconApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly traceId: string
  ) {
    super(message);
    this.name = "FalconApiError";
  }
}

export function unwrapApiResponse<T>(response: ApiResponse<T>): T {
  if (response.code === "0" && response.data !== null) {
    return response.data;
  }
  throw new FalconApiError(response.code, response.message, response.traceId);
}

export async function requestJson<T>(
  path: string,
  options: RequestInit & { token?: string } = {}
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("Content-Type", "application/json");
  if (options.token) {
    headers.set("Authorization", `Bearer ${options.token}`);
  }
  const response = await fetch(path, { ...options, headers });
  return unwrapApiResponse((await response.json()) as ApiResponse<T>);
}
```

- [ ] **Step 3: Run API parser test**

Run:

```bash
cd falconx-frontend
npm run test -- src/lib/api.test.ts
```

Expected:

- Pass.

- [ ] **Step 4: Write failing auth store test**

Create `src/features/auth/authStore.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { toAuthSession } from "./authStore";

describe("toAuthSession", () => {
  it("converts expiry seconds to absolute timestamps", () => {
    const session = toAuthSession(
      {
        accessToken: "access",
        refreshToken: "refresh",
        accessTokenExpiresIn: 900,
        refreshTokenExpiresIn: 259200,
        userStatus: "ACTIVE",
        emailVerified: false
      },
      1000
    );

    expect(session.accessTokenExpiresAt).toBe(901000);
    expect(session.refreshTokenExpiresAt).toBe(260200000);
  });
});
```

Run:

```bash
cd falconx-frontend
npm run test -- src/features/auth/authStore.test.ts
```

Expected:

- Fails because `authStore.ts` does not exist.

- [ ] **Step 5: Implement auth store and auth API**

Create `src/features/auth/authStore.ts` with `AuthSession`, `AuthTokenResponse`, `toAuthSession`, and a Zustand store exposing `session`, `setSession`, and `clearSession`.

Create `src/features/auth/authApi.ts` with:

- `register(email, password)`
- `login(email, password)`
- `refresh(refreshToken)`
- `logout(accessToken)`

Rules:

- Use `requestJson`.
- Never send `X-Trace-Id`.
- Store expiry timestamps as `Date.now() + expiresIn * 1000`.
- Implement refresh as a single-flight operation. Concurrent callers must share the same in-flight refresh promise so an old Refresh Token is not consumed twice.
- Never log full Access Token, Refresh Token, or token-bearing WebSocket URLs.

- [ ] **Step 6: Run auth store test**

Run:

```bash
cd falconx-frontend
npm run test -- src/features/auth/authStore.test.ts
```

Expected:

- Pass.

## Task 3: Market WebSocket and Market Data

**Files:**

- Create: `falconx-frontend/src/features/market/marketTypes.ts`
- Create: `falconx-frontend/src/features/market/marketApi.ts`
- Create: `falconx-frontend/src/features/market/marketStore.ts`
- Create: `falconx-frontend/src/features/market/marketSocket.ts`
- Create: `falconx-frontend/src/features/market/marketSocket.test.ts`
- Create: `falconx-frontend/src/features/market/useMarketSocket.ts`

- [ ] **Step 1: Write failing market socket tests**

Create `src/features/market/marketSocket.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { buildSubscribeMessage, reduceMarketMessage } from "./marketSocket";

describe("marketSocket", () => {
  it("builds a backend-compatible subscribe message", () => {
    expect(buildSubscribeMessage("BTCUSD.p", "req-1")).toEqual({
      type: "subscribe",
      requestId: "req-1",
      channels: ["price.tick", "kline.1m"],
      symbols: ["BTCUSD.p"]
    });
  });

  it("stores price.tick payload by symbol", () => {
    const next = reduceMarketMessage(
      { quotes: {}, klines: {} },
      {
        type: "price.tick",
        symbol: "BTCUSD.p",
        bid: "68000.00",
        ask: "68001.00",
        mid: "68000.50",
        mark: "68000.50",
        ts: "2026-04-30T12:00:00Z",
        source: "TM_QUOTE",
        stale: false
      }
    );

    expect(next.quotes["BTCUSD.p"]?.bid).toBe("68000.00");
  });
});
```

Run:

```bash
cd falconx-frontend
npm run test -- src/features/market/marketSocket.test.ts
```

Expected:

- Fails because `marketSocket.ts` does not exist.

- [ ] **Step 2: Implement market types and reducer**

Create `marketTypes.ts` for symbol, quote, kline, and connection state types.

Create `marketSocket.ts` with:

- `buildSubscribeMessage(symbol, requestId)`
- `buildUnsubscribeMessage(symbol, requestId)`
- `reduceMarketMessage(state, message)`

Rules:

- Support `price.tick`.
- Support `kline.1m`.
- Preserve unknown messages by returning previous state.

- [ ] **Step 3: Run market socket tests**

Run:

```bash
cd falconx-frontend
npm run test -- src/features/market/marketSocket.test.ts
```

Expected:

- Pass.

- [ ] **Step 4: Implement market API and store**

Create `marketApi.ts`:

- `getSymbols(token)`
- `getQuote(token, symbol)`

Create `marketStore.ts`:

- `selectedSymbol`
- `setSelectedSymbol`
- `connectionState`
- `setConnectionState`
- `quotes`
- `klines`
- `applyMarketMessage`

- [ ] **Step 5: Implement WebSocket hook**

Create `useMarketSocket.ts`:

- Only connect when access token and selected symbol exist.
- Build URL as `${wsBaseUrl}/ws/v1/market?token=${encodeURIComponent(token)}`.
- Send subscribe message on `open`.
- On symbol change, unsubscribe old symbol and subscribe new symbol.
- On close, reconnect with exponential backoff up to `30s`.
- On close code `1008`, call an `onAuthExpired` callback instead of blindly reconnecting.
- On message, parse JSON and call `applyMarketMessage`.
- Never log the complete WebSocket URL because the token is in the query string.

## Task 4: Auth UI and Brand System

**Files:**

- Create: `falconx-frontend/src/components/brand/FalconMark.tsx`
- Create: `falconx-frontend/src/components/brand/FalconWordmark.tsx`
- Create: `falconx-frontend/src/components/brand/FalconBackdrop.tsx`
- Create: `falconx-frontend/src/features/auth/AuthGate.tsx`
- Create: `falconx-frontend/src/features/auth/AuthForm.tsx`
- Modify: `falconx-frontend/src/App.tsx`

- [ ] **Step 1: Implement brand components**

Create SVG-based `FalconMark` and large decorative `FalconBackdrop`.

Rules:

- The mark is geometric and original.
- No third-party brand asset is copied.
- Backdrop is `aria-hidden`.

- [ ] **Step 2: Implement AuthForm**

`AuthForm` supports:

- `mode: "login" | "register"`
- email input
- password input
- submit button
- inline error
- success message after registration

Rules:

- Labels must be programmatic.
- Password is never logged.
- Login success calls `setSession(toAuthSession(...))`.
- Register success switches to login mode and shows a success message.

- [ ] **Step 3: Implement AuthGate**

`AuthGate` renders:

- top-right language selector
- centered Falcon mark
- wordmark
- primary `登录`
- secondary `创建账户`
- form panel after selecting mode
- glass background and bottom falcon backdrop

Use Framer Motion for mode switch and brand entrance.

- [ ] **Step 4: Wire App auth state**

`App.tsx` reads `authStore.session`.

- If no session: render `AuthGate`.
- If session exists: render `TradingTerminal`.

## Task 5: Terminal UI and Deferred Panels

**Files:**

- Create: `falconx-frontend/src/features/terminal/TradingTerminal.tsx`
- Create: `falconx-frontend/src/features/terminal/TerminalNav.tsx`
- Create: `falconx-frontend/src/features/terminal/TerminalTopbar.tsx`
- Create: `falconx-frontend/src/features/terminal/DeferredPanel.tsx`
- Create: `falconx-frontend/src/features/terminal/DeferredPanel.test.tsx`
- Create: `falconx-frontend/src/features/market/MarketWatchlist.tsx`
- Create: `falconx-frontend/src/features/market/QuoteHeader.tsx`
- Create: `falconx-frontend/src/features/market/MarketChart.tsx`
- Create: `falconx-frontend/src/features/order-ticket/OrderTicketPreview.tsx`
- Create: `falconx-frontend/src/features/activity/ActivityDock.tsx`

- [ ] **Step 1: Write failing deferred panel test**

Create `DeferredPanel.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DeferredPanel } from "./DeferredPanel";

describe("DeferredPanel", () => {
  it("states that user-side realtime is not connected", () => {
    render(<DeferredPanel title="持仓" />);
    expect(screen.getByText("持仓")).toBeInTheDocument();
    expect(screen.getByText(/用户侧实时推送尚未接入/)).toBeInTheDocument();
  });
});
```

Run:

```bash
cd falconx-frontend
npm run test -- src/features/terminal/DeferredPanel.test.tsx
```

Expected:

- Fails because `DeferredPanel.tsx` does not exist.

- [ ] **Step 2: Implement DeferredPanel**

Create a panel that clearly says:

```text
用户侧实时推送尚未接入
当前区域只展示结构占位，不代表账户、订单或持仓实时更新。
```

- [ ] **Step 3: Implement MarketWatchlist and QuoteHeader**

`MarketWatchlist`:

- Uses `getSymbols`.
- Shows symbol, market code/category, bid/ask or missing state.
- Shows `LIVE`, `REFERENCE`, `MISSING`.
- Disables tradability affordance for non-`LIVE`.

`QuoteHeader`:

- Shows selected symbol, bid, ask, mid, stale/source state, and WebSocket status.

- [ ] **Step 4: Implement MarketChart**

Use `lightweight-charts` if straightforward. If chart setup blocks progress, implement a first-pass semantic SVG/canvas chart shell that can later be replaced, but do not hand-roll trading logic.

Display:

- selected symbol
- latest price
- live candles or placeholder candles
- empty state when no data exists

- [ ] **Step 5: Implement OrderTicketPreview**

Render:

- Long / Short selector
- quantity input
- leverage input
- TP / SL fields
- disabled submit button with label `交易接入未启用`

Rules:

- Do not call `/api/v1/trading/orders/market`.
- Do not imply order submission is live.

- [ ] **Step 6: Implement TradingTerminal**

Desktop layout:

- nav rail
- market watchlist
- chart workspace
- order ticket preview
- activity dock

Mobile layout:

- task-based view with bottom navigation.

Use Framer Motion for panel entry and CSS for WebSocket pulse.

- [ ] **Step 7: Run UI tests**

Run:

```bash
cd falconx-frontend
npm run test -- src/features/terminal/DeferredPanel.test.tsx
```

Expected:

- Pass.

## Task 6: Full Verification and Browser QA

**Files:**

- Modify only files under `falconx-frontend/` if fixes are required.

- [ ] **Step 1: Run full tests**

Run:

```bash
cd falconx-frontend
npm run test
```

Expected:

- Exit code `0`.
- All tests pass.

- [ ] **Step 2: Run production build**

Run:

```bash
cd falconx-frontend
npm run build
```

Expected:

- Exit code `0`.
- `dist/` created.

- [ ] **Step 3: Start dev server**

Run:

```bash
cd falconx-frontend
npm run dev -- --host 127.0.0.1 --port 5173
```

Expected:

- Local URL available at `http://127.0.0.1:5173/`.

- [ ] **Step 4: Browser QA**

Use browser tooling to check:

- Desktop auth gate at `1440x1000`.
- Mobile auth gate at `390x844`.
- Auth form mode switch.
- Terminal shell after setting a test session in local storage or using a development-only session injection if implemented.
- No visual claim that account/order/position realtime is connected.
- `prefers-reduced-motion` style path does not depend on large motion.

- [ ] **Step 5: Final git scope check**

Run:

```bash
git status --short
git diff --stat
git diff -- falconx-frontend DESIGN.md docs/design/falconx-frontend-react-implementation-spec.md docs/superpowers/plans/2026-04-30-falconx-frontend.md
```

Expected:

- Only planned frontend files, `.gitignore`, and this plan are part of this task.
- Existing unrelated dirty files remain unstaged.

## Self-Review Checklist

- [ ] 首版只接入认证、市场 REST、市场 WebSocket。
- [ ] 未接入账户 / 订单 / 持仓实时流。
- [ ] 未用轮询模拟用户侧实时能力。
- [ ] 未新增后端接口、WebSocket topic、Kafka topic 或 payload。
- [ ] 未修改后端 Java 代码。
- [ ] Refresh Token 刷新使用全局单飞，避免一次性 Refresh Token 被并发重复消费。
- [ ] 未输出完整 token 或带 token 的 WebSocket URL。
- [ ] 认证入口符合浅色玻璃方向。
- [ ] 终端壳符合深色专业交易台方向。
- [ ] 支持 reduced motion。
- [ ] `npm run test` 与 `npm run build` 有新鲜通过证据。
