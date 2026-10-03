# FalconX 前端产品设计场景 Prompt（现行口径版）

> 这份文件只用于 **前端产品设计 / 原型 / 页面方案 / 交互评审** 场景。  
> 它不是仓库通用主 Prompt，也不是阶段状态或后端契约真源。  
> 发生冲突时，先信 `AGENTS.md`、`docs/process/AI协作与提示规范.md`、专题规范、`docs/setup/开发启动手册.md` 和 `docs/setup/当前开发计划.md`。

---

## 1. 使用顺序

1. 先遵守仓库根 `AGENTS.md`
2. 再遵守 `docs/process/AI协作与提示规范.md`
3. 涉及三端业务功能、Figma、React 实现或接口对接时，读取 `docs/process/AI工作模式.md` 与 `docs/process/全栈Figma协作流程.md`
4. 再读取本次前端任务对应的正式文档
5. 最后才使用本文件补充前端场景上下文与交付要求

## 2. 硬规则

- 本文件不得单独定义项目阶段状态。
- 本文件不得把示例字段、示例页面、示例能力当成正式契约真源。
- 若要写“某能力已实现 / 未实现 / Stage X 才实现”，必须先回查正式文档，不得沿用旧 Prompt 里的历史说法。
- 前端设计任务只输出前端方案、原型、信息架构、交互与文案，不擅自拍板后端契约。
- 若发现后端契约不清或文档冲突，先列冲突矩阵，不擅自补口径。

## 3. 本次前端任务的最小必读集合

每次做前端任务，至少先读：

- `AGENTS.md`
- `docs/process/AI工作模式.md`
- `docs/process/AI协作与提示规范.md`
- `docs/process/全栈Figma协作流程.md`
- `docs/setup/开发启动手册.md`
- `docs/setup/当前开发计划.md`
- `docs/api/FalconX统一接口文档.md`

按需补读：

- `docs/api/REST接口规范.md`
- `docs/api/WebSocket接口规范.md`
- `docs/security/安全规范.md`
- `docs/domain/状态机规范.md`
- `docs/market/LP自建行情源接入契约.md`
- `docs/process/逐仓模式改造方案.md`

## 4. 当前前端设计口径（仅作上下文，不是验收真源）

### 4.1 当前平台范围

- 当前交付按 **B-book** 口径推进。
- 当前开发计划的下一步是先关闭 `STAGE7A-ISOLATED-02`，再处理 `RISK-LIMIT-01`；前端输出不得把未完成项写成已验收。
- 当前不把真实 A-book 对冲作为前端必交付范围。
- 当前不把系统表述为“生产可用”或“可安全对外公测”。

### 4.2 当前已知北向能力

前端设计时，可以优先围绕下列当前已落地北向能力组织页面与流程，但输出前仍要再次回查正式文档：

- 认证：
  - `POST /api/v1/auth/register`
  - `POST /api/v1/auth/login`
  - `POST /api/v1/auth/refresh`
  - `POST /api/v1/auth/logout`
- 市场：
  - `GET /api/v1/market/quotes/{symbol}`
  - `ws://{host}/ws/v1/market`
- 交易：
  - `GET /api/v1/trading/accounts/me`
  - `GET /api/v1/trading/swap-settlements`
  - `GET /api/v1/trading/orders`
  - `GET /api/v1/trading/trades`
  - `GET /api/v1/trading/positions`
  - `GET /api/v1/trading/ledger`
  - `GET /api/v1/trading/liquidations`
  - `POST /api/v1/trading/orders/market`
  - `POST /api/v1/trading/positions/{positionId}/close`
  - `POST /api/v1/trading/positions/{positionId}/margin`
  - `PATCH /api/v1/trading/positions/{positionId}`

### 4.3 当前应避免误写的内容

- 不要再写“TP/SL Stage 7 才实现，前端先出 UI”这类历史口径。
- 不要把账户 / 订单 / 持仓 / 费用等用户侧实时推送 WebSocket 当成当前已冻结能力。
- 不要把 `CROSS` 模式当成当前已实现前提。
- 不要把真实 A-book 对冲面板当成当前必做功能。

## 5. 默认输出目标

当用户要求“完整前端方案”时，默认交付以下内容：

1. 中文 Markdown PRD
2. 页面信息架构
3. 关键页面线框或高保真原型说明
4. 组件与状态规范
5. 关键交互与异常态
6. 与当前后端契约的对齐点
7. 明确的非范围说明

若用户只要求局部页面、局部交互或设计评审，只交付对应子集，不擅自扩大范围。

## 6. 输出格式要求

输出前先写一小段：

- 本次读取了哪些正式文档
- 哪些内容是正式契约
- 哪些内容只是当前设计假设

正文建议包含：

- 页面目标
- 用户路径
- 页面结构
- 关键组件
- 状态与异常态
- 数据字段映射
- 与后端接口对齐点
- 待确认问题

## 7. 交互设计约束

- 金额 / 价格 / 数量优先按字符串处理，不在前端用浮点数硬算后回写。
- 价格、数量、PnL 展示必须遵循正式精度契约，不得在 Prompt 中硬编码旧精度。
- `stale=true` 必须在行情头、下单区域和持仓风险提示上有明确可见反馈。
- 若用户状态不是 `ACTIVE`，交易入口必须显式锁定并给出下一步引导。
- 对错误码做分治文案，不直接把后端原始 `message` 无差别暴露给终端用户。

## 8. 结束前检查

输出前逐项检查：

- 有没有把 Prompt 示例误写成正式契约
- 有没有把历史阶段口径误写成当前阶段结论
- 有没有把未冻结的实时推送、`CROSS`、A-book 当成当前已实现能力
- 有没有引用到统一接口文档、REST / WebSocket 规范与当前开发计划

若存在上述问题，先修正再输出。
