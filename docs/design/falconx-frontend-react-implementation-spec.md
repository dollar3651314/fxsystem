# FalconX 前端 React 实现规格

## 1. 范围

本文定义当前仓库内新增 `falconx-frontend` 的首版前端实现规格。

首版实现：

- 浅色极简 FalconX 认证入口。
- 登录与注册流程。
- 登录后的深色交易终端壳。
- 市场品种列表。
- 选中品种报价视图。
- 市场 WebSocket，接收 price tick 与 kline 更新。
- 账户、订单、持仓、成交、账本、强平、Swap、钱包等区域只保留非实时结构占位。

首版不实现：

- 用户侧账户 / 订单 / 持仓实时流。
- 轮询模拟账户 / 订单 / 持仓伪实时。
- 完整下单提交接入。
- 钱包入金地址接入。
- 超出首版市场图表所需的生产级图表工具集。

原因：当前后端只冻结了北向实时行情端点 `ws://{host}/ws/v1/market`。账户、订单、持仓、费用、钱包等用户侧实时端点尚未冻结。

## 2. 真源文档

实施前必须读取：

- `AGENTS.md`
- `SKILLS.md`
- `DESIGN.md`
- `docs/api/REST接口规范.md`
- `docs/api/WebSocket接口规范.md`
- `docs/api/FalconX统一接口文档.md`
- `docs/security/安全规范.md`
- `docs/process/全栈Figma协作流程.md`
- `docs/process/完成定义.md`

已批准设计工件：

- `/Users/ives/.gstack/projects/ives-shao-FalconX/designs/falconx-terminal-auth-20260430-120033/approved.json`

## 3. 推荐技术栈

在当前仓库下创建 `falconx-frontend`，使用 Vite React。

推荐依赖：

- `react`
- `react-dom`
- `typescript`
- `vite`
- `@vitejs/plugin-react`
- `lucide-react`
- `framer-motion`
- `@tanstack/react-query`
- `zustand`

理由：

- Vite 能让前端与 Maven 后端保持隔离，启动和构建都简单。
- React Query 适合 REST 认证和市场初始化请求。
- Zustand 足够承载 session、选中 symbol、行情连接状态等轻量客户端状态。
- 首版图表使用受控 SVG 展示行情趋势；后续若进入生产级交易图表，再单独评估成熟图表库。
- Framer Motion 能实现受控动效，并支持 `prefers-reduced-motion`。

## 4. 环境契约

前端环境变量：

```bash
VITE_FALCONX_API_BASE_URL=http://localhost:18080
VITE_FALCONX_WS_BASE_URL=ws://localhost:18080
```

规则：

- REST 请求使用 `VITE_FALCONX_API_BASE_URL`。
- 市场 WebSocket 使用 `VITE_FALCONX_WS_BASE_URL`。
- 浏览器不得发送 `X-Trace-Id`。
- 受保护 REST 接口可发送 `Authorization: Bearer <accessToken>`。
- WebSocket 按后端契约通过 query parameter 传入 token。

## 5. 路由结构

首版可先使用状态驱动，不强制引入深层路由：

- 未认证状态：渲染 `AuthGate`。
- 已认证状态：渲染 `TradingTerminal`。

后续可扩展为：

- `/login`
- `/register`
- `/terminal`
- `/terminal/markets`
- `/terminal/wallet`
- `/terminal/activity`

首版应先稳定前端 shell，再决定是否扩展深层路由。

## 6. 组件清单

### 6.1 基础层

- `App`
  - 管理认证 bootstrap 和 shell 切换。
- `providers/AppProviders`
  - 提供 React Query、全局错误边界。
- `styles/tokens.css`
  - 承载 `DESIGN.md` 中的 CSS custom properties。
- `styles/global.css`
  - reset、字体、focus 状态、reduced-motion 规则。

### 6.2 认证

- `features/auth/AuthGate`
  - 全屏未认证入口。
  - 展示 FalconX logo、语言选择和认证操作。
- `features/auth/AuthForm`
  - 支持登录和注册两种模式。
  - 调用后端认证接口。
- `features/auth/authApi`
  - `register`
  - `login`
  - `refresh`
  - `logout`
- `features/auth/authStore`
  - 保存 access token、refresh token、过期时间和用户状态。

### 6.3 品牌

- `components/brand/FalconMark`
  - SVG 几何飞行鹰隼。
