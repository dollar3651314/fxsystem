# FalconX Frontend

FalconX CFD 交易终端首版前端。当前实现范围按仓库接口边界收敛：

- 登录、注册、刷新、登出走 Identity REST。
- 市场列表与首屏报价走 Market REST。
- 实时行情走 `/ws/v1/market` WebSocket。
- 账户、订单、持仓、成交、账本、强平、Swap 与钱包实时能力暂不接入，只展示禁用或占位状态。

## 本地启动

```bash
npm install
npm run dev
```

默认访问 `http://127.0.0.1:5173/`。

## 后端地址

本地开发默认通过 Vite dev proxy 访问 gateway，同源请求 `/api` 与 `/ws`，避免浏览器 CORS 干扰。

需要覆盖远端 gateway 地址时设置：

```bash
VITE_FALCONX_API_BASE_URL=http://localhost:18080
VITE_FALCONX_WS_BASE_URL=ws://localhost:18080
```

生产环境必须使用 `https` 与 `wss`。

## 验证

```bash
npm run test
npm run lint
npm run build
```
