# FalconX 设计系统

## 1. 设计北极星

FalconX 要给用户的第一印象是：专业、高速、可信的 CFD 交易终端。界面可以高级、炫酷、有动画，但交易判断必须始终清楚、低噪音、低误触。

已确认的设计方向：

- 结构基准：`Ivory Falcon`
- 首页色调与光效：`Glass Terminal`
- 最终口径：浅色极简认证入口，使用浅 cyan、mist green 与玻璃光效；认证后进入深色 graphite 专业交易驾驶舱。

已批准的设计工件：

- `/Users/ives/.gstack/projects/ives-shao-FalconX/designs/falconx-terminal-auth-20260430-120033/approved.json`

Figma 协作口径：

- 后续页面若已有 Figma 节点，必须按 `docs/process/全栈Figma协作流程.md` 和 `figma-implement-design` 执行。
- Figma 是视觉、布局、组件状态与动效真源；后端契约仍以 REST / WebSocket / 数据库 / Kafka / 状态机等正式规范为准。
- 从 Figma 落地 React 时，必须先获取 `get_design_context` 与 `get_screenshot`，再映射到 `falconx-frontend` 的 tokens、组件和数据请求模式。

## 2. 产品界面

### 2.1 未认证入口

首屏固定为登录 / 注册入口。用户必须完成认证后才能进入交易终端。

布局：

- 居中展示 FalconX 飞行鹰隼图形和 `FalconX` 字标。
- 主按钮：`登录`。
- 次按钮：`创建账户`。
- 右上角语言选择：`中文(简体)`。
- 视口底部放置大尺寸裁切的几何鹰隼图形。
- 认证前不展示传统营销页内容。

视觉口径：

- 浅色、安静、高级、极简。
- 使用 frosted cyan、mist green、soft white 与极深文字色。
- 页面应像可信金融控制入口，而不是普通获客落地页。

### 2.2 已认证交易终端

登录成功后进入交易终端。

布局：

- 左侧紧凑导航栏，顶部放 FalconX 小图标。
- 市场列表来自 market owner 数据。
- 中央为报价与图表工作区。
- 右侧为下单面板壳。
- 底部为后续 `持仓 / 订单 / 成交 / 账本 / 强平 / Swap` 活动区。

首版前端接入范围：

- 接入认证流程。
- 接入市场品种列表和选中品种报价。
- 接入市场 WebSocket，订阅 `price.tick` 和 `kline.{interval}`。
- 不接入账户、订单、持仓、成交、账本、强平、Swap、钱包等用户侧实时数据。
- 不用轮询模拟账户 / 订单 / 持仓的伪实时能力。

交易终端可以保留未来交易区块的结构位、锁定态、空状态或明确的非实时状态，但不得暗示用户侧实时推送已经存在。

## 3. 品牌图形

Logo 方向：

- 抽象几何飞行鹰隼。
- 锐利、高速、高级，不做卡通化。
- 小尺寸下仍可识别，同时通过倾斜和翼形表达速度。

使用方式：

- 认证入口：居中大图形，位于字标上方。
- 交易终端：侧边栏紧凑图标。
- 背景视觉：认证入口底部使用放大裁切的鹰隼翼部或头部几何图形。

## 4. 色彩 Token

### 4.1 认证入口

```css
--fx-auth-bg: #eef9fb;
--fx-auth-bg-warm: #f7fbf8;
--fx-auth-glass-cyan: rgba(84, 230, 255, 0.28);
--fx-auth-glass-mint: rgba(168, 255, 92, 0.22);
--fx-auth-text: #071014;
--fx-auth-muted: #52616b;
--fx-auth-button: #071014;
--fx-auth-button-text: #ffffff;
--fx-auth-secondary: rgba(255, 255, 255, 0.86);
```

### 4.2 交易终端

```css
--fx-terminal-bg: #050608;
--fx-surface-1: #0b0e12;
--fx-surface-2: #11161c;
--fx-surface-3: #171d24;
--fx-border: rgba(255, 255, 255, 0.1);
--fx-border-strong: rgba(84, 230, 255, 0.22);
--fx-text: #f4f7fb;
--fx-muted: #9aa4b2;
--fx-subtle: #687383;
--fx-cyan: #54e6ff;
--fx-lime: #a8ff5c;
--fx-long: #a8ff5c;
--fx-short: #ff6378;
--fx-risk: #ffb84d;
--fx-danger: #ff3d57;
```