- `components/brand/FalconWordmark`
  - 使用设计字体规则的文字字标。
- `components/brand/FalconBackdrop`
  - 认证入口底部的大尺寸裁切鹰隼图形。

### 6.4 交易终端 Shell

- `features/terminal/TradingTerminal`
  - 已认证主界面。
- `features/terminal/TerminalNav`
  - 左侧导航栏。
- `features/terminal/TerminalTopbar`
  - 搜索、选中 symbol、WebSocket 状态、会话操作。
- `features/terminal/DeferredPanel`
  - 复用的非实时占位面板，用于账户、订单、持仓等延后区域。

### 6.5 市场

- `features/market/MarketWatchlist`
  - 展示 market owner 启用品种。
- `features/market/MarketChart`
  - 使用选中报价与 WebSocket tick / kline 数据。
- `features/market/QuoteHeader`
  - 展示 bid、ask、mid、source、stale、price status。
- `features/market/marketApi`
  - `getSymbols`
  - `getQuote`
- `features/market/useMarketSocket`
  - 管理 `/ws/v1/market` 连接、订阅、取消订阅、重连、订阅恢复。
- `features/market/marketStore`
  - 保存选中 symbol、最新报价 map、kline map、连接状态。

### 6.6 下单面板壳

- `features/order-ticket/OrderTicketPreview`
  - 展示 Long / Short、数量、杠杆、TP/SL、预计保证金等 UI 壳。
  - 首版不提交订单。
  - 提交按钮保持禁用，或打开明确的 `交易接入未启用` 状态。

### 6.7 活动区壳

- `features/activity/ActivityDock`
  - Tabs：`持仓`、`订单`、`成交`、`账本`、`强平`、`Swap`。
  - 首版展示非实时空状态。

## 7. 后端接口映射

### 7.1 认证接口

`POST /api/v1/auth/register`

请求：

```json
{
  "email": "alice@example.com",
  "password": "Passw0rd!"
}
```

用途：

- 认证入口创建账户。
- 首版注册成功后切换到登录态提示，不自动登录。

`POST /api/v1/auth/login`

请求：

```json
{
  "email": "alice@example.com",
  "password": "Passw0rd!"
}
```

用途：

- 保存 `accessToken`、`refreshToken`、`accessTokenExpiresIn`、`refreshTokenExpiresIn`、`userStatus`、`emailVerified`。
- 成功后进入 `TradingTerminal`。

`POST /api/v1/auth/refresh`

用途：

- Access Token 过期前刷新。
- 若返回 `10006`，清空 session 并回到 `AuthGate`。
- Refresh Token 是一次性轮换，前端必须使用全局单飞刷新，禁止并发刷新导致旧 Refresh Token 被重复消费。

`POST /api/v1/auth/logout`

用途：

- 发送 bearer token。
- 成功后清空本地 session；若登出请求已因认证失效失败，也应清空本地 session。

### 7.2 市场 REST 接口

`GET /api/v1/market/symbols`

用途：

- 填充市场列表。
- 展示 `symbol`、分类、币种、精度、杠杆、bid / ask / mid / mark、`priceStatus`、`tradable`。
- `REFERENCE` 与 `MISSING` 不得展示成可成交状态。

`GET /api/v1/market/quotes/{symbol}`

用途：

- 在 WebSocket tick 到达前初始化选中品种。
- 展示 stale 与 source 状态。

### 7.3 市场 WebSocket

端点：

```text
ws://{host}/ws/v1/market?token=<accessToken>
```

订阅消息：

```json
{
  "type": "subscribe",
  "requestId": "req-001",
  "channels": ["price.tick", "kline.1m"],
  "symbols": ["BTCUSD"]
}
```

客户端行为：

- 只在登录后连接。
- 指数退避重连：`1s`、`2s`、`4s`、`8s`、`16s`，最大 `30s`。
- 重连成功后重新发送当前 symbol 订阅。
- 收到 close code `1008` 时，先尝试刷新 token，再重连。
- `price.tick` 写入 quote map。
- `kline.1m` 写入 chart series。
- WebSocket token 位于 URL query，前端不得把完整 WebSocket URL 或 token 写入日志、错误上报、页面错误详情或可见调试信息。

## 8. 状态模型

认证状态：

```ts
type AuthSession = {
  accessToken: string;
  refreshToken: string;
  accessTokenExpiresAt: number;
  refreshTokenExpiresAt: number;
  userStatus: "ACTIVE" | "FROZEN" | "BANNED";
  emailVerified: boolean;
};
```

市场状态：

```ts
type MarketConnectionState =
  | "idle"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "auth-refreshing"
  | "closed"
  | "error";

type PriceStatus = "LIVE" | "REFERENCE" | "MISSING";

type MarketQuote = {
  symbol: string;
  bid: string;
  ask: string;
  mid: string;
  mark: string;
  ts: string;
  source: string;
  stale: boolean;
  priceStatus?: PriceStatus;
  tradable?: boolean;
};
```

UI 状态：

```ts
type TerminalPanel =
  | "trade"
  | "markets"
  | "activity"
  | "wallet"
  | "settings";
```

## 9. 视觉实现规格

认证入口：

- 高度占满 viewport。
- 使用 `DESIGN.md` 中的浅色玻璃背景。
- 中央品牌组包含 Falcon mark 和字标。
- 登录 / 注册按钮桌面与移动端均纵向排列。
- 语言选择在桌面固定右上角，移动端位于安全边距内。
- 底部鹰隼几何图形为装饰元素，不得遮挡认证控件。

交易终端：

- 桌面 grid：
  - `72px` 导航栏。
  - `280px` 市场列表。
  - 中央图表区自适应。
  - `360px` 下单面板。
  - 底部活动区横跨图表区和下单区。
- 移动端：
  - 底部 tab 导航。
  - 每次只展示一个主要任务视图。
  - 市场、图表、下单、活动分开呈现。

## 10. 动效实现规格

使用 Framer Motion：

- 认证表单模式切换。
- 登录成功过渡。
- 终端面板进入 / 退出。
- 移动端抽屉打开 / 关闭。

使用 CSS transition：

- 按钮按压。
- Focus ring。
- 价格单元格闪动。
- WebSocket 状态脉冲。

Reduced motion：

- 禁用鹰隼掠过。
- 禁用 shimmer 循环。
- 面板滑动改为透明度变化。

## 11. 错误处理

API 响应结构：

```ts
type ApiResponse<T> = {
  code: string;
  message: string;
  data: T | null;
  timestamp: string;
  traceId: string;
};
```

规则：

- `code === "0"` 视为成功。
- 认证失败展示业务错误信息和错误码。
- `traceId` 保留在隐藏技术详情中，便于调试。
- 不展示原始 stack trace。
- WebSocket 错误在顶部状态 pill 中展示简短状态。
- 错误展示不得包含完整 Access Token、Refresh Token 或带 token 的 WebSocket URL。

重要映射：

- `10001`：session 失效，回到认证入口。
- `10003`：登录限流，停留在登录表单。
- `10005`：账号或密码错误。
- `10006`：Refresh Token 失效，清空 session。
- `30003`：报价不可用，展示不可成交报价状态。

## 12. 无障碍

要求：

- 所有按钮和输入框可通过键盘访问。
- 认证表单使用程序化 label，不只依赖 placeholder。
- 终端导航图标必须有 label 或 tooltip。
- Long / Short 控件使用 button 语义。
- 价格更新不得抢占焦点。
- 动效遵守 `prefers-reduced-motion`。
- `LIVE`、`REFERENCE`、`MISSING`、Long、Short、错误状态都不能只靠颜色表达。

## 13. 测试计划

单元测试：

- API 响应成功 / 失败解析。
- Token 过期时间转换。
- 市场 WebSocket 消息 reducer。
- 订阅恢复 payload 生成。

组件测试：

- 认证入口登录 / 注册模式切换。
- 认证表单校验。
- 市场列表渲染 `LIVE`、`REFERENCE`、`MISSING`。
- 终端延后面板不得宣称实时能力。

浏览器检查：

- 桌面认证入口。
- 移动端认证入口。
- 桌面交易终端壳。
- 移动端交易终端壳。
- WebSocket 已连接、重连中、auth-refreshing、错误状态。
- Reduced motion 模式。

构建检查：

```bash
npm run build
npm run test
```

## 14. 明确非目标

首版前端不得：

- 宣称平台已经生产可用。
- 展示假实时账户余额更新。
- 展示假实时持仓。
- 轮询账户 / 订单 / 持仓接口来模拟实时能力。
- 未经用户明确批准就接入下单提交。
- 新增后端契约。
- 新增 REST 路径、WebSocket topic 或 payload 字段。