### 4.3 语义状态色

- `LIVE`：lime，并配合轻量脉冲状态点。
- `REFERENCE`：amber，明确表示不可成交。
- `MISSING`：muted gray，禁用态。
- `Long / BUY`：lime。
- `Short / SELL`：coral red。
- 风险与强平警告：先使用 amber，硬失败或危险状态才使用 danger red。

## 5. 字体

主 UI 字体：

- `Inter`，兜底为系统 sans-serif。

数字字体规则：

- 使用 `font-variant-numeric: tabular-nums`。
- 价格和 PnL 更新时不得造成横向跳动。

字重：

- 页面标题 / 字标：`800-900`。
- 分区标题：`700`。
- 表头：`650`。
- 正文和控件：`500-600`。

约束：

- `letter-spacing` 固定为 `0`。
- 不随视口宽度缩放字体。
- 交易终端内部使用紧凑字体；英雄级字号只用于认证入口品牌区域。

## 6. 布局 Token

间距：

```css
--fx-space-1: 4px;
--fx-space-2: 8px;
--fx-space-3: 12px;
--fx-space-4: 16px;
--fx-space-5: 20px;
--fx-space-6: 24px;
--fx-space-8: 32px;
```

圆角：

```css
--fx-radius-sm: 6px;
--fx-radius-md: 8px;
--fx-radius-lg: 12px;
--fx-radius-xl: 18px;
```

使用规则：

- 交易终端卡片和面板使用 `6px-10px`。
- 认证入口按钮可使用 `12px`。
- 禁止装饰性卡片套卡片。
- 可对重复项使用卡片；整页分区不做漂浮卡片。

## 7. 动效系统

动效用于表达速度和状态，不得干扰交易数据阅读。

时长：

- 微反馈：`90-140ms`。
- 面板进入 / 退出：`180-240ms`。
- 认证入口切换到终端：`500-700ms`。
- 价格闪动衰减：`250-450ms`。

缓动：

- 面板使用 spring 风格缓动。
- 按钮和输入反馈使用 ease-out。
- 交易终端内避免长时间循环动画。

必须支持的动效：

- 认证入口品牌图形轻微漂浮或玻璃 shimmer。
- 登录成功后鹰隼掠过，切入深色终端。
- WebSocket 连接状态轻量脉冲。
- 价格变化时以 lime / red 短促闪动。
- Long / Short 选择即时动效反馈。

无障碍：

- 必须遵守 `prefers-reduced-motion`。
- reduced mode 下，大位移动效改为透明度和颜色变化。

## 8. 交互规则

认证：

- `登录` 打开登录表单状态。
- `创建账户` 打开注册表单状态。
- 表单使用行内校验和 API 错误映射。
- Access Token 与 Refresh Token 处理必须遵守后端契约。

市场数据：

- 品种列表从 `/api/v1/market/symbols` 加载。
- 选中品种可先用 `/api/v1/market/quotes/{symbol}` 初始化。
- 实时行情使用 `ws://{host}/ws/v1/market?token=<accessToken>`。
- 重连成功后必须重新发送当前订阅。

延后区域：

- 账户、订单、持仓、成交、账本、强平、Swap、钱包等面板在首版不做实时接入。
- 若展示这些区域，必须展示明确的非实时状态。
- 不得加入假余额、假持仓或隐含实时更新；若设计评审需要演示数据，必须显式标记为 demo-only。

## 9. 响应式规则

桌面：

- 默认终端布局：导航栏 + 市场列表 + 图表工作区 + 下单面板 + 底部活动区。
- 主要设计断点：`1440px`。

平板：

- 市场列表收起为可展开抽屉。
- 图表和下单面板通过 tab 或 split view 保持可访问。

移动端：

- 认证入口保持居中全屏。
- 交易终端改为任务式视图：
  - `市场`
  - `图表`
  - `下单`
  - `活动`
- 不把完整桌面终端硬塞进移动端。

## 10. 验证要求

设计验证必须覆盖：

- 认证入口桌面与移动端。
- 交易终端桌面、平板与移动端。
- `prefers-reduced-motion`。
- WebSocket 已连接、重连中、错误状态。
- 长 symbol，例如 `AAPL.NAS`、`BTCUSD.p`、`EURUSD.c`。
- `LIVE`、`REFERENCE`、`MISSING` 价格状态。
